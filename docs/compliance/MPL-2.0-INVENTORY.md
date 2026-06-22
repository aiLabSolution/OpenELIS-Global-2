# MPL-2.0 inventory & obligations

Tracks every Mozilla Public License 2.0 component in (or planned for) this system and the
obligations they carry. MPL-2.0 is **file-level copyleft**: obligations attach to the
individual MPL-licensed files, not to the whole repository.

## Obligations (summary — see `LICENSE.md` for authoritative text)

- **§3.1 Distribute source of MPL files.** When you *distribute* the software (source or
  binaries) to a third party, you must make the **source of the MPL-covered files** (with
  your modifications) available to recipients under MPL-2.0.
- **§3.2 / §3.3 Larger Work.** MPL files may be combined with proprietary/other-licensed
  code in a "Larger Work"; only the MPL files stay MPL. Our net-new code may carry its own
  license, provided MPL files keep their notices and remain available as source.
- **Notices.** Preserve existing MPL headers and `LICENSE.md`; do not strip attribution.

> **Distribution trigger.** Keeping this repository private is fully MPL-compliant — no
> obligation activates until we distribute binaries/source to a third party (e.g. deploying
> to a customer site). At that point we must offer the MPL source to that recipient. Track
> each external deployment as a distribution event.

## Component inventory

| Component | Upstream | License | Status | Notes |
|-----------|----------|---------|--------|-------|
| OpenELIS-Global-2 (core) | DIGI-UW/OpenELIS-Global-2 | **MPL-2.0** | In repo (pinned 3.2.1.10) | The forked core; see `UPSTREAM.md`. |
| openelisglobal-plugins | DIGI-UW/openelisglobal-plugins | **MPL-2.0** | Planned (driver home) | Per-analyzer plugin pattern. |
| Open Integration Engine | OpenIntegrationEngine/engine | **MPL-2.0** | Optional | HL7/ASTM channel engine (Mirth 4.5.2 fork). |
| openelis-analyzer-bridge | DIGI-UW/openelis-analyzer-bridge | **Modified MPL-2.0** (`NOASSERTION`) ⚠️ | Reference-only — clean-room | Custom healthcare warranty/liability clauses + README "TBD" + patchy file headers; **not reused** (clean-room reimplement), so no MPL obligation is inherited. See `docs/compliance/THIRD-PARTY-HOLDS.md` HOLD-001. |
| LabSolution analyzer bridges / site code | aiLabSolution (this org) | TBD (our choice) | Net-new | Keep separable from MPL files to preserve license flexibility. |

Update this table whenever a component is added, upgraded, or its license status changes.
