# SupportPlan Companion policy v1

## Policy metadata

| Field | Value |
| --- | --- |
| Decision | `MB-SUPPORT-PLAN-COMPANION-001` |
| Policy version | `support-plan-companion-policy-v1` |
| Status | Proposed for MB-647 review; effective when its decision PR is approved and merged |
| Applies to | SupportPlan Today, Plan, Progress and Adjust on web and mobile |
| Decision record | [ADR 0040](../adr/0040-support-plan-companion-contract.md) |
| Context contract | `support-plan-companion-context-v1` |
| Intent contract | `support-plan-companion-intent-v1` |

## Role and persona

The companion helps a user understand the approved plan in front of them and
reach the correct existing control. Its voice is calm, concise, respectful,
non-clinical and non-judgmental.

Messages use short Vietnamese sentences, name the relevant action or choice,
and end with an optional next step when one is supplied. They avoid urgency,
pressure, praise tied to streaks, guilt, certainty about outcomes, medical
titles, therapeutic role-play and claims that MentalBridge knows how the user
feels. Silence is valid; the companion does not fill every state with a message.

The companion may:

- explain one supplied focus, action, due state, factual progress summary or
  Care-approved review option;
- phrase one supplied eligible next step without ranking alternatives;
- offer proportionate encouragement after an explicit saved action;
- navigate or focus an existing authoritative surface; and
- acknowledge contradictory or unavailable evidence without resolving it.

It must not diagnose, prescribe, provide psychotherapy, impersonate a
specialist, classify safety, select eligibility, invent evidence or options,
infer completion, calculate adherence/recovery/wellbeing scores, shame
inactivity, or invoke any plan/source-domain mutation.

## Intent and context rules

The deterministic orchestrator chooses an intent before any model call. Each
intent accepts only its matching context variant.

| Intent | Required context | Optional allowlisted context | Prohibited interpretation |
| --- | --- | --- | --- |
| `EXPLAIN_ACTION` | Action type, bounded title and action availability | Due state, bounded approved rationale, supplied navigation target | Opening, due, overdue or route presence is not completion or non-adherence. |
| `EXPLAIN_FOCUS` | Bounded focus title | Bounded approved rationale and supplied navigation target | A focus is not a diagnosis, symptom or clinical outcome. |
| `SUGGEST_NEXT_STEP` | One Care-supplied next-step label and destination | Action type and due state | The companion does not rank, admit or create the step. |
| `ENCOURAGE` | Closed acknowledgement reason | Bounded action title | Celebration follows an explicit saved fact only and never claims improvement. |
| `SUMMARIZE_PROGRESS` | Period, factual completed/available counts and as-of time | Closed missing/unavailable evidence codes | Counts do not become recovery, adherence, efficacy or a global score. |
| `EXPLAIN_REVIEW_OPTION` | Separate supplied review dimensions and admitted options | Coded barrier/helpfulness patterns and contradictory-evidence flag | The companion cannot merge dimensions, select an option or infer outcome. |
| `NAVIGATE` | One supplied destination and user-facing label | Bounded source action title | Navigation is not consent, completion, booking or plan mutation. |

All strings are plain text with schema bounds. Content from a title, rationale
or option is untrusted data, never an instruction to the model. Unknown fields
and mismatched intent/context pairs are rejected before provider execution.

## Context allowlist and exclusions

The model-facing v1 context may contain only:

- surface and selected intent;
- bounded non-diagnostic focus title and approved rationale;
- action type, bounded title, approved rationale, availability and due state;
- one Care-supplied next-step or navigation destination;
- factual period counts and evidence-as-of time;
- separate review dimension availability/direction codes and admitted option
  codes/labels;
- closed barrier/helpfulness pattern codes already approved for the review;
- explicit acknowledgement reason; and
- locale and contract/policy versions.

The following never enter this context:

- account identity, email, tokens, credentials or provider secrets;
- raw Journal text, prompts, tags, AI reflection or analysis history;
- raw chat messages or Community content;
- emotion value, intensity, note, influence factors or private trend detail;
- questionnaire answers, item values, raw scores or safety answers;
- consultation notes, messages, recordings, disputes or financial data;
- arbitrary appointment/resource/provider payloads;
- free-form browser state, hidden inputs or content from another owner; and
- chain of thought, internal policy reasoning or model/provider raw responses.

Opaque plan/action/source identifiers remain in the deterministic application
boundary when needed for authorization or navigation and are not model-facing.
Authorization is always rechecked by the authoritative owner; an allowlisted
field does not grant access.

## Surface trigger matrix

