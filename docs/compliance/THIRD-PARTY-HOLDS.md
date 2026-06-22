# Third-party reuse holds

Components that are **blocked from reuse** pending a licensing or compliance resolution.
A hold means: do not copy, vendor, embed, statically/dynamically link, or derive from the
component until the hold is cleared and this file is updated.

## HOLD-001 — openelis-analyzer-bridge (undeclared license)

| Field | Value |
|-------|-------|
| Component | openelis-analyzer-bridge |
| Source | https://github.com/DIGI-UW/openelis-analyzer-bridge |
| Status | **BLOCKED — license undeclared ("TBD")** |
| Raised | 2026-06-22 (Stage 0 / S0.1) |
| Tracking | Plane LIS issue (Stage 0) — license confirmation |

**Why blocked.** The repository declares **no license**. Under default copyright, "no
license" means *all rights reserved* — copying or creating derivative works is a copyright
infringement risk, regardless of the code being public. This is the analyzer-bridge that
would otherwise serve as our RS232 serial + file middleware, so the risk is material.

**Allowed while on hold**
- Reading it as a *reference* for documented protocol behavior (ASTM/HL7/MLLP/RS232).
- Clean-room reimplementation in our own code under our own license.

**Not allowed while on hold**
- Copying, vendoring, forking-for-embed, or linking the code into our build.

**Clear path**
1. Request an explicit license declaration from DIGI-UW (open an upstream issue / contact maintainers).
2. Record the outcome (license granted, or confirmed unusable) here and in `MPL-2.0-INVENTORY.md`.
3. If favorable, lift the hold and note the date + approver. If not, proceed clean-room only.
