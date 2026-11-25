# MB-718 pending documentation reconciliation

These packets preserve the documentation deltas of three open feature PRs while
MB-718 changes their source layout. They are **pending owner adoption**, not
active schema definitions, accepted runtime capability, owner approval or an
agreed merge order. No packet has been applied to the canonical models or to
another author's branch.

The refreshed `dev` baseline is
[`2ac6d3d6`](https://github.com/NgyenKhoi/mentalbridge-backend/commit/2ac6d3d65199ba8c7657fa489e1f44333b575cfd).
It includes MB-676/#218. Its five new traceability lines now live in
[the MB-676 story](../../requirements-traceability/stories/mb-676.md), preserving
[the merged evidence](../../mb-676-reminder-overview.md). The full baseline audit
checks all 176 SQL statements, 163 tables, 2,475 dictionary lines and 532
traceability lines against that exact commit.

## Pinned feature deltas

The [manifest](manifest.json) records the complete source/base SHAs and permitted
patch destinations. Deltas are computed against each feature's merge base,
then combined with the refreshed `dev` source by a three-way text merge. This
retains later `dev` facts without treating an older feature snapshot as current.
`splitHead` records the packet-generation layout anchor; it is provenance
metadata, not an assertion that pending feature work was merged into that head.

| PR and reviewed source | Owner/story destinations | Preserved delta | Packet |
| --- | --- | --- | --- |
| [#222 / MB-675](https://github.com/NgyenKhoi/mentalbridge-backend/pull/222), `3d19e679052efe24790dff624864baba7422e4e8`, owner `phuongthao0908` | Content [dictionary](../../database/postgresql/content-notification-service.md), [SQL](../../domain-model/relational/content-notification-service.sql), existing [MB-684 record](../../requirements-traceability/stories/mb-684.md) | Three tables: `push_device_registration`, `push_delivery_attempt`, `push_device_command_rate_limit`; active-installation index; opt-in description and provider-boundary trace text | [pr-222.patch](pr-222.patch) |
| [#216 / MB-673](https://github.com/NgyenKhoi/mentalbridge-backend/pull/216), `1cf26404ea06718b1447b8d806aee8b29f1a1c94`, owner `vulanhlung` | Identity [dictionary](../../database/postgresql/identity-service.md), [SQL](../../domain-model/relational/identity-service.sql), new `docs/requirements-traceability/stories/mb-673.md` | Three tables: `deletion_request`, `deletion_owner_fact`, `retention_execution_record`; exact MB-673 record and its rebased `../../evidence/mb-673-retention-deletion-governance-evidence.md` link | [pr-216.patch](pr-216.patch) |
| [#220 / MB-745](https://github.com/NgyenKhoi/mentalbridge-backend/pull/220), `036f33558e0585cf159d5243be33a44073d522a1`, owner `Thaonguyen0507` | Community [dictionary](../../database/postgresql/community-service.md), [SQL](../../domain-model/relational/community-service.sql) | Message `state` and `retracted_at`, retraction/erasure constraints, introduction and `purged_at` explanation; no traceability delta was authored | [pr-220.patch](pr-220.patch) |

No feature body, field row or constraint is silently rewritten by these moves.
The MB-673 evidence does not exist on `dev` yet; the verifier checks its exact
path against the pinned feature Git tree. The moved link therefore becomes
valid with that feature's own evidence, rather than a fabricated replacement.

## Reproduce the checks

With the pinned feature objects fetched locally, run from the repository root:

```powershell
node scripts/verify-documentation-preservation.mjs --base 2ac6d3d65199ba8c7657fa489e1f44333b575cfd
node scripts/verify-documentation-reconciliation.mjs
node scripts/documentation-policy.mjs
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/verify-repository.ps1 -BaseSha origin/dev
```

The reconciliation verifier applies each packet only in a disposable directory.
It checks the exact allowed destination paths, compares the resulting SQL
statement multiset to the feature-plus-current-dev monolith, accounts for every
original dictionary/SQL/traceability line and repeated occurrence, validates
owner-local physical references, and resolves moved links/anchors. It never
merges application code, executes SQL, fetches a branch, updates a remote or
changes the current working tree.

| Packet | SQL statements in its simulated merged model | Source nonblank lines preserved: dictionary / SQL / traceability | Moved local links checked |
| --- | ---: | --- | ---: |
| #222 | 180 | 2,524 / 2,575 / 533 | 15 |
| #216 | 179 | 2,516 / 2,572 / 540 | 6 |
| #220 | 176 | 2,479 / 2,531 / 532 | 4 |

These counts describe simulated feature adoption. The active MB-718 models
remain identical to refreshed `dev`: 176 statements and 163 tables.

## Adoption and remaining coordination

The integrator still chooses whether a feature lands before MB-718 or rebases
onto the split first. These packets make either reconciliation reviewable; they
do not claim anyone approved the order. Each feature owner must retain their
runtime/evidence files, remove only their incremental edits to the legacy
routers during integration, and apply the corresponding owner/story packet to
the split layout. Then run the pairing/link policy and that feature's own gates.
If the feature head changes, regenerate and reverify its packet from the new
SHA before using it; do not assume this snapshot covers later edits.

Source review also noted two pre-existing descriptions for the respective
owners to resolve in their feature reviews: #220's `content_ciphertext` row says
null only after retention purge although retraction erases it, and #222's
logical SQL does not include the active-provider-token unique index present in
its migration. These are preserved source facts, not silently repaired as part
of MB-718's layout change.
