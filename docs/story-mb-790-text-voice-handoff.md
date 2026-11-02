# MB-790 selected Text-to-Voice continuation

[MB-790](https://vunguyenkhoi47.atlassian.net/browse/MB-790) follows MB-789 shared
guidance. Product Owner chose a new Voice disclosure v2 with reacceptance;
AI_PROCESSING for Text remains unchanged. The accepted prospective context
amendment is [MB-TEXT-VOICE-HANDOFF-001](adr/0048-opt-in-text-voice-context.md).

## Implementation slices and authority

1. Care publishes v2 with immutable v1 history/replay; contract and focused
   controller/service/real PostgreSQL tests change together.
2. Journal/AI adds the strict preparation/START contracts, owner/snapshot/expiry
   checks, bounded RAM seed, migration 016 and unique consumed-hash constraint.
   Canonical model, dictionary and regression tests accompany that migration.
3. Web consumes these contracts separately with unchecked per-call opt-in,
   explicit voice-only fallback and preserved unknown-admission intent.

| State | User-facing outcome | Must not imply | Rule |
| --- | --- | --- | --- |
| Feature off/no selected chat | Voice-only call | Text history was read | Default-off capability |
| Missing current Voice consent | Read and accept Care v2 | Old v1 or AI Text consent covers Voice | Dual consent |
| Explicit selected chat | At most two eligible recent exchanges | Entire history or private derived context | Owner-authorized bounded seed |
| Deleted/changed/expired/foreign chat | Safe rejection; explicit voice-only choice | Context attached or automatic retry | Snapshot and owner check |
| Unknown admission | Reconfirm original key/ticket | New call is safe | Durable intent and consumption |
| Session confirms attachment | Context prepared for this call | Provider used it or already replied | `contextMode=TEXT_HANDOFF` |
| End/revoke/timeout | RAM cleared, usual quota settlement | Replay can recreate a new call | Existing Voice authority |

The feature performs no provider warmup or standalone inference. Content is
absent from tickets, Voice MongoDB documents, logs and errors. The Text record
remains in its original encrypted owner collection under its existing lifecycle.
No new provider, queue, cross-service database read or content store is added.

## Operations and verification

See [ADR 0048](adr/0048-opt-in-text-voice-context.md) for process affinity,
rollout and rollback. Preparation is bounded to four live tickets per owner and
32 per process, each 120 seconds. Rejection uses closed existing Voice problem
codes, with `Cache-Control: no-store`. Consent/entitlement are rechecked at
admission and turns. Disabled configuration does not open Voice admission.

Authored coverage includes ticket tamper/owner/snapshot/TTL/provenance bounds,
single-session hash concurrency and post-settlement replay, optional-context
turn integration, immediate retained-RAM trimming after every reply, long-input
seed trimming, voice-only isolation and Care v1/v2 idempotency. Web tests
cover strict BFF payloads and unknown-outcome command preservation. Exact
executed commands/results are recorded in the PR verification table.

No live provider, microphone, staging, campaign reset or new admission is part
of these checks. MB-789 merges before MB-790 backend; the Web consumer follows
the merged owner contracts and must regenerate snapshots before its merge.
