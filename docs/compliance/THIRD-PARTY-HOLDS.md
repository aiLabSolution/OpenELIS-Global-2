# Third-party reuse holds

Components blocked from **direct reuse** pending a licensing or compliance resolution.
A hold means: do not copy, vendor, embed, statically/dynamically link, or derive from the
component until the hold is cleared and this file is updated. A hold may be *satisfied by
avoidance* — i.e. resolved by deciding not to reuse the component at all.

## HOLD-001 — openelis-analyzer-bridge (modified MPL-2.0, unverified)

| Field | Value |
|-------|-------|
| Component | openelis-analyzer-bridge |
| Source | https://github.com/DIGI-UW/openelis-analyzer-bridge |
| License state | **Modified MPL-2.0 — GitHub `NOASSERTION`** (see below) |
| Raised | 2026-06-22 (Stage 0 / S0.1) |
| **Resolution** | **Clean-room reimplementation — direct reuse declined** |
| Status | **RESOLVED for our roadmap** (clean-room); direct-reuse hold stands |
| Tracking | Plane **LIS-71** (parked fallback) |

**Actual license state (verified 2026-06-22).** The repo *does* ship a `LICENSE.md`, but it
is the MPL-2.0 text with **custom clauses substituted into the warranty/liability sections** —
healthcare-specific disclaimers covering clinical-care standards, privacy-law compliance, and
provider liability. Because the text is modified, GitHub cannot match it to a known license
and reports `NOASSERTION`. Two further inconsistencies: the **README says "License /
Contributing: TBD"**, and only *some* source files carry an MPL header. So it is **not plain
MPL-2.0**, and the governing terms are genuinely ambiguous — it cannot be safely *assumed* to
be MPL-2.0.

**Decision.** LabSolution will **reimplement the required ASTM/serial-bridge behavior
clean-room** under our own license and will **not** copy, vendor, embed, or link the upstream
code. This takes the license ambiguity off our critical path: we acquire no MPL (or
modified-MPL) obligation from a component we do not use.

**Standing rule (still in force).** Do **not** copy / vendor / embed / link
`openelis-analyzer-bridge` into our build. Reading it as a *behavioral reference* for the
documented protocols (ASTM/HL7/MLLP/RS232) is fine and is how the clean-room work proceeds.

**Fallback (only if direct reuse is ever reconsidered).** Obtain written confirmation from
DIGI-UW of (a) the governing license, (b) whether the custom healthcare clauses apply, and
(c) that all files are covered — then re-evaluate. Tracked at LIS-71.
