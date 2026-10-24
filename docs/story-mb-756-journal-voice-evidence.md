# MB-756 Journal voice admission evidence

Story: [MB-756](https://vunguyenkhoi47.atlassian.net/browse/MB-756).
Branch: `feat/journal-ai-voice-quota-(MB-756)`.
Status: upstream prerequisites merged; final owner-contract/Mongo integration
verified against `dev` baseline `5206b3a2`. Exact CI head/results are tracked in
[PR #231](https://github.com/NgyenKhoi/mentalbridge-backend/pull/231).

## Delivered boundary

The owner approved three bounded provider-to-consumer PRs: Care voice consent,
Consultation current entitlement, then Journal admission. This document covers
the Journal slice under [ADR 0046](adr/0046-ai-voice-companion-contract.md) and
[Companion Voice policy v1](policies/companion-voice-policy-v1.md).

Only `GET /api/v1/ai-companion/voice-eligibility` is promoted in the
[Journal OpenAPI](../contracts/openapi/journal-ai-service-v1.yaml). It authenticates
the USER, resolves current owner decisions and reads independent quota/capacity;
it does not reserve or invoke a provider. The application-level
[`VoiceAccessService`](../journal-ai-service/src/companion-voice/voice-access.ts)
supplies reusable server-owned reserve/current-authorization behavior over the
[`MongoVoiceLedger`](../journal-ai-service/src/companion-voice/voice-ledger.ts).
Public start/status/connection/turn/end APIs, LiveKit room/token/dispatch,
Node worker execution, STT/TTS and Bedrock voice turns remain downstream work.
Runtime provider readiness is deliberately false; no configuration switch in
this slice establishes provider access or enables voice.

The [owner client](../journal-ai-service/src/companion-voice/voice-authorization.ts)
uses distinct Journal-to-Care and Journal-to-Consultation credentials, exact
owner/session/purpose bindings and two-second reads. Missing credentials,
dependency failures, stale/malformed responses, invalid provenance or expired
entitlement fail closed. It never stores/forwards the user's JWT or reuses the
existing bearer-based owner reads as background authorization.

## Persistence, privacy and compatibility

Migration [013](../journal-ai-service/migrations/013_companion_voice_quota.cjs)
owns closed session, daily-usage and singleton-capacity collections. Their
[detailed descriptions](database/mongodb.md#ai-companion-voice-admission-and-usage-collections)
and [canonical MongoDB model](domain-model/document/mongodb-logical-model.md)
record the independent quota, short authority lease, attempt fencing and
transaction boundary. Current owner reads finish before MongoDB admission.

PLUS retains two successful sessions/day with a 300-second session ceiling and
600 ACTIVE seconds/day; PREMIUM has three, 600 and 1,800 respectively. FREE has
no voice. The Vietnam quota day and hard deadline freeze at admission. Upgrades
raise the daily ceiling without clearing usage or extending a session. First
trusted audio-publication evidence starts delivered-session/ACTIVE accounting;
no delivered reply refunds the reservation. Text quotas, saved Companion
conversations, Consultation financials and credits retain their existing owners
and behavior.

The owner approved 30 days **from each record's creation**, without retention
extension by activity or settlement. Session cleanup transactionally purges its
metadata and scrubs owner/session references from capacity. An uncertain old
room leaves an anonymous `QUARANTINED` slot occupied; no timer or TTL proves
teardown or frees that capacity. Current code does not clear quarantine.
The metadata validators exclude audio, transcript, prompt/reply, saved
Journal/chat context, raw owner responses and bearer/participant credentials.
Ordinary verification uses synthetic content and isolated disposable MongoDB.

## Verification checkpoint

Development checks on baseline `463a15ee`, before upstream merges:

| Command                                                                                                             | Result                                                                                                                           |
| ------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- |
| `npm run format:check`; `npm run lint`; `npm run typecheck`                                                         | PASS, exit 0                                                                                                                     |
| `npm test`                                                                                                          | PASS, 184 tests, no failures or skips                                                                                            |
| `node --test dist/companion-voice/voice-ledger.test.js dist/companion-voice/voice-ledger.mongo.integration.test.js` | PASS, 27 tests, real disposable MongoDB 7.0.24 replica set, including two independent Node processes competing for the last slot |
| `npm run contract:check`; `npm run migration:check`                                                                 | PASS, active eligibility schemas match frozen MB-755; migrations 001–013 validate                                                |

The first whole-module formatting check found the newly edited migration while
its claim schema was being finalized. Formatting was corrected and the complete
check passed. No test was weakened, disabled or skipped.

## Acceptance states

| State or trigger                                       | Observable result                                                                                 | Required exclusion / rule                                               |
| ------------------------------------------------------ | ------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- |
| Missing/forged JWT or non-USER                         | Closed 401/403 Problem, `no-store`, UUID correlation                                              | No owner checks, provider work or reservation                           |
| FREE or expired paid entitlement                       | `FREE_LOCKED`, finite zero FREE allowance                                                         | Text answers and Consultation credits unchanged                         |
| One of two consents withdrawn                          | Consent-specific denial; current authority ends                                                   | No stale allow, transcript or saved context                             |
| Owner absent, slow, malformed or unavailable           | Sanitized 503; live authority ends                                                                | Two-second read deadline, no automatic retry                            |
| PLUS/PREMIUM with all prerequisites                    | Independent finite package allowance                                                              | Provider readiness remains false in this slice                          |
| Pending duplicate start                                | One claimant; retry receives internal `INVALID_SESSION_STATE` until accepted/failed work finishes | No repeated owner reads or reservation; later accepted replay is exempt |
| Owner occupied / global two occupied / exhausted quota | Distinct bounded denial                                                                           | Atomic cross-process reservation; no extra slot                         |
| Failure before first trusted spoken reply              | Full reservation refund after safe teardown proof                                                 | Uncertain issued media retains capacity                                 |
| Active/reconnect/idle/deadline                         | Sum ACTIVE intervals once, exclude reconnect, enforce frozen hard stop                            | Midnight/upgrade never resets usage or extends a live session           |
| Creation plus 30 days                                  | Purge metadata; unresolved media leaves anonymous quarantine                                      | No identity retention or timer-based slot reopening                     |

All rows implement [policy v1](policies/companion-voice-policy-v1.md). They describe
API/application behavior, not a delivered client screen or live audio path.

## Final owner-contract and MongoDB integration

Development checkpoints have exercised the synthetic owner-contract/HTTP/access
tests and real MongoDB ledger/migration scenarios. Their current commands are:

```text
npm run build
node --test dist/companion-voice/voice-ledger.test.js dist/companion-voice/voice-authorization.test.js dist/companion-voice/voice-access.test.js dist/companion-voice/voice-http.test.js
node --test dist/companion-voice/voice-ledger.mongo.integration.test.js
node --test dist/companion-voice/voice-owner.mongo.integration.test.js
npm run contract:check
npm run migration:check
```

After both upstream merges, the new `voice-owner.mongo.integration.test.ts`
executes the real HTTP owner client and application service over real disposable
MongoDB. Synthetic Care/Consultation servers validate requests and normal/error
responses against their merged active OpenAPI contracts. This coupled test
covers missing prerequisite, PAID/DEMO admission and accepted replay, withdrawal
of either consent, live entitlement downgrade/expiry/outage, malformed binding,
cacheable authority, no-reservation compensation and unchanged seeded Text quota.
No user bearer or worker credential is forwarded to the owners. Its 14 tests
pass with no failures/skips and run through `npm run test:integration` in CI.

These are contract-backed synthetic owner servers, not a running Spring/cloud
E2E stack. They establish the tested consumer/application/persistence behavior;
the prerequisite PRs independently exercised their real owner implementations.
Final post-sync module checks and exact PR CI results are recorded in the PR.
Cloud quota, audio delivery and provider teardown are not established here.

Post-sync local module gate (exit 0): `npm run format:check`, `npm run lint`,
`npm run typecheck`, `npm run build`, `npm run contract:check`,
`npm run migration:check`, `npm test` (184 PASS) and `npm run test:integration`
(44 PASS, no failures/skips). Integration uses disposable local MongoDB replica
sets, refuses a local `.env.integration` or inherited external URI in the
verification command, and contains no cloud provider calls. Repository and
paired-change policy also pass for the 27 Journal-only changed paths.

## Ordered integration and remaining gates

1. Care [PR #229](https://github.com/NgyenKhoi/mentalbridge-backend/pull/229)
   merged into `dev` as `8f295d71` after current-head approval and green CI.
2. Consultation [PR #230](https://github.com/NgyenKhoi/mentalbridge-backend/pull/230)
   then merged as `5206b3a2` after refreshed green CI and current-head approval.
3. Journal integrated both using normal merges, preserving published history
   without force-push. Its base-to-head diff contains only Journal MB-756 paths.

The final updated Journal PR CI remains required before handoff. Credential
publication, provider account access/free
allowance, hosted-worker region, LiveKit deployment, speech benchmark, microphone,
STT/TTS, Bedrock voice calls and real-device audio have not been verified here.
MB-757/MB-760/MB-762 and their applicable downstream stories retain those gates;
the [controlled-demo runbook](ai-voice-demo-runbook.md) is guidance, not execution
evidence. Keep provider readiness false until the relevant implementation and
reviewed real evidence permit enablement.
