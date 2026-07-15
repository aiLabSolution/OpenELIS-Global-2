package org.openelisglobal.autoverification.valueholder;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import org.hibernate.annotations.Type;
import org.openelisglobal.common.valueholder.BaseObject;

/**
 * Per-test delta-check thresholds for the LIS-54 engine
 * ({@code DeltaCheckServiceImpl}): the maximum plausible change between a
 * patient's most recent prior final result and an incoming result for the same
 * test.
 *
 * <p>
 * Either threshold may be null (that leg is simply not checked); a row with
 * BOTH null — like no row at all, or an inactive row — leaves the test
 * unconfigured and the delta check NOT_EVALUABLE. Thresholds are NUMERIC /
 * {@link BigDecimal} so the flag-or-pass decision at the exact boundary is
 * decimal-exact (S5.3 AC1), not subject to binary floating-point rounding.
 */
@Entity
@Table(name = "delta_check_config", uniqueConstraints = @UniqueConstraint(columnNames = { "test_id" }))
public class DeltaCheckConfig extends BaseObject<String> {

    private static final long serialVersionUID = 1L;

    @Id
    @Column(name = "id", length = 36)
    private String id;

    // testId references Test.id (String, bridged to NUMERIC via
    // LIMSStringNumberUserType) — same pattern as the QC tables (PR #3112).
    @NotNull
    @Column(name = "test_id", nullable = false)
    @Type(type = "org.openelisglobal.hibernate.resources.usertype.LIMSStringNumberUserType")
    private String testId;

    /** Maximum plausible absolute change, in the result's own unit. */
    @Column(name = "absolute_change", precision = 15, scale = 5)
    private BigDecimal absoluteChange;

    /** Maximum plausible relative change versus the prior value, in percent. */
    @Column(name = "relative_change_percent", precision = 15, scale = 5)
    private BigDecimal relativeChangePercent;

    @NotNull
    @Column(name = "active", nullable = false)
    private Boolean active = true;

    public DeltaCheckConfig() {
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public void setId(String id) {
        this.id = id;
    }

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    public BigDecimal getAbsoluteChange() {
        return absoluteChange;
    }

    public void setAbsoluteChange(BigDecimal absoluteChange) {
        this.absoluteChange = absoluteChange;
    }

    public BigDecimal getRelativeChangePercent() {
        return relativeChangePercent;
    }

    public void setRelativeChangePercent(BigDecimal relativeChangePercent) {
        this.relativeChangePercent = relativeChangePercent;
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        this.active = active;
    }
}
