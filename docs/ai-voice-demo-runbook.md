# AI voice controlled-demo runbook

Status: **accepted target with the disabled MB-757 control plane implemented**.
MB-755 itself delivered no runtime. This runbook documents implementation and
opt-in demo gates. It does not enable a
feature, create an agent, modify a provider account, or approve a paid call.
Public documentation was checked on 2026-10-23; account-specific evidence below
has **not** been collected. The disabled MB-759 Node.js worker is now implemented;
its checkpoint below does not establish live-provider readiness.

Authority: [ADR 0046](adr/0046-ai-voice-companion-contract.md),
[Companion Voice policy v1](policies/companion-voice-policy-v1.md), and the
[planned voice REST contract](../contracts/proposals/companion-voice-v1.openapi.json).

## MB-757 control plane checkpoint

The [active Journal contract](../contracts/openapi/journal-ai-service-v1.yaml)
now implements start/status/connection/end and internal worker claim/control.
MB-758 adds the internal inference-turn endpoint described below; the hosted
Node worker is delivered by the MB-759 checkpoint below.
[MB-757 evidence](story-mb-757-livekit-sessions.md) covers local synthetic SDK
transport, actual Nest HTTP and disposable Mongo replica-set checks. No Cloud
deployment, quota check, live room, speech benchmark or paid call was run.

Keep `JOURNAL_AI_VOICE_ENABLED=false` until downstream tests and the account,
privacy, speech and E2E gates below are reviewed. No new registration or upgrade
is needed to implement this control plane. Follow the existing-project checks
below before enabling; a zero allowance or unresolved access check blocks enablement.

For a later authorized synthetic Cloud test, use this order:

