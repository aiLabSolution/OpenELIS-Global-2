package org.openelisglobal.analyzer.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.openelisglobal.analysis.service.AnalysisService;
import org.openelisglobal.analysis.valueholder.Analysis;
import org.openelisglobal.common.services.IStatusService;
import org.openelisglobal.common.services.StatusService.AnalysisStatus;
import org.openelisglobal.patient.valueholder.Patient;
import org.openelisglobal.sample.service.SampleService;
import org.openelisglobal.sample.valueholder.Sample;

/**
 * The order menu is the host-query counterpart of the dispatch push path:
 * accession → sample → analyses still awaiting a result → their tests' LOINCs
 * (ordered, de-duped). Resulted/cancelled analyses are excluded via the
 * status-exclusion query; rejected analyses stay (re-run work).
 */
public class AnalyzerOrderMenuServiceTest {

    private SampleService sampleService;
    private AnalysisService analysisService;
    private IStatusService statusService;

    private AnalyzerOrderMenuService service;

    @Before
    public void setUp() throws Exception {
        sampleService = Mockito.mock(SampleService.class);
        analysisService = Mockito.mock(AnalysisService.class);
        statusService = Mockito.mock(IStatusService.class);
        when(statusService.getStatusID(AnalysisStatus.Canceled)).thenReturn("10");
        when(statusService.getStatusID(AnalysisStatus.SampleRejected)).thenReturn("11");
        when(statusService.getStatusID(AnalysisStatus.Finalized)).thenReturn("12");
        when(statusService.getStatusID(AnalysisStatus.TechnicalAcceptance)).thenReturn("13");
        when(statusService.getStatusID(AnalysisStatus.NonConforming_depricated)).thenReturn("14");

        service = new AnalyzerOrderMenuService();
        inject("sampleService", sampleService);
        inject("analysisService", analysisService);
        inject("statusService", statusService);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = AnalyzerOrderMenuService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private Analysis analysisWithLoinc(String loinc) {
        Analysis a = Mockito.mock(Analysis.class);
        org.openelisglobal.test.valueholder.Test t = Mockito.mock(org.openelisglobal.test.valueholder.Test.class);
        when(t.getLoinc()).thenReturn(loinc);
        when(a.getTest()).thenReturn(t);
        return a;
    }

    private Sample seedAccession(String accession, String sampleId, Analysis... pendingAnalyses) {
        Sample s = Mockito.mock(Sample.class);
        when(s.getId()).thenReturn(sampleId);
        when(s.getAccessionNumber()).thenReturn(accession);
        when(sampleService.getSampleByAccessionNumber(accession)).thenReturn(s);
        when(analysisService.getAnalysesBySampleIdExcludedByStatusId(eq(sampleId), Mockito.anySet()))
                .thenReturn(List.of(pendingAnalyses));
        return s;
    }

    @Test
    public void returnsPendingLoincMenuForKnownAccession() {
        seedAccession("ACC-1", "S1", analysisWithLoinc("6690-2"), analysisWithLoinc("718-7"));

        AnalyzerOrderMenuService.OrderMenu menu = service.getOrderMenu("ACC-1");

        assertEquals("ACC-1", menu.accessionNumber);
        assertEquals("S1", menu.patientId);
        assertEquals(List.of("6690-2", "718-7"), menu.loincCodes);
    }

    @Test
    public void excludesResultedAndCancelledStatusesFromTheQuery() {
        seedAccession("ACC-1", "S1", analysisWithLoinc("6690-2"));

        service.getOrderMenu("ACC-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> excluded = ArgumentCaptor.forClass((Class) Set.class);
        Mockito.verify(analysisService).getAnalysesBySampleIdExcludedByStatusId(eq("S1"), excluded.capture());
        assertEquals(Set.of("10", "11", "12", "13", "14"), excluded.getValue());
    }

    @Test
    public void dedupesAndSkipsBlankLoincsPreservingOrder() {
        seedAccession("ACC-1", "S1", analysisWithLoinc("6690-2"), analysisWithLoinc(null), analysisWithLoinc(" "),
                analysisWithLoinc("6690-2"), analysisWithLoinc("718-7"));

        AnalyzerOrderMenuService.OrderMenu menu = service.getOrderMenu("ACC-1");

        assertEquals(List.of("6690-2", "718-7"), menu.loincCodes);
    }

    @Test
    public void emptyMenuWhenAllPendingAnalysesLackLoinc() {
        seedAccession("ACC-1", "S1", analysisWithLoinc(null));

        AnalyzerOrderMenuService.OrderMenu menu = service.getOrderMenu("ACC-1");

        assertTrue(menu.loincCodes.isEmpty());
    }

    @Test
    public void unknownAccessionReturnsNull() {
        when(sampleService.getSampleByAccessionNumber(anyString())).thenReturn(null);

        assertNull(service.getOrderMenu("NOPE-1"));
    }

    @Test
    public void blankAccessionRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.getOrderMenu(" "));
        assertThrows(IllegalArgumentException.class, () -> service.getOrderMenu(null));
    }

