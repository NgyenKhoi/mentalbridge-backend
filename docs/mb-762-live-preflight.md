# MB-762 real-provider preflight

Authority: MB-762 comments 10243–10256, 2026-10-29, and the owner's direct
instruction to run Local Real E2E before staging. Comment 10251 supersedes the
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

- Read all fifteen Jira comments, including fixture authorization, account
  reconciliation, the narrow CountTokens-only exception and its endpoint
  correction and one-request Runtime approval (10254–10258).
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
  from the global inference profile. The single authenticated Runtime check
  authorized by PO 10258 returned HTTP 403; no token count was returned.
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

Before a live run: accept both current Care disclosures in the product for the
controlled accounts, finish recording/Egress/export audit, verify the named
local Agent configuration, record the reviewed funding protection and obtain
explicit `LIVE_APPROVED`. Actual named registration/dispatch, counting permission,
speech compatibility/latency and provider cleanup must be verified in that
authorized run; disabled preparation cannot claim those runtime observations.

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

Status is **COUNT_TOKENS_UNAVAILABLE**, not READY_FOR_LOCAL_LIVE.
No inference, real microphone, paid use, Hosted Agent deployment or LIVE_APPROVED
action is claimed. Local preparation does not establish staging/Hosted PASS or
all task acceptance scenarios; MB-762 is not Done.