1. Apply owner migration 014 after 013, with replica-set transactions available.
   Inject the existing project's dedicated API credentials and a distinct
   worker REST secret through operator-managed secrets, following the
   [Journal configuration](../journal-ai-service/README.md#current-scope).
2. Deploy the reviewed MB-759 worker with the exact explicit agent name
   `mentalbridge-ai-voice-v1` (or matching reviewed configuration), identity
   `va-<attemptId>`, `record: false`, and no automatic room dispatch registration.
   Check that no unnamed automatic agent can join AI rooms in this shared project.
   The worker receives only three opaque dispatch UUIDs, claims through Journal
   and stops/clears RAM on STOP, failed renewal or expiry of its ten-second lease.
   USER/appointment tokens and Bedrock credentials never go to the worker.
3. After the remaining privacy, actual account allowance, Vietnamese speech and
   client/E2E checks pass, record their reviewed evidence version and set
   `JOURNAL_AI_VOICE_APPROVAL_VERSION` with explicit voice enablement. Configuration
   validates prerequisites; it cannot establish that those external checks passed.
4. With synthetic accounts and both current Care consents, exercise all acceptance
   rows in the MB-757 evidence and later device scenarios below. Observe only
   sanitized outcome codes, state and counts; do not export token bodies,
   private room references or content. Verify maximum two global/one owner,
   grant scopes, reconnect, revocation, hard stop and provider teardown.
5. Disable new admission for rollback, retain backend media credentials, end
   current sessions and let reconciliation prove cleanup. Unknown outcomes stay
   ENDING with capacity held; deadlines never justify manually freeing a slot.
   Only after every media record is STOPPED may 014 be rolled back. Expired
   unidentified media keeps the existing anonymous quarantine; do not clear it
   without independent provider-verified isolation.

## MB-759 worker checkpoint

[Worker README](../ai-voice-agent/README.md) documents the separate Node 22 package,
locked SDK, server-only environment, Docker build/start/stop and dependency gates.
[Acceptance evidence](story-mb-759-voice-agent.md) maps synthetic HTTP/media tests
to final Vietnamese STT, approved-text-only TTS, first-frame publication,
interruption, mute, expiry and reconnect behavior. Recording/content logs,
transcription output, SDK LLM/history and direct reasoning-provider credentials
are disabled. No account registration or new VPS is needed for implementation.

Keep `AI_VOICE_AGENT_ENABLED=false` and Journal's Voice flag off until the
account/privacy/benchmark/QA gates below are approved. Choose the reviewed
default voice UUID in MB-760 and inject credentials only at runtime. CI tests
and Docker builds contain no Cloud credentials and never call a speech provider.
Use explicit named dispatch only; worker startup never auto-joins consultation
rooms. Journal remains the shared two-call authority across worker processes.
Neither local SDK load limits nor a worker's FINISHED signal prove capacity is
free: Journal must verify provider room/agent teardown before reuse.

Local tests do not establish Vietnamese pronunciation/noise quality or a real
spoken reply. The opt-in QA story records exact SHA, approved provider/model/voice,
device/microphone/headphone modes, echo cancellation/noise suppression and
interrupt/stop behavior without recording or attaching conversation content.
If speech is uncertain or unavailable, end safely and offer explicit text/manual
push-to-talk in the client; MB-759 does not implement client controls.

## MB-758 turn checkpoint

The [active Journal contract](../contracts/openapi/journal-ai-service-v1.yaml)
v1.15.0 implements the frozen internal turn operation. The
[turn evidence](story-mb-758-guarded-voice-turns.md) records synthetic provider,
real Nest HTTP and disposable Mongo checks, not live Bedrock or speech success.

Apply migration 015 after 014 before starting turn handlers. Keep live Voice
disabled while MB-760/762, hosted deployment and account/privacy gates remain pending.
The current demo must use one Journal context process or route a session's turn
requests consistently to its original process. Another replica or a restarted
process must return stale without reconstructing content or regenerating a
claimed turn. No Redis, database or provider cache may restore discarded dialogue.

The worker must claim and maintain its five-second renewal/ten-second lease
while the turn REST request is in flight. Send final vi-VN text only, increasing
turn sequence, opaque turn ID and one retry key per intent. Keep returned text
in RAM; start only authorized TTS and report its exact turn/reply IDs through
PLAYBACK_STARTED. A generated/replayed reply is not proof that the user heard it.
On a stale/expired/denied turn, STOP or failed lease renewal, stop processing and
purge context. Disconnecting/cancelling a pending generation ends that session;
normal TTS interruption uses INTERRUPTED and cannot regenerate or double-charge
the reply. Reconnect between turns retains bounded RAM only inside the current
session's grace/deadline and requires fresh ACTIVE authority before generation.

There is no new account/key registration for MB-758. Reuse the approved Companion
Claude Bedrock configuration and existing owner/worker credentials. The request
contains no tools or prompt-cache checkpoint; explicit cache behavior is documented
by [AWS](https://docs.aws.amazon.com/bedrock/latest/userguide/prompt-caching.html).
This payload check does not replace actual account invocation-logging/privacy
review. Provider processing locations and metadata handling remain disclosed
separately from the application's RAM-only policy.

Rollback disables new admission first, ends active sessions and stops turn
handlers. Keep replay fences until expiry or a separately reviewed cleanup after
confirmed provider teardown; migration 015 down refuses surviving turn fences
before restoring the old command validator. Do not delete fences to make a
retry invoke the model again.

## Approved path and existing resources

Use the existing `mentalbridge` LiveKit Cloud Free project already used by
[MB-384 consultation video](runbooks/mb-384-livekit-video.md). Voice uses a
separate room/dispatch namespace and purpose-scoped credentials; appointment
rooms, grants, credits, webhooks and completion evidence remain independent.
The future worker runs on **LiveKit Cloud hosted Agents**, with no VPS or EC2
worker deployment.

The target processing path is:

```text
Authenticated client microphone
  -> LiveKit room -> hosted Node.js Agent
  -> LiveKit Inference STT -> current-call text in RAM
  -> custom LLM node -> authenticated Journal/AI REST voice turn
  -> Journal/AI approved Bedrock route -> normalized reply
  -> LiveKit Inference TTS -> room audio
```

Journal/AI owns current consent/entitlement checks, usage limits, routing,
safety rules and turn authorization. Both Care grants are required:
`AI_PROCESSING` and separate `AI_VOICE_PROCESSING` at
`ai-voice-processing-capstone-v1`. A text grant or microphone permission alone
cannot authorize voice. The agent and clients have no AWS
credentials and never invoke Bedrock or a LiveKit Inference LLM directly.
The existing [Journal/AI provider approval process](../journal-ai-service/README.md#bedrock-enablement)
still applies. Do not infer `PLUS = Sonnet` or `PREMIUM = Opus` from model
availability; reuse the current approved owner route.

This is a fresh ephemeral conversation. Its context is only the current call's
bounded in-memory turns. It does not read Journal, screening, SupportPlan,
prior conversations or account history, and does not create a persisted chat,
Journal entry, audio file or transcript. Content-free control/quota facts are
separate from conversation content and have the policy's 30-day metadata
retention window. Enforce the policy's four recent user/assistant pairs and
2,000-token context ceiling, 2,000-character recognized turn and 1,000-character
spoken response bounds before provider work.

## Read-only account checks before implementation enablement

These are **manual instructions, not commands run for MB-755**. An authorized
operator records only check time, sanitized result, remaining allowance and
reviewed configuration version. Keep credentials, account IDs, private project
URLs and raw command output outside the repository and chat. An access denial,
missing field or empty quota result means **unverified**, not usable capacity.

| Check               | Read-only operator path                                                                                                                        | Passing evidence needed later                                                                                                         |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- |
| Existing project    | Sign in at [LiveKit Cloud](https://cloud.livekit.io/), select `mentalbridge`; inspect its plan and Usage/Billing views                         | Actual Free/Build plan, remaining agent minutes, Inference credit, media allowance and active sessions recorded without project IDs   |
| Agent capacity      | Inspect the project's deployment and Inference quota views against [published limits](https://docs.livekit.io/deploy/admin/quotas-and-limits/) | Enough hosted-agent and STT/TTS capacity for the application cap and existing project traffic                                         |
| Regions             | Inspect project data/privacy settings and the available hosted-agent region selector without creating a deployment                             | Actual selected compute region, media routing, Inference restriction setting and outbound Journal/AI reachability reviewed separately |
| Retention           | Inspect project Agent observability, recordings, exports and log-drain settings                                                                | Agent observability off; no audio/transcript export, room recording, egress or content-bearing log drain                              |
| AWS access          | AWS console, Amazon Bedrock, `ap-southeast-1`: inspect the approved model/profile and Service Quotas                                           | Actual model authorization, agreement, regional availability, usable request/token quota and owner-approved cost ceiling              |
| AWS content logging | Bedrock Settings -> Model invocation logging in the invocation region                                                                          | No prompt/response content delivery to CloudWatch/S3; no conflicting shared-account logging requirement                               |

LiveKit's [Build plan](https://livekit.com/pricing) is listed at $0/month with no
card, 1,000 hosted-agent session minutes/month and $2.50 Inference credit. The
[quota guide](https://docs.livekit.io/deploy/admin/quotas-and-limits/) lists five
concurrent hosted-agent sessions and hard stops when free allowances run out.
These are published allowances, not a measurement of this project's balance.
Do not upgrade the plan, add a payment method or assume all minutes include
speech inference. Existing project activity shares the applicable allowances.

AWS read-only examples, using an already authorized local profile and current
AWS CLI v2. Replace the profile placeholder locally; none invokes a model:

```powershell
aws --version
aws bedrock get-foundation-model-availability --region ap-southeast-1 --profile '<operator-profile>' --model-id anthropic.claude-sonnet-4-6 --query '{authorization:authorizationStatus,agreement:agreementAvailability.status,entitlement:entitlementAvailability,region:regionAvailability}' --output json --no-cli-pager
aws bedrock get-foundation-model-availability --region ap-southeast-1 --profile '<operator-profile>' --model-id anthropic.claude-opus-4-6-v1 --query '{authorization:authorizationStatus,agreement:agreementAvailability.status,entitlement:entitlementAvailability,region:regionAvailability}' --output json --no-cli-pager
aws bedrock get-inference-profile --region ap-southeast-1 --profile '<operator-profile>' --inference-profile-identifier global.anthropic.claude-sonnet-4-6 --query '{id:inferenceProfileId,status:status}' --output json --no-cli-pager
aws bedrock get-inference-profile --region ap-southeast-1 --profile '<operator-profile>' --inference-profile-identifier global.anthropic.claude-opus-4-6-v1 --query '{id:inferenceProfileId,status:status}' --output json --no-cli-pager
aws service-quotas list-service-quotas --region ap-southeast-1 --profile '<operator-profile>' --service-code bedrock --query "Quotas[?contains(QuotaName, 'Sonnet 4.6') || contains(QuotaName, 'Opus 4.6')].[QuotaName,Value,Unit,Adjustable]" --output table --no-cli-pager
aws bedrock get-model-invocation-logging-configuration --region ap-southeast-1 --profile '<operator-profile>' --query '{configured:loggingConfig != `null`,text:loggingConfig.textDataDeliveryEnabled,audio:loggingConfig.audioDataDeliveryEnabled}' --output json --no-cli-pager
```

The [availability API](https://docs.aws.amazon.com/cli/latest/reference/bedrock/get-foundation-model-availability.html)
and [quota listing](https://docs.aws.amazon.com/cli/latest/reference/service-quotas/list-service-quotas.html)
do not prove a successful inference or its latency. Review the quota type for
the exact route: request/token limits and global cross-region limits are not
interchangeable. No account-specific quota has been verified here.

For current commercial AWS access, follow [AWS model-access guidance](https://docs.aws.amazon.com/bedrock/latest/userguide/model-access.html)
if the checks fail. Anthropic may require the one-time use-case submission and
applicable model agreement/Marketplace permissions. Submission, agreement,
subscription, quota increase and first invocation are state-changing operator
work; this document does not execute or silently authorize them. Do not test
access by sending a real user prompt. Do not grant a broader IAM policy merely
to make the read-only check pass.

## Region, speech and cost decisions to freeze after evidence

Hosted-agent regions are currently `us-east` (Ashburn, Virginia), `eu-central`
(Frankfurt), and `ap-south` (Mumbai). Singapore appears in the realtime network
but is not an offered hosted-agent region. Choose an actual available region
after measuring the Vietnam-client -> agent -> Journal/AI -> Bedrock path;
record that choice before the E2E gate. [Region documentation](https://docs.livekit.io/deploy/admin/regions/endpoints/)

Journal/AI's current Bedrock request region is `ap-southeast-1`. AWS documents
these identities; a base-model ID is not proof that direct in-region inference
is available:

| Candidate                                                                                                             | Base-model ID                  | Global inference profile ID           |
| --------------------------------------------------------------------------------------------------------------------- | ------------------------------ | ------------------------------------- |
| [Claude Sonnet 4.6](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-sonnet-4-6.html) | `anthropic.claude-sonnet-4-6`  | `global.anthropic.claude-sonnet-4-6`  |
| [Claude Opus 4.6](https://docs.aws.amazon.com/bedrock/latest/userguide/model-card-anthropic-claude-opus-4-6.html)     | `anthropic.claude-opus-4-6-v1` | `global.anthropic.claude-opus-4-6-v1` |

If the approved route uses a `global.*` profile, disclose cross-region processing
before microphone consent. An `ap-southeast-1` request endpoint does not promise
Singapore-only processing. Agent compute, media routing, LiveKit Inference
provider processing and AWS inference destinations are distinct decisions.
LiveKit Inference normally permits global provider routing; inspect its region
restriction setting instead of assuming the worker region constrains it.
[Inference region behavior](https://docs.livekit.io/agents/models/inference/)

Candidate speech direction is Deepgram Nova-3 with language `vi` and Cartesia
Sonic through **LiveKit Inference**. The official
[Deepgram guide](https://docs.livekit.io/agents/models/stt/deepgram/) and
[Cartesia guide](https://docs.livekit.io/agents/models/tts/cartesia/) list
Vietnamese support. MB-760 must measure Vietnamese accents, names, interruptions,
latency and pronunciation, then pin the exact supported SDK/model revision and
reviewed voice ID. A candidate is not a tested or frozen production voice.

[LiveKit Inference](https://docs.livekit.io/agents/models/inference/) uses the
LiveKit project credentials: no separate Deepgram or Cartesia provider account
or key is required for this route. A Cartesia account is optional for browsing
its voice library, not a deployment prerequisite. Do not switch to a direct
provider plugin, clone a voice or upload a voice sample as setup shortcuts.

The [current Build/Ship Inference price table](https://livekit.com/pricing/inference)
lists Nova-3 monolingual at $0.0048/audio minute and Cartesia Sonic candidates
at $50/million generated characters. Vietnamese benchmark selection must confirm
which SKU applies. AWS Bedrock tokens are billed separately and do not consume
the $2.50 LiveKit credit. A call's duration is not its TTS character count or
Bedrock token count; record each unit separately and recheck rates before the
opt-in run. This runbook provides no all-in per-call price or AWS credit claim.

## Privacy and security gate

Before any synthetic Cloud call, the implementers and operator must verify:

- [ ] The client explicitly accepts microphone use and the AI/non-clinical,
      ephemeral-session and cross-region disclosures before capture; permission
      denial leaves the microphone and room disconnected.
- [ ] Journal/AI freshly authorizes the user, both independent Care grants and
      current Consultation entitlement at the contract boundaries. An unavailable owner,
      revoked consent, expired session or changed route stops further paid work.
      Verify the five-second renewal, ten-second authority lease and two-second
      owner REST deadline; failed renewal stops processing and purges current-call RAM.
- [ ] Browser/mobile grants publish microphone audio only to one authorized
      voice room. Opaque room/participant metadata contains no user content. Agent
      dispatch cannot be requested by an arbitrary client or join appointment rooms.
- [ ] LiveKit project Agent observability is off, and the Node session explicitly
      starts with `record: false`. SDK defaults are insufficient. Room egress,
      recording, transcription storage, application request-body logging and
      provider-content tracing are disabled; secrets never enter log output.
- [ ] AWS invocation-content logging has been inspected in the actual request
      region. If shared-account policy requires prompt/response logging, block this
      ephemeral demo until an approved isolated configuration resolves the conflict.
- [ ] Speech/transcript/replies stay in bounded current-call RAM only. End,
      timeout, worker death, consent revocation and disconnect expiry clear context;
      reconnection cannot load previous history or create a persisted conversation.
- [ ] LiveKit keys and dispatch/service credentials are injected server-side.
      The agent receives no user JWT; its REST credential is purpose- and
      session/attempt-bound. Only Journal/AI holds Bedrock credentials. No credential, private project URL,
      raw audio, transcript or hidden reasoning is placed in source, CI artifacts,
      metrics, support screenshots or crash reports.

LiveKit Inference's advertised zero data retention is separate from
[Agent session recording](https://docs.livekit.io/reference/agents/session-recording-options/).
The latter explicitly supports `record: false`; disabling only audio can still
retain transcripts, traces or logs. AWS
[invocation logging](https://docs.aws.amazon.com/bedrock/latest/userguide/model-invocation-logging.html)
can retain model inputs and outputs, so its default-disabled status does not
replace the account check. Application RAM deletion does not establish a claim
about every provider's processing location or non-content operational metadata.

## Opt-in deployment commands: not executed

MB-759 now supplies the agent source, lockfile, Dockerfile and secret schema;
MB-757/758 supply authenticated Journal/AI endpoints. These artifacts do not
authorize deployment. Complete the account/privacy/benchmark gates first.
Do not reuse the persisted AI Companion messages endpoint to make a demo work.

After those stories pass, the operator can follow the official
[CLI deployment procedure](https://docs.livekit.io/reference/developer-tools/livekit-cli/agent/deployment/).
These commands are **future examples and were not executed**:

```powershell
Set-Location '<reviewed MB-759 Node agent source directory>'
lk cloud auth
lk project list
lk project set-default mentalbridge
lk agent create --help
lk agent create --region '<reviewed-hosted-region>' --secrets-file '<operator-managed-secret-file-outside-repo>'
lk agent status
```

`lk agent create` uploads code, builds and deploys an agent; it is not a
read-only readiness probe. Review the selected existing project, actual region,
secret file and CLI version before executing it. Use a configured deployment's
reviewed update procedure for later revisions rather than creating duplicate
workers. No new project, provider account, paid tier or production rollout is
part of MB-755. Disable new voice admission/dispatch and stop the voice worker
for rollback; preserve the existing consultation-video configuration.

## Product limits and acceptance evidence

These are the **accepted application target**, enforced in the MB-756/757 owner
ledger and independent of provider allowances; live end-to-end evidence remains
an enablement gate:

| Rule             | Target                                                                             |
| ---------------- | ---------------------------------------------------------------------------------- |
| `FREE`           | No voice calls                                                                     |
| `PLUS`           | At most 2 successful sessions/day, 5 minutes/session, 10 ACTIVE minutes/day total  |
| `PREMIUM`        | At most 3 successful sessions/day, 10 minutes/session, 30 ACTIVE minutes/day total |
| Day boundary     | `Asia/Ho_Chi_Minh`, enforced by owner time                                         |
| Idle / reconnect | 60-second idle cutoff; at most 30-second reconnect grace                           |
| Concurrency      | 2 reserved/live calls globally, 1 per user across devices                          |

Admission reserves capacity and duration; the authenticated first successful
spoken AI reply commits one session count and opens ACTIVE usage. A failed
attempt with no first reply releases its quota reservation. Global/user capacity
is released only after confirmed provider teardown or fencing proves the old
agent/media cannot continue; timeout alone does not free it. Charge only eligible
ACTIVE intervals, round their summed milliseconds up once at settlement and
release unused reserved seconds. The absolute deadline is authorization time
plus the smaller of package session maximum and remaining daily seconds;
setup, midnight, upgrade and reconnect never extend it. Reconnection is the same
session, not another allocation or extra duration.
Test limits across devices, worker failures and the Vietnam-day boundary using
owner responses, not client clocks. A free-provider quota is never a package
entitlement; a paid package cannot bypass an exhausted provider allowance.

Later synthetic E2E evidence must cover microphone denial, user end, speaking
and listening, barge-in, idle/end cutoff, reconnect within/beyond grace, duplicate
commands, simultaneous admission, package limits, expired/revoked access,
provider failure and cleanup. Measure first reply/turn latency from Vietnam and
the actual chosen hosted region. Confirm that no recording/transcript appears
in the project or application stores after the call. Preserve only source SHA,
synthetic scenario IDs, durations, safe error codes, aggregate cost/usage and
redacted pass/fail screenshots. No paid calls, Cloud recordings or credentials
belong in ordinary CI.

## Delivery handoff and current evidence

MB-756/757 implement Journal/AI metadata, admission, quota and lifecycle;
MB-758 adds guarded turns; MB-759 supplies the disabled Node worker.
MB-760/MB-762 must establish provider benchmarks and enablement gates. Remaining
client and controlled E2E work follows its approved task contracts; this runbook
does not reassign it.
Freeze speech choices only after the applicable Vietnamese benchmark.
Each story must publish its exact implementation source SHA and applicable
tests; this runbook is not acceptance evidence for those stories.

At the MB-755 checkpoint, only official documentation and repository sources
were read. No account quota/access check, LiveKit agent deployment, Bedrock
invocation, speech synthesis, Cloud audio call or real-device test ran. Actual
Free-plan balance, model access, inference quota, regional availability, selected
voice and latency remain open gates. Keep voice disabled until the dependent
implementation and these checks supply evidence.