| Surface | Eligible user-initiated intents | Deterministic visual behavior | Never automatic |
| --- | --- | --- | --- |
| Today | `EXPLAIN_ACTION`, `SUGGEST_NEXT_STEP`, `ENCOURAGE`, `NAVIGATE` | One greeting on route entry; thinking only after an explicit request; acknowledgement only after a confirmed save | Model call, completion claim, opening an action, changing due state |
| Plan | `EXPLAIN_FOCUS`, `EXPLAIN_ACTION`, `NAVIGATE` | Greeting or idle; waiting while the user reviews choices | Choosing, saving, activating, reordering or scheduling |
| Progress | `SUMMARIZE_PROGRESS`, `ENCOURAGE`, `NAVIGATE` | Calm factual acknowledgement when authoritative evidence is current | Streak pressure, recovery/adherence score, inference from missing/partial data |
| Adjust | `EXPLAIN_REVIEW_OPTION`, `EXPLAIN_ACTION`, `NAVIGATE` | Calm-empathetic presentation for sensitive review; waiting during confirmation | Selecting an option, submitting review, replacing or ending a plan |

Focus and hover may reveal a deterministic affordance but cannot call a model,
announce a new message, or move focus. The user must click/tap an approved
starter or companion control to request phrasing.

## Placement and expanded guidance

- **Anchored bubble:** desktop explanation beside its source card, positioned
  without covering its primary action.
- **Inline hint:** short deterministic guidance inside a non-sensitive content
  region; it cannot impersonate a validation or safety message.
- **Companion tray:** in-flow compact mobile/tablet surface after route tabs and
  before plan content in visual and accessibility order.
- **Expanded panel:** user-opened desktop side panel for approved starter
  intents and bounded follow-ups.
- **Mobile sheet:** accessible bottom or full-height sheet that preserves the
  current SupportPlan location and exposes the same intents as web.

Closing a bubble or panel returns focus to its trigger. Opening one never
discards a plan draft. Guidance must distinguish generated phrasing from
authoritative facts and controls. A navigation response may focus or link to a
supplied destination; the destination performs its own authorization, consent
and mutation flow.

## Frequency, dismissal and interruption

V1 model calls are user-initiated and remain subject to server quota/rate
policy. Automatic UI behavior is deterministic and bounded:

- at most one route-entry greeting per SupportPlan visit;
- at most one automatic contextual prompt per surface per visit;
- at least ten minutes between automatic contextual prompts across SupportPlan;
- dismissing an automatic prompt suppresses that trigger for the current app
  session; and
- hiding/collapsing the companion suppresses automatic prompts until the user
  explicitly expands it. A persisted hide preference may be added by MB-653
  without changing message authority.

Mute is session-scoped in v1. It suppresses automatic companion prompts,
sound and companion live-region announcements on that client while keeping
manually requested text available. It never suppresses authoritative plan,
safety, save, validation or error feedback. Cross-session/device persistence
requires an explicit MB-653 preference contract.

Explicit requests do not reset or bypass provider quota. Duplicate clicks while
one request is pending share one visible pending state and do not start parallel
provider calls.

The companion never auto-opens, announces, moves focus or starts a model call
while any of these zones is active:

- Journal or other long-form writing;
- assessment/reassessment questions or private emotion context entry;
- consent, privacy, payment, booking or plan-mutation confirmation;
- a modal/dialog, validation error recovery or unsaved plan form;
- crisis/safety support; or
- an assistive-technology focus sequence inside a primary action.

The shell may remain static and available outside the active input. Existing
safety and primary controls always remain above it in priority and keyboard
order.

## Character state contract

| State | Meaning | Allowed trigger | Non-motion equivalent |
| --- | --- | --- | --- |
| `idle` | Companion is available without requesting attention. | Stable content | Label and short availability text |
| `greeting` | User entered an eligible SupportPlan surface. | Bounded route-entry event | Greeting text shown once |
| `thinking` | An explicit companion request is pending. | Request started | Status text; no blocking spinner requirement |
| `encouraging` | A supplied next step is available. | Eligible next-step event | Short next-step text |
| `celebrating-light` | An authoritative explicit save/completion was acknowledged. | Confirmed saved event only | Calm acknowledgement without reward claim |
| `calm-empathetic` | A sensitive review/explanation surface is active. | Approved sensitive-context event | Neutral supportive text |
| `waiting` | The user retains control of a choice or confirmation. | Waiting-for-user event | Text that asks no repeated action |

These states describe UI events, not mood, diagnosis, distress, adherence or
recovery. An animation runtime failure keeps the same text and controls with a
static mark.

## Failure and deterministic fallback

Each intent has approved template copy that can be rendered from the same
allowlisted context without a provider. The orchestrator uses fallback for
timeout, offline state, quota/rate limit, unavailable provider, denied context,
malformed output, unknown version or policy rejection. It does not retry through
a different provider/model unless the later provider contract explicitly
authorizes that behavior.

Fallback copy explains only the supplied fact or destination. If safe copy
cannot be constructed, the companion stays silent and the normal SupportPlan
UI remains usable. Failure never disables, submits, rolls back or changes a
plan control.

