# Companion Voice policy v1

## Policy metadata

| Field                      | Value                                                                                         |
| -------------------------- | --------------------------------------------------------------------------------------------- |
| Decision                   | `MB-AI-VOICE-COMPANION-001`                                                                   |
| Policy version             | `companion-voice-policy-v1`                                                                   |
| Status                     | Owner-approved target for MB-755; no voice runtime or live-provider approval                  |
| Approved date              | 2026-10-23                                                                                    |
| Actor and owner            | Authenticated USER; Journal/AI                                                                |
| Consent owner              | Care                                                                                          |
| Package authority          | Consultation                                                                                  |
| Voice consent type/version | `AI_VOICE_PROCESSING` / `ai-voice-processing-capstone-v1`                                     |
| Decision record            | [ADR 0046](../adr/0046-ai-voice-companion-contract.md)                                        |
| Machine-readable policy    | [Approved policy values](companion-voice-policy-v1.json)                                      |
| Planned REST               | [Voice and owner-check contract](../../contracts/proposals/companion-voice-v1.openapi.json)   |
| Lifecycle                  | [Nine-state transition contract](../../contracts/proposals/companion-voice-lifecycle-v1.json) |
| Worker control             | [Closed envelope schema](../../contracts/proposals/companion-voice-worker-v1.schema.json)     |

## Product boundary

Voice is an optional Vietnamese USER-to-AI Companion mode. It is separate from
specialist appointments, consultation credits and the text Companion's retained
conversations and quota. FREE remains text-only. PLUS/PREMIUM do not receive
unlimited voice or guaranteed provider capacity.

AI responds supportively and non-diagnostically. It cannot score PHQ-9/GAD-7,
make a safety/clinical verdict, choose eligibility or entitlement, mutate a
SupportPlan, book or complete an appointment, consume consultation credits,
create earnings or contact another person. The existing deterministic help
entry and ordinary text/manual workflows do not depend on voice availability.

## Eligibility and two independent consents

Before admission, require a verified current USER principal, effective
Consultation-authoritative PLUS/PREMIUM entitlement, enabled approved voice
providers, available quota/capacity, and both current Care grants:

1. Existing `AI_PROCESSING`, with its existing version and behavior unchanged.
2. Separate `AI_VOICE_PROCESSING` at `ai-voice-processing-capstone-v1`, explicitly
   granted after the current Care-owned voice disclosure is shown.

An existing text grant, paid plan, microphone permission or client acceptance
flag is insufficient. Care retains append-only versioned decisions. Missing,
revoked or outdated voice consent denies voice; a material voice vendor/purpose
change requires a new voice disclosure and fresh voice grant without changing
the independent text stream. Withdraw either required grant and the active
voice session must end. Withdrawal is separate from historical metadata cleanup.

The voice notice explains audio capture, transient STT text, Journal/AI model
processing, TTS output, provider identities, cross-region processing when global
Bedrock profiles are selected, no saved audio/transcript, the 30-day metadata
window, and how to stop/withdraw. Provider privacy settings and publication of
the exact notice below remain enablement gates; an absent notice never falls
back to the old text disclosure.

### Immutable Vietnamese voice disclosure

The following title and content are the accepted target text for
`ai-voice-processing-capstone-v1`. They are documentation, not a claim that
Care publishes the notice or any client currently renders it. The technical
processing chain is LiveKit media/Inference STT → Journal/AI → AWS Bedrock →
LiveKit Inference TTS. Vendor/privacy configuration must pass the downstream
gate before this notice can authorize actual processing.