    @Test
    public void trimsAccessionBeforeLookup() {
        seedAccession("ACC-1", "S1", analysisWithLoinc("6690-2"));

        AnalyzerOrderMenuService.OrderMenu menu = service.getOrderMenu("  ACC-1  ");

        assertEquals("ACC-1", menu.accessionNumber);
        assertEquals(List.of("6690-2"), menu.loincCodes);
    }

    @Test
    public void h99sBarcodeAliasResolvesToAccessionAndLinkedPatient() {
        Sample sample = seedAccession("2", "S99", analysisWithLoinc("6690-2"), analysisWithLoinc("718-7"));
        Patient patient = new Patient();
        patient.setId("17");
        when(sampleService.getPatient(sample)).thenReturn(patient);

        AnalyzerOrderMenuService.OrderMenu menu = service.getOrderMenu("DEV01260000000000002");

        assertEquals("2", menu.accessionNumber);
        assertEquals("17", menu.patientId);
        assertEquals(List.of("6690-2", "718-7"), menu.loincCodes);
    }

    // Canonicalization is gated on barcode shape: identifiers that are not a
    // machine-generated barcode must never fall back to embedded-digit
    // candidates, or a QC label / typo would resolve to another patient's
    // live sample and the analyzer would post results under that accession.

    @Test
    public void qcLabelDoesNotCanonicalizeToAnAccession() {
        seedAccession("1", "S1", analysisWithLoinc("6690-2"));

        assertNull(service.getOrderMenu("QC-01"));
    }

    @Test
    public void numericTypoDoesNotCanonicalizeToAnAccession() {
        seedAccession("2", "S2", analysisWithLoinc("6690-2"));

        assertNull(service.getOrderMenu("1002"));
    }

    @Test
    public void shortAlphanumericIdDoesNotCanonicalizeToAnAccession() {
        seedAccession("123", "S123", analysisWithLoinc("6690-2"));

        assertNull(service.getOrderMenu("XYZ123"));
    }

    @Test
    public void ambiguousBarcodeIsRefusedNotFirstMatchWins() {
        seedAccession("102", "S102", analysisWithLoinc("6690-2"));
        seedAccession("2", "S2", analysisWithLoinc("718-7"));

        // digit suffixes of DEV00000000000000102 hit both 102 and 2
        assertNull(service.getOrderMenu("DEV00000000000000102"));
    }

    @Test
    public void barcodeCandidatesRequireBarcodeShape() {
        assertEquals(List.of(), AnalyzerOrderMenuService.barcodeAccessionCandidates("QC-01"));
        assertEquals(List.of(), AnalyzerOrderMenuService.barcodeAccessionCandidates("1002"));
        assertEquals(List.of(), AnalyzerOrderMenuService.barcodeAccessionCandidates("XYZ123"));
        assertEquals(List.of(), AnalyzerOrderMenuService.barcodeAccessionCandidates("ACC-102"));
        assertEquals(List.of("1260000000000002", "260000000000002", "60000000000002", "2"),
                AnalyzerOrderMenuService.barcodeAccessionCandidates("DEV01260000000000002"));
    }
}
