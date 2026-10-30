# MB-762 real-provider preflight

Authority: MB-762 comments 10243–10259, 10294–10295, 10297 and the owner's direct
2026-10-30 instruction to finish Local Real E2E preparation. Comment 10251 supersedes the
Hosted Agent/HTTPS prerequisite for this first local pilot. This work does not create
another mock-only delivery milestone. Tests verify production changes without
making provider calls before approval.

## Approved pilot

- Total USD 1.00 metered usage maximum: USD 0.50 existing LiveKit Build Inference
  credit and USD 0.50 existing eligible AWS Bedrock credit. Each is additionally
  bounded by its verified available balance. No paid upgrade or cash billing.
- Two web calls, at most 90 seconds each; 180 seconds combined. Four Claude
  turns/call, eight total; 180 STT seconds, 2,400 TTS characters, 16,000 input
  tokens and 2,400 output tokens combined. No retry after unknown outcome.
- Journal/AI owns `global.anthropic.claude-sonnet-4-6` in `ap-southeast-1`.
  No Opus, Gemini fallback or inference in the Agent worker.
- Cartesia `cartesia/sonic-3-2026-01-12`, language `vi`, Xia
  `b8cd71e3-bc14-4538-a530-d6314731c036`. This supersedes the earlier Lien choice.
- PO volunteered to read harmless scripted speech. Both in-product Care
  consents still apply. Reader permission does not establish two distinct owners.
- Keep live admission disabled until preflight is complete and the owner
  explicitly confirms `LIVE_APPROVED`.

## Current status: LIVE_FAILED_CAMPAIGN_STOPPED — 2026-10-30