> **Đồng ý trò chuyện bằng giọng nói với AI**
>
> Khi bạn chủ động bắt đầu, MentalBridge dùng micro để trò chuyện với AI bằng tiếng Việt. LiveKit truyền âm thanh; LiveKit Inference chuyển lời nói của bạn thành văn bản và chuyển câu trả lời thành giọng nói. MentalBridge gửi phần văn bản cần thiết của cuộc gọi hiện tại đến AI Claude qua AWS Bedrock để tạo câu trả lời.
>
> Dữ liệu có thể được xử lý ở nhiều quốc gia. Tuyến xử lý toàn cầu của AWS Bedrock có thể chuyển dữ liệu ra ngoài Singapore; việc kết nối tại Singapore không có nghĩa dữ liệu chỉ được xử lý ở Singapore hoặc Việt Nam.
>
> Nội dung chỉ được giữ tạm trong bộ nhớ của cuộc gọi hiện tại. Tính năng này không đọc lại nhật ký, cuộc trò chuyện bằng văn bản hoặc lịch sử riêng trước đó của bạn. MentalBridge không ghi âm, lưu bản chuyển lời nói thành văn bản hoặc lưu nội dung trả lời; không đưa những nội dung này vào nhật ký hệ thống. Các chế độ ghi lại và theo dõi nội dung trên nền tảng đám mây được tắt. Khi cuộc gọi kết thúc, phần nội dung tạm trong bộ nhớ được xóa.
>
> MentalBridge chỉ giữ thông tin tối thiểu để tính lượt, thời gian sử dụng và xử lý sự cố trong 30 ngày, như mã cuộc gọi, thời điểm, thời lượng và lý do kết thúc. Thông tin này không chứa âm thanh hoặc nội dung trò chuyện.
>
> Bạn đang trò chuyện với AI, không phải nhà trị liệu hay chuyên gia tư vấn. AI chỉ hỗ trợ trao đổi; không chẩn đoán, không quyết định an toàn và không tự thay đổi kế hoạch hỗ trợ, đặt lịch hoặc liên hệ người khác.
>
> Bạn cần đồng ý riêng cho cả xử lý bằng AI và trò chuyện bằng giọng nói. Quyền dùng micro hoặc gói trả phí không thay thế hai sự đồng ý này. Bạn có thể dừng cuộc gọi hoặc rút lại một trong hai sự đồng ý; MentalBridge sẽ chặn xử lý tiếp, kết thúc cuộc gọi và xóa nội dung tạm. Khi mất kết nối, việc dừng được thực hiện theo thời hạn bảo vệ của hệ thống, không phải tức thời trong mọi sự cố mạng.
>
> Nếu mục đích hoặc nhà cung cấp xử lý giọng nói thay đổi đáng kể, MentalBridge sẽ thông báo và xin lại sự đồng ý cho giọng nói. Việc này không thay đổi sự đồng ý hoặc đặt lại hạn mức trò chuyện bằng văn bản của bạn.

The voice grant records this exact version separately from the existing
AI-processing stream. Later replacement text must receive a new voice version;
do not edit the historical v1 text or accept a client-authored substitute.

Current owner decisions are rechecked at start, before each model turn, on every
reconnect and authority-lease renewal. A worker never receives the user's JWT or
supplies an account, role, package or consent decision. Journal/AI derives the
owner from its session and uses dedicated authenticated owner-check REST contracts. Their future
implementation is required; the existing bearer-forwarding endpoints alone do
not satisfy this service-owned session flow. Denial or owner dependency failure
stops further processing and initiates cutoff, without a stale-positive grant.

The authority lease lasts at most 10 seconds and renews every five seconds;
each current-owner REST read has a two-second deadline. Failed renewal halts all
processing, purges current-call RAM and initiates provider cutoff. Observed
revocation cancels immediately. When its signal is lost, the maximum authority
window is 10 seconds, rather than an instantaneous-network-partition guarantee.
No transaction or data-owner lock spans the remote authorization reads.

## Finite quota policy

| Package | Successful sessions per local day | Maximum admitted session ceiling | Daily ACTIVE seconds |
| ------- | --------------------------------: | -------------------------------: | -------------------: |
| FREE    |                                 0 |                                0 |                    0 |
| PLUS    |                                 2 |                              300 |                  600 |
| PREMIUM |                                 3 |                              600 |                1,800 |

These counters never consume, provision, release or refund text answers or
consultation credits. Server-side fair-use, request, token, provider cost and
capacity controls may reject work safely; no client derives unlimited access
from a package or a missing displayed counter.

Non-replay start attempts are additionally capped at three per USER per minute
and ten per USER per local day. These guards are independent of the two/three
successful-session quota. Exact replay of the same accepted start does not
consume an additional attempt, quota reservation or successful session.

The server uses `Asia/Ho_Chi_Minh`. The quota day starts at local 00:00 and has no
rollover. Freeze the quota day and policy/entitlement provenance at admission.
Crossing midnight never moves usage/reservations into a fresh day or extends an
active session. An upgrade raises that day's applicable ceilings while preserving
all consumed and reserved sessions/seconds. It never rewrites historical usage,
resets counters or extends an already admitted deadline. Entitlement loss ends
the session; reconnect cannot preserve a lost paid grant.

### Reservation, first reply and usage

Reserve one possible session and a finite duration before room creation/dispatch.
Admission includes existing consumed usage and all concurrent reservations.
The reserved duration cannot exceed the package session maximum or the remaining
daily seconds. If either session allowance or duration is exhausted, reject
before provider work. The server freezes an absolute deadline at authorization;
neither retry, reconnect, midnight nor upgrade moves it later.

Precisely, `admittedSeconds = min(packageSessionMaximum, remainingDailySeconds)`
and `hardStopAt = authorizedAt + admittedSeconds`. This wall-clock ceiling
includes setup and reconnect time even though those states are not chargeable.

