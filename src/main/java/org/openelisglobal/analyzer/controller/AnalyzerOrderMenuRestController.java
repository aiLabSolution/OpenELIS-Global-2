package org.openelisglobal.analyzer.controller;

import java.util.LinkedHashMap;
import java.util.Map;
import org.openelisglobal.analyzer.service.AnalyzerOrderMenuService;
import org.openelisglobal.common.rest.BaseRestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Analyzer-bridge-facing order menu: what work is pending for one accession.
 *
 * <p>
 * GET /rest/analyzer/order-menu/{identifier} returns the ordered-but-
 * unresulted tests as LOINC ({@code {accessionNumber, patientId,
 * loincCodes[]}}), or 404 for an unknown accession/barcode. The bridge calls
 * this to answer analyzer host queries (ASTM Q-record or HL7 QRY^R02) and owns
 * the LOINC→analyzer-code translation — this is the query-path counterpart of
 * the send-order push path.
 *
 * <p>
 * Like the rest of the analyzer bridge surface (e.g. the registry pull on
 * {@code GET /rest/analyzer/analyzers}) this is not anonymous: the bridge
 * authenticates with Basic auth, handled by the {@code @Order(1)} basic-auth
 * filter chain.
 */
@RestController
@RequestMapping("/rest/analyzer")
public class AnalyzerOrderMenuRestController extends BaseRestController {

    private static final Logger logger = LoggerFactory.getLogger(AnalyzerOrderMenuRestController.class);

    @Autowired
    private AnalyzerOrderMenuService analyzerOrderMenuService;

    @GetMapping("/order-menu/{identifier}")
    public ResponseEntity<Map<String, Object>> getOrderMenu(@PathVariable String identifier) {
        try {
            AnalyzerOrderMenuService.OrderMenu menu = analyzerOrderMenuService.getOrderMenu(identifier);
            if (menu == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(AnalyzerControllerHelper.wrapError("No sample for identifier: " + identifier));
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("accessionNumber", menu.accessionNumber);
            response.put("patientId", menu.patientId);
            response.put("loincCodes", menu.loincCodes);
            logger.info("[ORDER_MENU] accession={} loincCodes={}", menu.accessionNumber, menu.loincCodes);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(AnalyzerControllerHelper.wrapError(e.getMessage()));
        } catch (Exception e) {
            logger.error("Error resolving order menu for identifier {}", identifier, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(AnalyzerControllerHelper.wrapError(e.getMessage()));
        }
    }
}
