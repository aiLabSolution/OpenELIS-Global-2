# Upstream provenance & pin

This repository is a **private copy** (export, not a GitHub fork) of OpenELIS Global,
maintained by aiLabSolution / LabSolution. It is pinned to a specific upstream release so
that every later delivery stage validates *deltas against a known, reproducible base*.

| Field | Value |
|-------|-------|
| Upstream project | OpenELIS-Global-2 |
| Upstream repo | https://github.com/DIGI-UW/OpenELIS-Global-2 |
| **Pinned release** | **3.2.1.10** |
| **Pinned commit** | `bc339fa5148c94a1df92cf267ea67668ce351a40` |
| Upstream license | MPL-2.0 (see `LICENSE.md`, `docs/compliance/MPL-2.0-INVENTORY.md`) |
| Copy method | Private export (`isFork: false`) to keep the repo private |
| Export date | 2026-06-22 |
| `develop` base | reset to the pinned commit `bc339fa` (3.2.1.10) |

## Why pinned (not tracking `develop`)

The initial export captured upstream `develop` at `5318e61` — 16 commits *ahead* of the
3.2.1.10 release. For a regulated LIS, the validated base must be an immutable, released
point, so `develop` was re-anchored to the 3.2.1.10 commit. The original export tip is
preserved (nothing lost) as branch **`upstream/develop-snapshot-20260622`** and tag
**`upstream-base/3.2.1.10`** marks the pin.

## Keeping current with upstream

```bash
git remote add upstream https://github.com/DIGI-UW/OpenELIS-Global-2.git
git fetch upstream --tags
# To evaluate moving the pin to a newer release:
git log --oneline 3.2.1.10..<new-tag>
```

Bumping the pin is a deliberate, reviewed decision (it shifts the validation base) — update
this file's release/commit rows and tag the new base `upstream-base/<version>` when you do.