A successful first spoken AI reply commits one session count and opens
chargeable usage. Its signal must come from the authenticated current worker
attempt observing successful AI audio publication to the room while the owner
is connected. It is operational delivery evidence, not proof that a person
heard or benefited from the response. A client cannot assert that evidence.

If the session ends/fails/expires without that first reply, release its entire
session and duration reservation. Once a first reply succeeds, keep exactly one
session consumed and charge only ACTIVE time after that reply. CONNECTING,
RECONNECTING and terminal/cleanup time do not consume ACTIVE seconds. Idle time
inside ACTIVE remains ACTIVE time until the idle cutoff. Do not round individual
heartbeats or reconnect intervals into extra charged sessions or duplicate time;
the authoritative total is the union of eligible intervals, bounded by reserved
duration and the absolute deadline. Sum post-first-reply ACTIVE milliseconds,
then round up to whole seconds once per session at settlement. Release unused
reserved seconds. Duplicate, reordered and retried reports cannot add a second
interval or reverse settled usage.

Usage/quota facts and session metadata belong to Journal/AI MongoDB under
MB-756. No client counter, provider billing counter, worker claim or another
owner's database becomes the authoritative voice ledger.

## Capacity, lifecycle and time limits

Across the controlled demo, permit at most two reserved/live voice sessions and
one per USER across devices. A reservation consumes capacity before dispatch.
Capacity remains occupied through uncertain provider cleanup; a lease timeout
alone must not admit another session while an old agent/room may still be live.

The exact lifecycle vocabulary is:

| State          | Meaning                                                                                                                            |
| -------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `REQUESTED`    | A syntactically accepted start awaits current authorization/admission; no media permission exists.                                 |
| `AUTHORIZED`   | Current owner checks passed and quota/capacity were reserved for one owner-bound session.                                          |
| `CONNECTING`   | The approved room/worker and client media connection are being established.                                                        |
| `ACTIVE`       | Authorized owner and current agent are connected for the current call. First-reply evidence separately controls quota consumption. |
| `RECONNECTING` | A temporary disconnection is inside the bounded reconnect grace; no ACTIVE time is charged.                                        |
| `ENDING`       | Further turns are blocked and server-side agent/participant/room cutoff is being confirmed.                                        |
| `ENDED`        | Explicit/normal closure is confirmed and usage is settled.                                                                         |
| `FAILED`       | A setup, authorization/dependency or execution failure ended the session; quota follows the first-reply rule.                      |
| `EXPIRED`      | Absolute, idle, reconnect or granted-duration expiry ended the session; quota follows the first-reply rule.                        |

Normal establishment follows REQUESTED → AUTHORIZED → CONNECTING → ACTIVE.
ACTIVE may enter RECONNECTING and return to ACTIVE only after fresh checks and
within the existing deadline/grace. Live cutoff passes through ENDING before a
terminal outcome; a pre-media denial/setup failure can terminate without media
cleanup. Terminal sessions never reactivate. A new session needs a new admission
and cannot reuse an old room, attempt, reservation or grant.

REQUESTED owner checks must finish within 10 seconds; CONNECTING is bounded to
45 seconds. The existing absolute cutoff wins if it arrives sooner. A failed
setup releases its quota under the first-reply rule, but releases the global or
owner permit only after provider teardown or fencing proves no old media/agent
can continue. No timeout or duplicate callback creates a second refund.

The idle cutoff is 60 seconds without user/agent conversational activity; worker
heartbeats and control messages do not fake activity. Reconnect grace is at most
30 seconds from disconnection. Absolute expiry always wins even if idle or
reconnect time remains. Repeated reconnects never extend the absolute deadline.
The server decides timestamps and cutoff; a client background timer is advisory.

## Worker, room and model boundary

Only Journal/AI creates the opaque AI room mapping and explicitly dispatches the
approved Node worker. It uses the existing `mentalbridge` LiveKit Cloud project,
hosted agents and Vietnamese LiveKit Inference speech providers. It does not
reuse a Consultation appointment room or credit. Client credentials allow only
the required microphone publication and agent-audio subscription to that exact
room/identity. They are memory-only, short-lived and returned with `no-store`.
Camera, recording, egress, administrative permissions, arbitrary data publishing
and client-controlled agent dispatch are unavailable.

Dispatch metadata carries only opaque session/attempt references. The worker
authenticates separately to Journal/AI using its session/attempt scope; it cannot
choose a different owner, obtain another session's content or invoke Bedrock
directly. Journal/AI rechecks current Care consent and Consultation entitlement
before every bounded model turn and retains existing approved package routing.
STT text, current-call memory, model input/reply and TTS text exist only in the
authorized in-memory pipeline. Worker control, usage and termination envelopes
contain no audio, transcript, model text, provider key or end-user token.

