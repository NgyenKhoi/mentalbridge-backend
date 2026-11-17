# MB-862 — cumulative Voice measurement fence

Source design approved in [MB-862 #10461](https://vunguyenkhoi47.atlassian.net/browse/MB-862?focusedCommentId=10461).
This implements the hard-limit prerequisite for [MB-854](https://vunguyenkhoi47.atlassian.net/browse/MB-854),
following merged PR #294 and MB-861 PR #301. **Source/tests only: no current
live window, deployment, provider probe or merge authority.** The old 12-turn
window remains closed, including its eleven historical turns.

## Design and source boundaries

Journal/AI owns one campaign record in its MongoDB database. New migration 020
only adds the collection, strict validator, unique session reference index and
30-day metadata TTL; it does not backfill, provision or alter historical rows.
The [canonical aggregate](domain-model/document/voice-measurement-campaign.md)
and [field dictionary](database/voice-measurement-campaign.md) define every field.
Application startup does not create/repair it. Missing state denies measurement.
Provisioning is an explicit separately authorized operator command.

The optional existing pilot JSON `measurement` object contains:

| Key                     | Bounds / meaning                                                                                                                |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| `campaignId`            | Immutable approved UUID, never recycled to obtain another attempt                                                               |
| `maxSessions`           | 1..2 lifetime admissions, including failed/unknown attempts                                                                     |
| `maxConcurrentSessions` | Exactly 1; product global capacity remains 2                                                                                    |
| `maxTurns`              | 1..12 total, at least maximum sessions                                                                                          |
| `maxVoiceSeconds`       | 1..480 total, at least maximum sessions                                                                                         |
| `maxTtsCharacters`      | 1..4,000 total, at least maximum sessions                                                                                       |
| `voiceId`               | Existing approved Xia or Liên ID, matched to actual Agent configuration; no voice is selected or changed by this implementation |

Measurement windows are at most 30 minutes and output stays at 300 tokens.
The existing exact 1,900-input-token authorization remains before inference.
No persona, prompt, provider/model/region, schema, full-message safety or fresh
publication authorization is replaced. Ordinary pilot 300..600 output overrides
and normal Text/Voice behavior outside a measurement campaign remain compatible.
MB-871 permits a separately configured plain speech-unit pilot without minting
or resetting this campaign. Its 500 combined-character output and Agent TTS
checks do not replace any measured reservation, worker hash or cumulative cap.

The allocation for each admitted attempt is the floor of each total cap divided
by maximum sessions, further reduced by smaller existing pilot turn/time limits.
For 2/12/480/4,000 it is at most 6/240/2,000. No unused allocation is transferred.
The admitted duration also remains limited by product quota and window end.
Campaign seconds conservatively reserve the whole session wall ceiling,
including connecting/reconnecting; product charging still counts delivered
ACTIVE intervals under the unchanged product policy. Provider usage/billing
rounding is not inferred from these reservations; current credit applicability
and no-overage remain separate gates.

## Transaction and failure rules

1. Provisioning serializes through the existing capacity singleton and requires
   both global slots FREE. It refuses overlapping campaigns, unresolved prior
   reservations, immutable-ID/hash changes or any prior admission in the proposed
   window. Exact provisioning replay cannot reset a record.
2. Admission writes campaign reservation, product quota, session and global slot
   in the same Mongo transaction. Conflicting replicas serialize. Duplicate
   accepted admissions reuse the original session and allocate once. No owner or
   provider REST call occurs inside a database transaction.
3. A measurement worker claim is one-shot and durable. A missing/wrong claim
   hash denies; an unknown response or restart cannot reclaim a fresh budget.
   Agent validates hash, actual configured voice and effective TTS cap before
   `media.start`. Legacy/unbound Agent traffic cannot enter a measured session.
4. Turn reservations are durable before any provider attempt. TTS character
   reservations are durable after complete validation/current publication
   authorization, before reply registration/return and before Agent synthesis.
   Failed/cancelled/unknown delivery never refunds them. Agent's matching local
   budget remains an additional check, rather than the sole durable counter.
5. Only the existing authoritative settlement path may mark the session STOPPED
   and release campaign concurrency in the same transaction as product/global
   settlement. Ending, deadline expiry, missing heartbeat, process loss or a
   failed provider stop alone cannot release it. The existing reconciliation
   loop retries verified cleanup; healthy recovery releases without operator
   counter edits. Unresolved media remains held, then existing capacity retention
   handling quarantines it rather than guessing it stopped. Confirmed provider
   teardown is required before manually resolving an eventual quarantine.
6. Full admission allocation stays spent after concurrency release, even when
   ordinary product quota was fully refunded. Retained sessions/attempts are
   unchanged. Expired windows deny regardless of delayed TTL cleanup.

The hash binds canonical approved window, sorted owner allowlist, aggregate
caps, fixed allocations, reply style, 1,900/300 limits and existing model/speech
pins. Every participating upgraded Journal replica checks the same owner record,
even if its local measurement setting is absent or different. Before activation,
the operator must prove all routing targets/Agent workers use the reviewed
revision and configuration. Code cannot attest to removal of an arbitrary old
binary or an undisclosed database/project; source snapshots are not fleet proof.

## Contract and operations

OpenAPI 1.20.1 adds optional claim `measurementHash` and lease `measurement`
(`configHash`, `ttsCharacters`, `voiceId`). The in-repo Agent is the consumer;
Web/mobile public contracts are unchanged and need no UI adoption. Old claims
and lease shapes remain unchanged outside measurement mode. An old consumer
cannot silently join measurement mode. Both active and executable Voice
reference schemas are updated together.

Use injected production configuration and the reviewed build. Never copy env
dumps or secrets into arguments. These commands describe a future authorized
operation; **none was executed against shared dev/staging during delivery**:

```sh
# Configuration only: no database access, activation, provider or counter claim.
node dist/companion-voice/voice-measurement-operator.js plan
# Only after separate current-window/provisioning authority and migration020:
node dist/companion-voice/voice-measurement-operator.js provision
# Read-only, fresh owner-store evidence; accepts no counter/approval input file.
node dist/companion-voice/voice-measurement-operator.js status
```

All commands require `NODE_ENV=production`, avoiding dotenv. Configure the
Agent's `AI_VOICE_MEASUREMENT_POLICY_HASH` from the safe plan digest and set its
pilot TTS limit to the exact allocation. Neither command enables Voice/Agent,
grants consent/entitlement, resets quota or contacts a provider. Provisioning
writes metadata only and requires its own explicit authority; it cannot reuse
an expired or historical window. It is idempotent, not a reset command.

`plan` always reports BLOCKED/configuration-only. `status` reads the actual
configured owner store and validates immutable policy/caps/reservations; its
`BOUND_OWNER_RECORD` is scoped evidence of source-backed enforcement, not live
readiness. Invalid/missing/mismatched/expired state exits 1 with a fixed safe
code. Successful evidence exits 2 and **always** keeps `liveApproved:false`,
pending fleet revision, current consent/entitlement/quota, credit and Cloud
recording/export/privacy gates. Provisioning before T0 can commit metadata but
still report BLOCKED/not-current; do not interpret nonzero as permission to
change the manifest or obtain a replacement allowance.

MB-861 version-1 snapshots now report effective allocated turns/time. Its
offline coordinator still rejects manually asserted enforcement/counters and
cannot turn this report into LIVE_APPROVED. Use native owner-store evidence
alongside the existing preflight; preserve UNKNOWNs and seek separate approval.

## Verification and limits

Tests use synthetic data and disposable loopback MongoDB replica-set databases.
They cover duplicate/racing admissions and worker claims across independent
clients, configuration removal/change, immutable provisioning, cancelled/spent
work, output-publication denial, actual turn/TTS bounds, settlement/reconciliation
and privacy-safe metadata. Existing context, consent, safety, lease, interruption,
quota and teardown tests remain enabled. No live provider or microphone is used.

`scripts/benchmark-voice-measurement.mjs` compares ordinary pilot and fenced
admission on this same source in 20 fresh isolated fixtures per path, then times
owner preflight. It requires an explicit credential-free loopback test URI and
drops only its generated test databases. This is enforcement overhead, **not a
pre-change/E2E Voice benchmark**, TTFT, browser audio or a new live window. See
the [numeric evidence](evidence/mb-862-measurement-fence.json). Provider and human
listening acceptance remain blocked on a future separately approved window.

Merge order is MB-697 PR #299 first, then synchronize/retest MB-862 and retain
both independent OpenAPI/Mongo documentation/migration additions as required by
PO #10461. Do not deploy migration020 or activate this source ahead of that gate.
Application rollback must keep the campaign writer/Agent gate until attempts are
confirmed stopped; never use an old binary to bypass spent reservations. No
source change in this PR optimizes authorization or starts streaming speech.
