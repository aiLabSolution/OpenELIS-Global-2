package org.openelisglobal.result.ingest;

import org.openelisglobal.result.service.ResultService;
import org.openelisglobal.result.valueholder.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResultIngestServiceImpl implements ResultIngestService {

    /**
     * Actor recorded on the append-only history / version spine for an edge ingest.
     * The edge is a service, not an OpenELIS named user, so ingested results are
     * attributed to the system user ({@code "1"}). Wiring a configurable
     * service-account is deferred (flagged in the ADR), mirroring S0.5's deferred
     * user-aware version-write path.
     */
    private static final String SYSTEM_INGEST_USER_ID = "1";

    @Autowired
    private ResultService resultService;

    @Override
    @Transactional
    public String ingest(NormalizedObservation observation) {
        Result result = new Result();
        result.setValue(observation.getValue());
        // raw analyzer observation, captured as reported...
        result.setRawCode(observation.getRawCode());
        result.setRawUnit(observation.getRawUnit());
        // ...beside its normalized LOINC/UCUM form and normalization status.
        result.setLoinc(observation.getLoinc());
        result.setUcumValue(observation.getUcumValue());
        result.setStatus(observation.getStatus());
        result.setSysUserId(SYSTEM_INGEST_USER_ID);
        // Persist through the core Result path: the AFTER INSERT trigger appends the
        // first clinlims.result_version snapshot, landing the observation in the
        // append-only store (LIS-7 / S0.5).
        return resultService.insert(result);
    }
}
