package org.openelisglobal.unitofmeasure.service;

import java.util.List;
import org.openelisglobal.common.service.BaseObjectService;
import org.openelisglobal.localization.valueholder.Localization;
import org.openelisglobal.unitofmeasure.valueholder.UnitOfMeasure;

public interface UnitOfMeasureService extends BaseObjectService<UnitOfMeasure, String> {

    UnitOfMeasure getUnitOfMeasureById(String uomId);

    UnitOfMeasure getUnitOfMeasureByName(UnitOfMeasure unitOfMeasure);

    List<UnitOfMeasure> getUnitOfMeasuresByType(String uomType);

    void refreshNames();

    Localization getLocalizationForUnitOfMeasure(String id);

    /**
     * Raw unit text → UCUM code for all active units carrying a UCUM code. Pushed
     * to the analyzer bridge as {@code testUnitUcum} so FHIR results get
     * Quantity.system/code while Quantity.unit keeps the raw analyzer text.
     */
    java.util.Map<String, String> getActiveUnitUcumMap();
}