New join credentials require current authorization. Token expiry alone does not
disconnect an already connected provider participant. Stop, withdrawal, lost
entitlement or expiry requires explicit provider cutoff and room cleanup.
Unknown dispatch outcomes are reconciled by the same session/attempt identity;
they do not authorize another agent or session. Worker claims, heartbeat and
finish reports are fenced and replay-safe.

Current-call context is bounded to four recent user/assistant pairs and 2,000
tokens. Recognized text is at most 2,000 characters per turn; spoken response
text is at most 1,000 characters. Generation must not automatically retry after
any spoken prefix. A lost response does not authorize persisting transcript or
reply text in an idempotency snapshot to make it replayable.

## Privacy and retention

No audio/recording, transcription, synthesized speech, assembled prompt, model
reply or previous conversation content is written to a database, filesystem,
object store, replay snapshot, log, trace, event, metric label or crash artifact.
Disable agent/LiveKit content observability, recording, egress and transcript
capture. Operational metrics may contain only aggregate content-free counters;
session identifiers are not a license for transcript-level tracing.

Context is confined to the current call's RAM. No old text chat, Journal,
assessment, emotion, SupportPlan, ConsultationBrief, SessionSummary or private
note is automatically read or supplied. Do not repurpose the existing persisted
text-message or replay collections for voice. Stop/failure/expiry discards the
in-memory conversation and cancels further provider work.

Keep only bounded content-free voice usage and operational metadata for 30 days:
owner/session/attempt references, policy/package provenance, state/coded reason,
timestamps, reservation/settled seconds and session counters needed for admission
and reconciliation. Provider recordings/transcripts are never an alternative
source. MB-756 must provide the retention/deletion implementation and must not
restore discarded content during replay or recovery.

Live enablement requires verifying provider-side no-content-retention settings
and the disclosed processing locations. A configured AWS region alone does not
prove single-region handling when a `global.*` Bedrock profile is used. No
unverified end-to-end encryption, provider training or retention assurance is
claimed by this policy.

## Failures and recovery

| Situation                                                               | Required outcome                                                                                                                                  |
| ----------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------- |
| FREE, absent/outdated/withdrawn consent or unavailable owner decision   | No new media/model work; deny safely, or end a live session. Never substitute client/stale authority.                                             |
| Daily quota, owner concurrency or demo capacity unavailable             | Reject before a second dispatch; return a distinct bounded quota/busy reason.                                                                     |
| Setup, STT, model, TTS or dispatch failure before first spoken reply    | End/clean up; refund the reserved session allowance and seconds.                                                                                  |
| Provider/worker failure after first spoken reply                        | Stop further turns, settle one session and actual eligible ACTIVE time, release unused duration.                                                  |
| Temporary network loss                                                  | Enter RECONNECTING for at most 30 seconds, charge no reconnect time and reauthorize before returning ACTIVE.                                      |
| Idle 60 seconds, reconnect grace or absolute/remaining-duration expiry  | Block further turns, cut off provider media and settle EXPIRED under the same first-reply rule.                                                   |
| Explicit stop or entitlement/consent loss                               | Block further turns immediately on observation, initiate cutoff and settle the same session.                                                      |
| Unknown cleanup or worker crash                                         | Fence old attempts, retain capacity until closure is established and reconcile idempotently; never infer success or issue parallel authorization. |
| Missing provider access, quota, approved route or privacy configuration | Voice remains unavailable; text and manual features keep their existing behavior.                                                                 |

Errors use safe Problem Details with stable codes, correlation identity and no
provider/raw-content/credential details. A timeout is an unknown outcome until
reconciled, not permission to start another live session. Provider failures never
produce a consultation settlement, clinical conclusion or automatic third-party
action.

## Delivery and verification

MB-755 accepts this target and planned REST/worker fixtures only. It changes no
active handler, runtime schema/migration, frontend, dependency, cloud plan or
deployment. MB-756 supplies owner Mongo admission/usage; MB-759 supplies the Node
worker; owner-check, consent, provider privacy/model-access and MB-760/MB-762
benchmark/enablement prerequisites must pass before voice is offered live.

Contract checks cover closed shapes, the exact nine states, permitted lifecycle
examples, safe errors and rejected content/credential overposting. Runtime tests
must additionally prove owner/global races, immutable-day quota accounting,
upgrade/withdrawal/reconnect behavior, first-reply refunds, stale-attempt fencing
and provider cutoff after failures. Synthetic schema fixtures do not prove live
speech quality, user hearing, provider quota or privacy configuration.