The real local pilot under PO **10297** did not produce an audible AI reply.
[Jira post-run report 10299](https://vunguyenkhoi47.atlassian.net/browse/MB-762?focusedCommentId=10299)
records the sanitized evidence. **The campaign is exhausted at 2/2 admissions;
no further live attempt or PREMIUM call is allowed.** Voice and Agent flags are
OFF, Journal was restarted OFF and the local Agent process tree is stopped.
The original window, counters and session rows remain intact. No PR merge,
staging deployment, paid upgrade or fallback occurred.

Execution used backend `8dc44bdb` and Web `d6b2755`, both quality-gate SUCCESS.
The pre-admission compatibility issue below had been corrected before either
admission. Provider/Agent source at execution matched the originally reviewed
runtime; configuration remained Sonnet 4.6 Singapore/global, Nova-3 `vi`, pinned
Sonic 3 `vi` and Xia. This configuration proof is separate from successful
inference or audible voice proof.

| Attempt | Observed local time | Result |
| --- | --- | --- |
| PLUS 1 | Admitted 14:58:03.551; settled 14:58:25.972 | Agent claimed, ACTIVE observed, then EXPIRED / `AUTHORIZATION_EXPIRED`; delivered false, charged 0; media and dispatch STOPPED. |
| PLUS 2, same owner | Admitted 14:58:41.459; settled 14:59:02.308 | One accepted final-turn fence reached Journal; FAILED / `PROVIDER_FAILURE`; delivered false, charged 0; media and dispatch STOPPED. PO reported no audible reply. |
| Later admission request | 14:59:34.022 | HTTP 429 `QUOTA_EXHAUSTED`; no third admission. |

The second admission's initiating action is unknown. The assistant did not
invoke a create-session API, and static review found no automatic new-admission
loop in Web polling, reconnect, failure or BFF transport. The server did lack
the approved **one historical admission per owner/window** constraint: its
global two-admission counter and concurrent owner slot check allowed the same
owner again after settlement/refund. This concrete guard gap is corrected in
the existing reserve transaction, counting failed/refunded sessions as well.
Idempotent replay remains ahead of the guard; the second distinct owner can
use the remaining slot in a future separately approved campaign. No existing
row/counter is changed by the fix.

Correction checks passed without provider calls: Journal lint/format/build,
197 unit tests and 26 ledger integration checks against disposable
`voice_ledger_verify_*` databases on the local replica set. The pilot database
and its two consumed admissions were preserved. The real-Mongo regression
proves daily-quota refund, replay after ledger restart, same-owner denial and
the unused other owner's admission path; it does not reopen this campaign.

Post-run metadata audit confirms:

- Both sessions terminal and settled, media/dispatch STOPPED; zero open
  sessions, LiveKit rooms and active Egress. Agent process absent.
- Product quota refunded correctly before first acknowledged AI audio: PLUS
  2 sessions/600 seconds, PREMIUM unchanged 3/1,800; no reserved quota. Campaign
  admissions remain consumed at 2, both for PLUS.
- Attempt 1 has worker sequence 1 and no later heartbeat HTTP request before
  its lease expires. The exact reason heartbeat did not continue is unresolved;
  do not label this an IAM fault or a proven lease algorithm bug.
- Attempt 2 has one final-turn fence and one FAILED metric; its turn HTTP
  connection closed after about 4,001 ms. The local provider timeout is 30,000
  ms. Failure stage and actual Bedrock usage are unknown; no successful
  GENERATED/token metric was recorded. Do not infer zero inference cost.
- No first published audio was acknowledged for either call. Xia synthesis
  and audible playback are **not PASS**. Exact STT billable seconds and TTS
  characters are not exposed by current content-free telemetry. Combined
  admission-to-settlement wall time is 43.270 seconds, not a provider meter.
- Inspected `ai_voice_*` Mongo collections have operational/fence fields only,
  with no audio/transcript content fields. No newly created audio/caption files
  were found in the private run and Agent root directories. Recording/export
  settings stayed OFF. This is scoped inspection, not proof about arbitrary
  external account integrations.

For conservative budget reporting, retain the approved ceilings rather than
claim exact spend: 180 STT seconds, 2,400 TTS characters and 16,000 input/2,400
output tokens. Existing free-plan protections apply; no extra verification
inference or synthetic CountTokens request was made. Existing CountTokens PASS
10295 is unchanged. The pilot is **not E2E PASS and MB-762 is not Done**.

Before any new real campaign: review the per-owner admission correction and
its real-Mongo regression, diagnose heartbeat/cancellation with sanitized
failure-stage metrics, and obtain explicit PO approval for a new bounded
campaign/window. Never reset or extend this exhausted campaign to retry.

## Historical pre-admission BLOCKED_CONTRACT — 2026-10-30, PO 10297

The live preview was halted before admission: the Web consumer required
`availableSessionSeconds` to equal the package/daily minimum and rejected a
valid server-derived pilot limit of 90 seconds with the full PLUS daily balance
of 600 seconds. `maxSessionSeconds` remains the product cap of 300 seconds for
PLUS and 600 for PREMIUM. Operational limits can reduce availability below that
minimum without changing consent, entitlement, product quota or final admission.

The active/proposal description is clarified together: eligible availability is
positive and at most the package/daily minimum, with additional operational caps
or a remaining window allowed to reduce it. No response field, schema constraint,
authorization or admission behavior changes. The Web consumer correction,
regenerated contract artifacts and their checks must pass before the current
status can return to READY_FOR_LOCAL_LIVE.

At the halt there were **0 campaign admissions, 0 model requests, 0 rooms and
0 active Egress**. Both Voice and Agent switches are OFF, the Agent is stopped
and no microphone or real provider call was started. Existing CountTokens PASS
from Jira 10295 remains valid; do not repeat AWS IAM or synthetic token checks.
Preserve the fixed pilot window and admission history; do not reset either to
gain another campaign. Any subsequent live run still requires explicit
`LIVE_APPROVED` and a valid remaining pilot window.

## Historical READY_FOR_LOCAL_LIVE snapshot — 2026-10-30

This earlier control-plane snapshot is superseded by the current
**LIVE_FAILED_CAMPAIGN_STOPPED** status under PO 10297. Its observations are retained as
historical evidence, not current readiness or live approval. Execution was
still **BLOCKED by `LIVE_APPROVED=false`**: both Voice and Agent switches remained
OFF. Publication also requires `quality-gate` SUCCESS on the final head of
[backend PR 240](https://github.com/NgyenKhoi/mentalbridge-backend/pull/240) and
[Web PR 247](https://github.com/NgyenKhoi/mentalbridge-frontend/pull/247); their
checks and PR descriptions record the exact published SHA and CI result.
This is preparation for real providers; no additional mock milestone is claimed.

Observed 13:20–13:30 Asia/Ho_Chi_Minh:

| Condition | Status and evidence |
| --- | --- |
| Current baseline | PASS: backend integrated dev `189f41de` (current-account JWT enforcement); Web integrated dev `1f8b5ed`. Normal merges, no history rewrite. |
| Local control stack | PASS: Web `39000`, Journal `39002`, Identity `39080`, Care `39081`, Consultation `39082` all HTTP 200. Three Java artifacts were rebuilt from refreshed dev, with distinct local account-status service tokens. |
| Databases | PASS: isolated Docker PostgreSQL `39040`, writable Mongo replica set `mb762` at `39017`, Redis `39079`. Existing volumes retained; no cloud database or financial write. |
| Real Web path | PASS: both DEMO USERs logged in through real Web/Identity and opened the unavailable Voice preview with HTTP 200 eligibility. No API response mocks, admission requests or microphone attempts. |
| Entitlements/consent | PASS: owner REST returns PLUS/PREMIUM source `DEMO`, unexpired; both current Care consents true, Voice version `ai-voice-processing-capstone-v1`. Authenticated disclosure HTTP 200. |
| Daily quota | PASS: PLUS remaining 2 sessions/600 seconds; PREMIUM 3/1,800. No reserved sessions/seconds. Product per-session caps remain 300/600 seconds; disabled eligibility correctly exposes zero available seconds. |
| Pilot availability | PASS: both pilot configurations present, two allowed owners, 0 campaign admissions and 0 open sessions. Fixed window still open; its end is **2026-10-30 15:48:09.404 Asia/Ho_Chi_Minh**. Do not reset it to gain another campaign. |
| Hard limits | PASS review: 2 admissions including failures/refunds, 90 seconds/call, 4 turns/call; full input counted ≤1,900 tokens/turn, output ≤300; TTS reserved ≤1,200 characters/attempt. No retry after unknown provider outcome. |
| Provider route | PASS configuration: Journal-only `global.anthropic.claude-sonnet-4-6`, `ap-southeast-1`; worker `deepgram/nova-3`/`vi`, `cartesia/sonic-3-2026-01-12`/`vi`, Xia `b8cd71e3-bc14-4538-a530-d6314731c036`. AI Text unchanged; Agent has no Bedrock key. |
| CountTokens | PASS, existing Jira **10295**: HTTP 200, 53 input tokens, 440 ms. No IAM or CountTokens synthetic request was repeated in this preparation. |
| Agent → Journal | PASS: exact loopback URL and matching scoped token; authorized malformed claim reaches 422 validation, wrong token gets 401. |
| LiveKit registration | PASS: real official SDK registration under `mentalbridge-ai-voice-v1`, local health HTTP 200. Production Agent stays OFF; the short-lived probe has zero idle processes and rejects every job. |
| Named routing | PASS: an actual explicit dispatch in an empty, non-business preflight room reached the local probe with matching name/room/metadata. Three scheduler offers were all rejected; **zero accepted jobs, room participants or model requests**. No AgentSession or media connection was created. |
| Control-plane teardown | PASS: matching dispatch removed, empty room deleted and confirmed absent, probe worker closed with zero active jobs. Subsequent room/Egress lists both empty. |
| Application privacy | PASS: `record:false`, text/video/transcription publication OFF, silent logging, no configured content exporter, SDK session-report/audio upload disabled by recording options. Owner evidence already establishes project observability OFF; preflight room `activeRecording=false`. This does not assert arbitrary account integrations are impossible. |
| Fixture cleanup | PASS rollback dry-run: only the two DEMO grants and synthetic ADMIN are targeted, with zero financial writes; fixtures remain for the pilot. |
| Free-credit protection | PASS under the existing owner-verified Build Free/AWS Free plan conditions below; no upgrade or paid-only action. Unknown numeric remaining credit is an availability risk, not an observed zero balance. |

Funding uses the reconciliation already supplied in Jira 10254, without asking
for the same screenshots again. [LiveKit Build allowance is a hard cap](https://docs.livekit.io/deploy/admin/quotas-and-limits/):
insufficient free allowance rejects work without overage. At the published
[Nova-3 mono and Sonic 3 rates](https://livekit.com/pricing/inference), the approved
3 STT minutes and 2,400 TTS characters estimate **USD 0.1344**, below the USD 0.50
ceiling. The exact remaining monthly dollar balance is still not exposed.

The owner evidence shows AWS **Free account plan**, USD 131.02 as of October 29,
with stated expiry March 8, 2027. [AWS documents no charges on that plan](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html),
closure on depletion/expiry, and restrictions on paid-only services. Keep it
Free: no Organizations/Control Tower or other automatic-upgrade action. This is
the no-cash protection permitted by Jira 10254; it does not invent an Applicable
Products entry or invoice-level credit offset. If a provider rejects an
unavailable/uncovered service, stop without upgrading, changing route or retrying.
The AWS campaign ceiling remains USD 0.50 from existing credit only.

After explicit local `LIVE_APPROVED`, start the reviewed production worker and
enable Journal admission using only the private env files, preserving owners,
window and every pilot limit. Verify worker registration, then the PO can grant
microphone access for harmless Vietnamese phrases in the Web UI. Abort at any
provider/budget/privacy/authority uncertainty. At stop: disable admission, stop
audio and pending work, verify provider/room teardown, then stop the worker.

Only live execution can verify actual speech/voice quality, full Sonnet response,
media interruption/reconnect and active-audio teardown. Those acceptance rows
remain NOT_RUN under the current approval gate; local preparation does not
establish staging/Hosted or full MB-762 completion. If approval arrives after
the fixed window expires, stop for an explicit schedule decision; do not silently
reset the campaign. No new service registration or staging restart is needed.

## Ordered implementation slices

1. Make Voice routing independently pin the approved Sonnet profile/region
   without modifying AI Text. Include typed configuration, environment example,
   provider boundary tests and this decision record. Build, typecheck and run
   the affected Voice/configuration tests before the checkpoint commit.
2. Implement the pilot's runtime ceilings and inspect privacy/export paths.
   Keep product entitlements/quotas unchanged; pilot controls can only reduce
   admission and provider work. Verify any durable counters against disposable
   MongoDB and update owner migrations/model docs together if required.
3. Verify real account credits, eligible Bedrock billing, monthly LiveKit usage
   and owner-secret wiring. Start Web, Identity, Care, Consultation and Journal
   with isolated local databases; prepare the local named Agent using the
   verified Journal localhost port. Both Voice flags remain OFF before final
   LIVE_APPROVED. Hosted Agent and staging are a separate later validation;
   no EC2 startup, public HTTPS or cloud deployment is required for local proof.

## Actual preflight observations

- Read the current Jira decisions, including fixture authorization, account
  reconciliation, endpoint correction and post-policy CountTokens PASS
  (10254–10259, 10294–10295).
  AWS Free-plan balance USD 131.02 and expiry 2027-03-08 are owner-supplied
  evidence in comment 10248. Specific Bedrock Applicable Products/credit offset
  remains a separate check, not inferred from that balance or a runtime key.
- Existing LiveKit project credentials passed authenticated read-only ListRooms.
  Official LiveKit CLI 2.19.2, SHA-256 checked against the official release,
  can read Hosted Agents; none exist yet. No room or dispatch was created.
- Browser inventory is empty and in-app browser creation returns
  `Browser is not available: iab`. No dashboard credit/privacy reading is claimed.
- Local Journal configuration still selects fake/Gemini Text. The new scoped
  Voice route uses the existing bearer-authenticated Bedrock Converse adapter,
  with its own pinned region and approval. Configuring the route cannot enable
  admission, and the benchmark model variable is never used to select it.
- No AWS IAM/Billing credential or Agent secret bundle was found in the inspected
  environment. No secret is copied to evidence. Local service tokens are
  injected outside the repository. The existing staging deployment is powered
  off; it is not a local-pilot prerequisite and will not be started.

## Implemented pilot controls

- Scoped Voice Sonnet routing leaves AI Text unchanged. Pilot JSON in an external
  env fixes one/two synthetic owners and one positive window of at most 24 hours.
  Never reset the window to retry. Existing 30-day session records count all
  admissions in that window; the shared capacity write serializes the Mongo
  transaction. Two admissions maximum includes failed/refunded calls. Replays
  allocate nothing. No new persisted field, collection or migration is needed.
- Admission freezes a deadline of at most 90 seconds and never beyond the window
  end. Existing one-owner/two-global capacity, current authority checks,
  first-reply charging, ACTIVE-only accounting and teardown remain in force.
- Four Claude turns/admission, 300 output tokens/turn. The full prompt/schema is
  counted before Converse; over 1,900 input tokens, counting failure or unknown
  outcome denies inference without retry. The corrected request uses Bedrock
  Runtime Singapore with base model `anthropic.claude-sonnet-4-6`, independently
  from the global inference profile. The initial check under PO 10258 returned
  HTTP 403; the separately approved post-policy check in 10295 returned HTTP 200
  and 53 input tokens. Neither was repeated during this preparation.
  Actual inference remains Singapore/global Sonnet.
  See [AWS token counting](https://docs.aws.amazon.com/bedrock/latest/userguide/count-tokens.html)
  and the [Sonnet 4.6 model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-4-6.html).
- Agent pilot pins Nova-3/vi and Sonic 3/vi/Xia. It reserves at most 1,200 TTS
  characters before synthesis per attempt, including interrupted/failed work.
  Two admissions bound campaign TTS to 2,400. Unknown outcomes cannot be retried
  or re-dispatched to regain a fresh budget. Both live flags remain OFF.

## Local observations on 2026-10-29

All five real service health checks returned HTTP 200: Web `127.0.0.1:39000`,
Journal `39002`, Identity `39080`, Care `39081`, Consultation `39082`.
Isolated Docker PostgreSQL `39040`, MongoDB replica set `39017` and Redis `39079`
use no cloud database. Owner migrations completed; credentials and tokens stay
in private env files outside the repository.

The script authorized in Jira 10255 verified local Docker named pipe, task label,
volume, loopback port and BOTH effective database identities before writing.
It created one synthetic ADMIN bootstrap and two DEMO PLUS/PREMIUM grants in
`mb762_identity` / `mb762_consultation`, with two authentic Identity USER logins.
No purchase/payment/credit/financial ledger writes occurred. Owner REST
checks return the correct plans; initially missing Care consents denied admission with
`CONSENT_REQUIRED`, and invalid scoped service credentials are rejected.
Care's disclosure is reachable. These are real local service checks, not proof
of live microphone/STT/TTS/Claude execution or paid entitlement verification.

Subsequently, both synthetic USERs created a minimal Care profile through the
authenticated public owner API (display name only, no DOB/health data). The
actual Web UI displayed each current disclosure and recorded independent
AI_PROCESSING (201) and AI_VOICE_PROCESSING (200) decisions for PLUS/PREMIUM.
Both current owner grants are now true. The dialog then showed unavailable
because admission is OFF; zero session-creation or microphone requests occurred.
This is controlled fixture consent, not a claim that human audio was tested.
Journal was restarted from the current build with the validated private pilot
window and flags OFF. The frontend's observed login fallback leak was fixed
with native POST and a no-JavaScript browser regression; temporary fixture
passwords were rotated and old refresh sessions revoked.

Cleanup was verified in transactions ending with ROLLBACK: removing only the two
DEMO grants, revoking only the bootstrap ADMIN's refresh sessions and disabling
only that synthetic ADMIN would leave zero active fixture grants/admins.
The fixtures remain intact for E2E. After the pilot: disable admission, prove all
media stopped, then use the reviewed workspace cleanup helper with `--apply`.
Keep audit facts; do not drop databases, remove shared volumes or reset a budget
window. Normal synthetic USER accounts and all financial tables stay untouched.

Docker's first image build was interrupted by engine EOF; Docker was restarted
without resetting its data disk and the MB-762 databases recovered. Subsequent
worker image build and offline CPU VAD package check passed. No staging process
was started. All five services were rechecked healthy after recovery.

Real private-env validation confirms both live flags OFF, two pilot owners,
24-hour window, the exact Sonnet/Nova-3/Sonic-3/Xia route, and no Bedrock key in
the Agent. Journal accepted the correct scoped worker token up to request
validation (422) and rejected the wrong token (401). ListRooms and active
ListEgress returned zero records; these lists do not establish account-wide
integrations are disabled.

## Account evidence and remaining gates

Comment 10254 explicitly records LiveKit numeric remaining as unknown, not zero,
and AWS Applicable Products/credit offset as unverified. Do not request the same
screenshots again or mark either numeric/billing fact PASS. The owner-verified
plans provide a separate possible no-cash bound:

- [LiveKit Build allowances are hard caps](https://docs.livekit.io/deploy/admin/quotas-and-limits/):
  exhausted requests fail rather than incur overage. This does not prove remaining
  capacity or that a complete pilot can finish.
- [AWS Free account plan](https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html)
  does not incur charges and closes on credit exhaustion/expiry. This guarantee
  requires the account to remain Free; no Organizations/Control Tower or other
  action that automatically upgrades it is allowed. It does not establish a
  specific Bedrock Applicable Products entry. Owner credit evidence expires
  2027-03-08; service availability may still reject a request.

Both current Care disclosures, application recording/export, named local routing,
the funding guard and CountTokens are now verified as recorded above. Obtain
explicit `LIVE_APPROVED` before starting production Agent/admission. Speech
compatibility/latency and active-media cleanup remain acceptance work for that
authorized run; control-plane preparation does not establish those observations.

## Authorized CountTokens-only result — 2026-10-29

PO comment 10256 explicitly permitted one small synthetic CountTokens-only
request before live approval, without any model inference, STT/TTS or microphone.
The actual result was:

| Field | Observed result |
| --- | --- |
| Endpoint | AWS Mantle Tokyo `ap-northeast-1`, `/anthropic/v1/messages/count_tokens` |
| Model ID | `anthropic.claude-sonnet-4-6` |
| HTTP | **403** |
| Provider error type | `permission_error` |
| `input_tokens` | Not returned; do not invent a count |
| Result | **COUNT_TOKENS_UNAVAILABLE**; this 403 alone does not establish the IAM cause |
| Retries | Zero; stopped on authorization rejection as instructed |
| Inference / STT / TTS / microphone | Zero requests / not used |
| Voice admission / Agent enabled | Both OFF |

A separate read-only model-metadata GET returned 200; that does not establish
CountTokens permission or exact Sonnet support. Neither check proves billing
coverage or actual inference residency. The existing Singapore/global Sonnet
inference route remains unchanged, and the runtime budget guard fails closed.

### Correction and proposed Runtime-only preflight — PO 10257

The [specific AWS Sonnet 4.6 model card](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-4-6.html)
lists Runtime CountTokens as supported and Mantle as unsupported. The earlier
generic-guide assumption was wrong; the 403 does not prove that adding Mantle
permissions would help. The prior instruction to change Mantle IAM is withdrawn.
Do not retry that endpoint, rotate keys or broaden IAM from that result.

The [Runtime CountTokens API](https://docs.aws.amazon.com/bedrock/latest/APIReference/API_runtime_CountTokens.html)
accepts a foundation model identifier and `input.converse` system/messages and
returns `inputTokens`. It is separate from the global-profile Converse inference
route. The [CountTokens guide](https://docs.aws.amazon.com/bedrock/latest/userguide/count-tokens.html)
identifies `bedrock:CountTokens` as the needed action and documents no charge.
[API-key authorization](https://docs.aws.amazon.com/bedrock/latest/userguide/api-keys.html)
uses `Authorization: Bearer` and the existing `bedrock:CallWithBearerToken`
permission. No IAM/key change is requested or attempted. Documentation establishes
the model feature and request form, not actual regional/account access.

Scope subsequently approved by PO 10258 and executed exactly once:

- One POST to `https://bedrock-runtime.ap-southeast-1.amazonaws.com/model/anthropic.claude-sonnet-4-6/count-tokens`.
- `input.converse` contains a synthetic system instruction and one synthetic
  Vietnamese user message; complete JSON payload is 188 UTF-8 bytes. No user,
  Journal, chat, audio or microphone data is included.
- Existing Journal-only key outside the repository; 10-second timeout, redirect
  rejection and zero retries. No profile/model/region switching or inference
  substitute if the check fails. Neither Voice flag is enabled.
- Report only HTTP status, public model ID, `inputTokens` when valid, elapsed
  milliseconds and sanitized error type/action. No headers, key, account ID,
  raw error message or prompt is logged.
- A failure is `COUNT_TOKENS_UNAVAILABLE`. Existing bounded context, 300 output
  tokens, four turns and 90-second/two-admission limits remain in force. An
  unverified input budget denies inference; a byte/character estimate never
  substitutes for actual token authorization in this candidate. Keep admission
  OFF while deciding any alternative conservative-budget policy.

Local dry-run verified the request shape and both OFF flags without network.
Approval of this CountTokens-only check is separate from `LIVE_APPROVED`.

### Authorized Runtime CountTokens-only result — PO 10258

| Field | Observed result |
| --- | --- |
| HTTP status | **403** |
| Model | `anthropic.claude-sonnet-4-6` |
| `inputTokens` | Not returned |
| Client-observed elapsed time | **370 ms**, measured for this single HTTP operation |
| Result | **COUNT_TOKENS_UNAVAILABLE** |

The request used exactly the approved 188-byte payload, Runtime Singapore and
existing Bearer authentication. There were no retries, redirects, model/region
changes, IAM/key changes or inference substitutes. A local exclusive attempt
lock prevents accidentally rerunning this one-request authorization. The report
contains no usable error type or denied action; the root cause of this 403 is
not established. Do not infer from this result that the base model/region is
supported for this account or that any particular IAM grant will fix it.

Stop here for review of the unavailable capability. No additional AWS request
is authorized by PO 10258. The fail-closed input-budget guard continues to deny
inference; Voice and Agent remain OFF and `LIVE_APPROVED` remains false.

This historical `COUNT_TOKENS_UNAVAILABLE` result is superseded by Jira **10295**:
the separately approved post-policy request returned HTTP 200, 53 input tokens,
440 ms. See the current `BLOCKED_CONTRACT` report above. No inference, real
microphone, paid use, Hosted deployment or `LIVE_APPROVED` action is claimed;
MB-762 remains In Progress until its real-provider acceptance work passes.