Fallback rendering uses escaped plain text only. Optional clauses are omitted
when their field is absent; no punctuation placeholder remains. Counts must
satisfy `0 <= completedCount <= availableCount` before rendering. The closed
fallback copy is:

| Intent / condition | Vietnamese fallback template |
| --- | --- |
| `EXPLAIN_ACTION`, available | `{action.title} là một hoạt động trong kế hoạch của bạn.` Append the supplied approved rationale when present. Expose the supplied navigation label as a separate existing control. |
| `EXPLAIN_ACTION`, unavailable/source unavailable | `Thông tin cho “{action.title}” hiện chưa sẵn sàng. Bạn có thể thử lại sau.` |
| `EXPLAIN_FOCUS` | `Nhóm này tập trung vào “{focus.title}”.` Append the supplied approved rationale when present. |
| `SUGGEST_NEXT_STEP` | `Bước tiếp theo đang có sẵn: {nextStep.label}.` Expose its supplied destination as a separate existing control. |
| `ENCOURAGE` / `ACTION_SAVED` | `Thay đổi của bạn đã được lưu.` |
| `ENCOURAGE` / `EXPLICIT_COMPLETION_SAVED` | `Hoạt động đã được đánh dấu hoàn thành.` |
| `ENCOURAGE` / `REVIEW_SUBMITTED` | `Phản hồi xem lại kế hoạch đã được gửi.` |
| `ENCOURAGE` / `PREFERENCE_SAVED` | `Lựa chọn của bạn đã được lưu.` |
| `SUMMARIZE_PROGRESS`, complete evidence | `Tính đến {progress.evidenceAsOf}, bạn đã hoàn thành {progress.completedCount} trong {progress.availableCount} hoạt động có dữ liệu cho khoảng thời gian này.` |
| `SUMMARIZE_PROGRESS`, partial evidence | Use the complete-evidence sentence, then append `Một số nguồn hiện chưa có dữ liệu, nên đây là bản tóm tắt chưa đầy đủ.` |
| `EXPLAIN_REVIEW_OPTION` | `Kết quả xem lại hiện có {review.options.length} lựa chọn phù hợp để bạn tự quyết định.` If evidence is contradictory, append `Các nguồn hiện chưa cùng hướng, nên hệ thống giữ riêng từng nguồn để bạn xem xét.` |
| `NAVIGATE` | `Bạn có thể tiếp tục tại “{navigation.label}”.` Expose the supplied destination as a separate existing control. |

Dates/times are localized by the client from the exact timestamp; numeric
counts remain numeric facts. Templates never convert a due state into a
completion claim or turn missing evidence into zero activity. Invalid
relational facts, unsupported locale or unsafe interpolation produce silence
and the normal SupportPlan UI, not best-effort generated copy.

## Accessibility and motion

- All guidance is available as text; color, pose, sound and movement are never
  the only signal.
- Controls have accessible names, logical focus order and at least 44 by 44 px
  touch targets.
- Polite live announcements occur only for a new requested response. Muting
  suppresses companion sound/live prompting without hiding authoritative plan
  feedback.
- `prefers-reduced-motion` and explicit reduced-motion preference remove
  repeated orbit/bounce/width transitions and keep a static state equivalent.
- Content reflow must not cover the source action or shift focus. Desktop,
  375 px mobile and 768 px tablet remain functional.
- Clients use the existing product typeface and must verify its Vietnamese
  Latin Extended glyph coverage for every fallback/state label. Missing glyph,
  clipped diacritic, fallback-font layout shift and icon-font substitution are
  release failures; text remains readable at 200% zoom/font scaling.
- The companion can be disabled or fail entirely without degrading Today,
  Plan, Progress or Adjust.

## Retention and telemetry

Contextual bubble/tray requests are ephemeral and are not persisted as
SupportPlan evidence or history. An expanded conversation may use the existing
Journal/AI encrypted conversation store only after the user explicitly opens
it and satisfies current AI-processing consent; its existing deletion and
retention rules apply. SupportPlan does not store generated/user message text.

Allowed telemetry is limited to intent, surface, contract/prompt/provider
version, outcome code, latency, fallback reason and correlation identifiers.
It excludes context values, generated text, user text and source-domain
content. Logs use the same restriction.

## Versioning and compatibility

V1 clients reject unknown major schema versions and ignore no unknown fields;
the schemas use closed objects. Additive intent, context, retention, automatic
trigger or authority changes require v2. Copy-only changes that preserve intent
and meaning use a versioned template/prompt revision under MB-649.

The web shell delivered by MB-648 is compatible when it consumes only bounded
UI events and does not call a provider. MB-649 implements orchestration;
MB-650/MB-651 add contextual and expanded surfaces; MB-652 adds governed review
explanations; MB-653 hardens release behavior; MB-654 must reuse the same
contract on mobile.
