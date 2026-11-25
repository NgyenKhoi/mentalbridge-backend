# MB-758 guarded voice turns

Story: [MB-758](https://vunguyenkhoi47.atlassian.net/browse/MB-758), MB-754 AI Voice
Companion; owner Journal/AI, Thảo Nguyên Trần. Initial baseline `8c514108` is merged
MB-757; refreshed to `21ab2d7e` before the runtime checkpoint on 2026-10-27.
Status: implemented and locally verified. Exact remote head, PR and CI result are
recorded in the PR/final handoff after publication. No live provider success or
deployment is claimed. UC is the MB-754 Voice conversation; no new UC ID or
frontend `ui-ready` status is invented. Hosted worker and clients are downstream.

## Brainstorm and implementation contract

Reuse the existing Companion route, prompt and normalized assistant validation.
Do not call persisted text `send`, its context assembler or quota repository.
Only final vi-VN STT in the frozen MB-755 internal turn contract is accepted.
Both consents, current entitlement, active attempt/lease, remaining admitted
duration and the approved pinned Claude Bedrock route must be current before
generation and publication. The common 500-character output validator is stricter
than the 1,000-character voice maximum and stays backward compatible.

Keep up to four current-call pairs in process RAM, with a conservative UTF-8 byte
budget of 2,000 for serialized context instead of relying on characters/4 for
Vietnamese. This deliberately admits less history than the 2,000-token ceiling.
No model prompt-cache checkpoint is sent. Only keyed intent/provenance digests,
opaque IDs, sequence and original retention timestamps may be stored to prevent
duplicate provider effects. Missing RAM on retry or another replica is stale,
never permission to reconstruct content or invoke the model again. Live routing
must preserve affinity to the current context process; restart ends that context.

Cancellation, termination, revocation and lease expiry abort/discard provider
work and clear RAM. A stable opaque reply ID registers with MB-757 before TTS;
generation/replay itself never commits the first-spoken-reply session count.
Existing owner policy and text behavior stay unchanged. No Care, Consultation,
SupportPlan, worker, frontend, speech provider or account mutation belongs here.
Existing AWS/LiveKit accounts suffice; account/privacy/model/worker/device gates
remain opt-in downstream, and CI uses deterministic providers only.

## Ordered commit slices

1. Reuse the Companion provider boundary: shared route selector and assistant
   validator, optional cancellation and voice presentation hints. Verify existing
   chat tests plus focused provider request/cancellation/safety tests.
2. Guarded turn vertical slice: frozen REST operation, module lifecycle, closed
   input, cross-process metadata fence, bounded RAM, authorization/publication/
   cancellation and migration 015 with paired model/dictionary. Actual Nest +
   real Mongo integration tests require these to commit together rather than
   depending on a later wiring commit. Verify concurrency, stale replay, HTTP
   response contracts and absence of transcript/Text-quota persistence.
3. Final acceptance and verification evidence. Operational handoff/traceability
   ship with the runtime contract above so intermediate commits remain accurate.
   Refresh dev, review task-only diff/overlap, push/create PR and wait CI green.
   No unrelated runtime change belongs in this evidence slice.

## Acceptance states and executable evidence

The display descriptions are an API handoff for the future worker/client;
this backend PR delivers no voice screen or real-device/audio evidence. All rows
follow [Voice policy v1](policies/companion-voice-policy-v1.md).

| State / trigger | Expected API/client outcome | Must not show or permit | Evidence |
| --- | --- | --- | --- |
| Wrong worker identity, USER bearer, forged owner/room | 401 or closed-input 422, `no-store` | Session/owner selection or user-to-model bypass | Actual Nest guard/HTTP tests |
| Interim, blank, wrong language, known instruction override or over-budget STT | 422 before provider work | Untrusted speech becoming policy/safety authority | HTTP and actual application integration |
| Current ACTIVE lease and owner grants | Short Vietnamese reply with stable opaque reply ID | Text quota consumption, stored text history or independent model route | Five ordered Vietnamese turns; real Mongo has no Text collections |
| Duplicate, reordered or concurrent turn | One durable generation claim; exact latest live reply may replay | Another provider invocation or conflicting sequence | Two separate Nest/Mongo clients race one turn; changed intents conflict |
| RAM lost, old reply evicted or another context process | Stale/conflict without regeneration | Transcript recovery or persisted response replay | Cross-replica/restart test and closed metadata validator |
| Bounded reconnect between turns | Same session/current-call RAM, fresh ACTIVE authority before generation | Fresh quota allocation or extra duration | Reconnect + generation + duplicate playback integration |
| Model/approval route changes or unavailable route | Fail closed, no fallback | Voice using Gemini/OpenAI or an unapproved model | Pinned Bedrock route and invalid-route tests |
| Unsafe/empty/English/oversized output, outage, timeout | Safe 503 or 504, no late spoken reply | Hidden reasoning, clinical/action claims or provider payload in errors | Existing schema/safe validator plus malformed output and timeout cases |
| Pending request cancelled, user stop, consent/entitlement withdrawn, lease/hard stop elapsed | Abort/discard generation, clear RAM, close safely | Late publication, repeated charge or retained prior-call context | HTTP disconnect, both grants, downgrade, stop and elapsed-session tests |
| Reply generated/replayed vs trusted playback | Generation alone consumes no successful session; exact connected playback counts once | Worker-invented publication proof or double settlement | Real media ledger and repeated PLAYBACK_STARTED integration |
| Fresh speech near the idle cutoff | A newly fenced final turn records user activity before generation; reply after the old cutoff remains valid | Replay/heartbeat reset, control-sequence consumption or lease/hard-stop extension | Regression at second 59, response at second 61, exact replay leaves activity unchanged |

Authored/extended tests:

- [Provider reuse and cancellation](../journal-ai-service/src/companion-voice/voice-turn-provider.test.ts):
  actual Companion request construction through a synthetic Bedrock transport,
  common prompt/schema, no tools/cache checkpoint, normalized safety and abort.
- [Actual Nest + real Mongo application](../journal-ai-service/src/companion-voice/voice-turn.mongo.integration.test.ts):
  14 scenarios plus parent, isolated disposable databases, two independent app
  instances, five Vietnamese turns, guarded replay/publication, caller abort,
  reconnect, current authorization, metadata constraints and content-free usage.
- [REST response contracts](../journal-ai-service/src/companion-voice/voice-sessions.http.test.ts):
  additive VoiceReply validation against active OpenAPI, worker credential/body
  separation, safe 422/504 and unchanged legacy Journal authentication.

Migration 015 adds a variant to the existing media-command aggregate, not a new
collection/entity owner. Its detailed canonical model and dictionary change in
the same runtime commit; no unrelated canonical entity-index edit is needed.
Existing 013/014 migrations, text quota policy, Consultation/SupportPlan behavior,
frontend and hosted agent source are unchanged.

## Verification

| Command | Environment | Result |
| --- | --- | --- |
| Provider + existing Companion tests | Journal local | PASS 18 tests at first checkpoint |
| Focused Voice turn/HTTP/access/Companion tests | Actual Nest + disposable Mongo | PASS 49 tests after runtime changes |
| `npm run format:check`, `npm run lint`, `npm run typecheck` | Journal / Node 22 | PASS |
| `npm test` (includes build) | Journal local | PASS 189 tests; zero failures/skips |
| `npm run test:integration` | Disposable actual MongoDB replica sets; no env file/shared DB | PASS 74 tests after idle correction; zero failures/skips |
| `npm run contract:check`, `npm run migration:check` | Journal local | PASS active OpenAPI 1.15.0 / frozen parity / migrations 001–015 |
| `node --test ../contracts/proposals/companion-voice-contract.test.mjs` | Existing frozen proposal/policy fixtures | PASS 55 tests |
| Repository paired-change/documentation policy, local link/diff review | Repository root | Rechecked for 23 task files; 7 changed Markdown files and 79 local links/anchors; whitespace clean |
| GitHub quality-gate and overlap | Exact pushed PR head | Recorded in PR/final delivery only after verification |
| Cloud quota/access, deployed worker, real Bedrock/STT/TTS/devices | Opt-in downstream gates | Not run; no fabricated provider success |

Development-only fixture/type/lint failures were corrected before passing gates:
Nest tests need a real synthetic RSA/SPKI key; typed Mongo IDs/invalid fixture
bodies and strict void/optional-chain lint rules were fixed. npm's initial offline
install was blocked by sandbox access to AppData cache; an approved cached install
completed without dependency/lockfile changes. A resumed base update rebased the
unpublished checkpoint safely, preserving all pending work and without force-push.
No account/cloud mutation, paid inference or real speech is run for this story.

A final review regression demonstrated that valid speech at second 59 did not
advance `lastActivityAt`, allowing an idle cutoff during the reply. The correction
records conversational activity only after a new durable generation claim,
without consuming worker-control sequence, committing delivery or extending
lease/hard stop. The same regression and all 15 Voice-turn integration tests pass
after correction; the 189-unit/74-integration module gates were repeated because
the ledger/application behavior changed. Exact replay does not reset activity.

The [operator handoff](ai-voice-demo-runbook.md#mb-758-turn-checkpoint) keeps live
enablement disabled pending hosted worker, speech, privacy/account allowance and
real-device gates. A green backend PR does not establish those external facts.
