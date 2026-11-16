# MB-861 — Voice measurement hard-limit preflight

[MB-854](https://vunguyenkhoi47.atlassian.net/browse/MB-854) staging audit on
16 November 2026 found actual hard-limit blockers: Journal input counting was
off and output ceiling was 2,000; Agent pilot reservations were off, so its
configured 1,200-character number was not enforced. The earlier measurement
window is closed. Usage below a ceiling does not prove enforcement.

[MB-861](https://vunguyenkhoi47.atlassian.net/browse/MB-861) delivers a read-only,
fail-closed operational check before further measurement or authorization
latency optimization. [MB-862](https://vunguyenkhoi47.atlassian.net/browse/MB-862)
owns the remaining cumulative campaign/concurrency fence design and review.
Neither task creates a new approval window. This change does not reduce Voice
latency or establish browser-audible E2E acceptance.

## Source boundaries

- Journal's `effectiveBedrockRequestLimits` supplies both the existing request
  path and the snapshot. Normal Text/Voice request bodies, counting conditions,
  prompts, output schema, safety and publication authorization are preserved.
- Journal and Agent CLI commands load their actual configuration and emit only
  allowlisted version-1 metadata. They do not bootstrap services, connect to a
  database, register an Agent, call providers or mutate runtime configuration.
- The root coordinator validates exported snapshots and approval metadata. It
  does not enforce admission itself, attest to the running process/revision or
  authenticate an approval file. Operators must bind exports to the reviewed
  build and existing service environment; never pass an env dump.
- No model/provider/voice change, new environment variable, API, persistence,
  migration, quota, consent, concurrency, recording or teardown change occurs.

## Commands and version-1 file protocol

Use the reviewed build in each existing service environment; do not start a
worker, enable intake or change configuration to obtain a snapshot. Journal
requires `NODE_ENV=production` before loading configuration, avoiding dotenv.

```sh
# From journal-ai-service in its existing production environment:
node dist/companion-voice/voice-measurement-preflight.js > /private/journal.json
# From ai-voice-agent in its existing environment:
node dist/measurement-preflight.js > /private/agent.json
# From repository root, with independently approved metadata (not a new window):
node scripts/voice-measurement-preflight.mjs --journal /private/journal.json --agent /private/agent.json --approval /private/approval.json
```

Do not copy secrets into arguments or approval files. Snapshot CLI exit 0 means
export succeeded, never live readiness. Invalid configuration exits 1 with a
fixed code. The coordinator exits **2 for BLOCKED**, or 1 for invalid arguments,
files or schema. It currently cannot return PASS or exit 0. An operator pipeline
must stop on either nonzero status. This is an opt-in operational tool, not an
automatic server admission hook.

All objects reject unknown keys. All timestamps are real UTC ISO instants.
Snapshots must be no older than 60 seconds, with at most two seconds of future
clock skew. A stale snapshot or expired/not-started approval is blocking even
when a component's per-session configuration says `CONFIGURED`.

| Object            | Exact fields                                                                                                                                                                                                                                                                            |
| ----------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Journal           | `schemaVersion:1`, `component:JOURNAL`, `capturedAt`, `voiceEnabled`, `providerEnabled`, `inputGuardEnabled`, `inputTokenLimit` (1900 or null), `outputTokenLimit`, `pilot` (null or object below), `concurrencyLimit:2` (existing source policy; not database inventory)               |
| Journal pilot     | `startsAt`, `endsAt`, `ownerCount` (1 or 2), `maxSessionSeconds`, `maxTurnsPerSession`; no owner identifiers                                                                                                                                                                            |
| Agent             | `schemaVersion:1`, `component:AGENT`, `capturedAt`, `enabled`, `pilotEnabled`, `ttsReservationEnabled`, `ttsCharactersPerAttempt` (null when reservation disabled), `configuredTtsCharactersPerAttempt`, `finalAggregationMs`, `sttModel`, `ttsModel`, `voiceConfigured` (boolean only) |
| Approval metadata | `schemaVersion:1`, `environment:staging`, `startsAt`, `endsAt`, `caps` (object below)                                                                                                                                                                                                   |
| Approval caps     | `maxSessions`, `maxConcurrentSessions`, `maxTurns`, `maxVoiceSeconds`, `maxTtsCharacters`, `maxInputTokens`, `maxOutputTokens`                                                                                                                                                          |

The approval parser accepts bounded metadata (up to two sessions, 30 minutes,
60 turns, 1,200 voice seconds, 16,000 TTS characters, 1,900 input and 600 output
tokens). These parser bounds do not approve any of those amounts. Supply only
limits from a separately approved, current Jira decision. The closed MB-854
Phase 1 allowance remains 12 turns/480 seconds/4,000 characters/300 output tokens;
this tool does not reset its counters or extend its dates.

Both effective limits and inconsistent flag combinations are checked. A
non-pilot/Friend-only configuration cannot satisfy the 300-output ceiling.
An Agent with pilot off cannot claim TTS reservations from a configured value.
The approved Nova-3/Sonic-3 pins are checked without emitting a voice identifier.

## Remaining blocking gates

Even compatible per-session/per-attempt configuration returns BLOCKED with:

- `AGGREGATE_ENFORCEMENT_UNVERIFIED`: existing per-owner/window admission guards
  and product quota do not prove cumulative campaign turn, voice-time and TTS
  reservations, including failures and unknown outcomes across sessions.
- `CONCURRENCY_ENFORCEMENT_UNVERIFIED`: the existing global two-slot policy is
  not a one-concurrent campaign fence. `CONCURRENCY_LIMIT_EXCEEDED` also reports
  a configured policy above the approved cap.
- `CURRENT_AUTHORITY_NOT_CHECKED`: configuration export does not read current
  consent, entitlement, quota, leases or active-session inventory.
- `CREDIT_AND_CLOUD_PRIVACY_NOT_CHECKED`: no Billing/credit, recording/export,
  retention or provider-level privacy evidence is inferred from code settings.

These codes cannot be replaced by caller-supplied booleans. MB-862 must establish
an owner-reviewed enforcement contract and tests before this tool can accept
aggregate evidence. A new live window and current authority/privacy/credit
checks still require their own approval. No live acceptance or E2E target is
claimed here.

## Verification

Regression fixtures cover audited unbounded configuration, pilot overrides,
Friend-only and Text compatibility, disabled reservations, stale/future data,
window mismatch, malformed or private extra fields, generic errors, no network
or mutation, and CLI exit status. New tests run through the existing Journal
provider suite and Agent test command; no shared CI workflow change is needed.

`node scripts/benchmark-voice-measurement-preflight.mjs` evaluates 10,000
synthetic copies of the audited blocking conditions and verifies every result
is BLOCKED. Timing is local evaluator overhead only, not STT/LLM/TTS latency or
a before/after Voice benchmark. See [numeric evidence](evidence/mb-861-preflight.json).
No provider request, new window, microphone, admission, deployment or merge is
part of these checks. Existing MB-854 real-log latency findings remain unchanged.

Local source verification before delivery:

| Check                                                                                    | Result                                               |
| ---------------------------------------------------------------------------------------- | ---------------------------------------------------- |
| Journal `typecheck`, `lint`, `format:check`, `test`, `contract:check`, `migration:check` | PASS; 377/377 unit tests                             |
| Agent same six gates (including QA build/tests and coordinator tests)                    | PASS; 118/118 tests                                  |
| Repository paired-change policy and documentation policy                                 | PASS                                                 |
| Open-PR file overlap                                                                     | PASS; no shared file                                 |
| Independent changed-source review                                                        | No blocking finding; human approval remains separate |

Mongo integration and required full repository CI run on the PR head; consult
the PR checks for their final status. Local evidence above does not predeclare
CI success or Code Owner approval.
