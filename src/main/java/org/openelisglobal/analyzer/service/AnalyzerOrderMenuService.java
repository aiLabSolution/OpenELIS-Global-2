package org.openelisglobal.analyzer.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;
import org.openelisglobal.spring.util.SpringContext;
import org.openelisglobal.test.valueholder.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Resolves the pending order menu for one accession or analyzer barcode — the
 * ordered-but-unresulted tests as LOINC. This answers analyzer host queries
 * (ASTM Q-record or HL7 QRY^R02 via the bridge): the analyzer scans a barcoded
 * tube, the bridge asks OE what work is pending for that identifier, and
 * translates the returned LOINCs to the analyzer's own codes. OE stays
 * analyzer-agnostic and speaks LOINC only, the same interlingua as the
 * {@link AnalyzerOrderDispatchService} push path.
 *
 * <p>
 * "Unresulted" is defined by exclusion: Canceled, SampleRejected, Finalized,
 * TechnicalAcceptance and (legacy) NonConforming analyses are already resulted
 * or dead. Rejected analyses (TechnicalRejected, BiologistRejected) stay in the
 * menu — they are re-run work the analyzer should pick up.
 */
@Service
public class AnalyzerOrderMenuService {

    @Autowired
    private SampleService sampleService;

    @Autowired
    private AnalysisService analysisService;

    // Deliberately NOT @Autowired: nothing in core field-injects
    // IStatusService — its @PostConstruct dependency graph must not be pulled
    // into early context bootstrap. Resolved at first use, per the
    // SpringContext.getBean convention used elsewhere (tests inject a mock).
    private IStatusService statusService;

    /**
     * Resolve the pending order menu for an accession or analyzer barcode, or null
     * when no sample exists for it.
     */
    public OrderMenu getOrderMenu(String sampleIdentifier) {
        if (sampleIdentifier == null || sampleIdentifier.isBlank()) {
            throw new IllegalArgumentException("accessionNumber required");
        }

        Sample sample = null;
        for (String candidate : sampleIdentifierCandidates(sampleIdentifier)) {
            sample = sampleService.getSampleByAccessionNumber(candidate);
            if (sample != null) {
                break;
            }
        }
        if (sample == null) {
            return null;
        }

        // Same LOINC resolution as the dispatch push path (preserve order,
        // de-dupe), but restricted to analyses still awaiting a result.
        List<String> loincCodes = new ArrayList<>();
        for (Analysis analysis : analysisService.getAnalysesBySampleIdExcludedByStatusId(sample.getId(),
                resultedOrDeadStatusIds())) {
            Test test = analysis.getTest();
            String loinc = test != null ? test.getLoinc() : null;
            if (loinc != null && !loinc.isBlank() && !loincCodes.contains(loinc)) {
                loincCodes.add(loinc);
            }
        }

        OrderMenu menu = new OrderMenu();
        menu.accessionNumber = sample.getAccessionNumber();
        menu.patientId = resolvePatientId(sample);
        menu.loincCodes = loincCodes;
        return menu;
    }

    private String resolvePatientId(Sample sample) {
        Patient patient = sampleService.getPatient(sample);
        return patient != null ? patient.getId() : sample.getId();
    }

    static List<String> sampleIdentifierCandidates(String sampleIdentifier) {
        String trimmed = sampleIdentifier.trim();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        candidates.add(trimmed);

        String digits = trimmed.replaceAll("\\D", "");
        if (!digits.isBlank()) {
            String wholeDigits = stripLeadingZeros(digits);
            if (!wholeDigits.isBlank()) {
                candidates.add(wholeDigits);
            }
            for (int i = 0; i < digits.length(); i++) {
                String suffix = stripLeadingZeros(digits.substring(i));
                if (!suffix.isBlank()) {
                    candidates.add(suffix);
                }
            }
        }
        return new ArrayList<>(candidates);
    }

    private static String stripLeadingZeros(String value) {
        String stripped = value.replaceFirst("^0+", "");
        return stripped.isBlank() ? "0" : stripped;
    }

    private Set<String> resultedOrDeadStatusIds() {
        IStatusService status = statusService();
        Set<String> excluded = new HashSet<>();
        excluded.add(status.getStatusID(AnalysisStatus.Canceled));
        excluded.add(status.getStatusID(AnalysisStatus.SampleRejected));
        excluded.add(status.getStatusID(AnalysisStatus.Finalized));
        excluded.add(status.getStatusID(AnalysisStatus.TechnicalAcceptance));
        excluded.add(status.getStatusID(AnalysisStatus.NonConforming_depricated));
        return excluded;
    }

    private IStatusService statusService() {
        if (statusService == null) {
            statusService = SpringContext.getBean(IStatusService.class);
        }
        return statusService;
    }

    public static class OrderMenu {
        public String accessionNumber;
        // Optional correlation aid, same convention as the push path: the
        // analyzer keys on the accession, not the patient.
        public String patientId;
        public List<String> loincCodes;
    }
}
