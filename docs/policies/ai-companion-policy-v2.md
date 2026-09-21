# AI Companion policy v2

## Policy metadata

| Field | Value |
| --- | --- |
| Scope decision | `MB-SCOPE-V2-001` |
| Status | `PRODUCT POLICY APPROVED; EXACT-REVISION BACKEND IMPLEMENTED; UI/REAL PROVIDER NOT ENABLED` |
| Effective decision date | 2026-09-15 |
| AI owner | Journal/AI |
| Care-decision, safety, and consent owner | Care |
| Reminder scheduling owner | Content/Notification |
| Decision | [ADR 0017](../adr/0017-product-scope-v2.md) |
| Amends | [AI Companion policy v1](ai-companion-policy-v1.md) |

The v1 exact-source consent, one-provider-per-run, bounded retry, normalized
result, provenance, deletion coupling, and no-hidden-reasoning rules remain in
force.

## Governing invariant

```text
AI understands and supports -> Care decides -> User confirms
```

AI may explain approved content, guide an approved activity, accompany an
active SupportPlan, surface bounded reassessment context, and phrase an
approved reminder. It may open a governed review flow but never performs the
business action.

AI does not score PHQ-9/GAD-7, diagnose, decide safety, determine clinical
improvement, decide entitlement or resource eligibility, create or mutate a
SupportPlan, schedule reminders, complete an appointment, consume credits, or
create earnings.

## Package behavior

| Package | AI behavior |
| --- | --- |
| `FREE` | Default limit of five successfully delivered assistant responses per day |
| `PLUS` | Higher versioned quota; it may use the same model as `FREE` |
| `PREMIUM` | No daily response limit displayed; a stronger configured model may be used |

Every package remains subject to server-side token, request-rate, abuse, cost,
and fair-use limits. “No displayed daily response limit” is not an unlimited
provider-capacity guarantee. Exact counting, reset window, token budget, rate,
and fair-use values belong to a versioned quota policy and server-side
configuration, not client claims.

For `EXACT_REVISION` and `LONGITUDINAL`, Consultation supplies the current
server-authoritative package through an authenticated REST decision. Journal/AI
does not infer the package from JWT claims or accept it from a client. The first
attempt snapshots one versioned route and retries must use that same
provider/model; entitlement uncertainty or a route-changing entitlement update
stops the provider call. There is no automatic cross-provider fallback.

## SupportPlan and reassessment

AI may use only current authorized, minimized plan context. It can explain an
unfinished resource, guide its approved exercise, or surface preferences,
barriers, recurring context, and helpful patterns. Care selects eligible
alternatives and owns the four separate reassessment dimensions. The user
confirms every `PlanChangeRequest` or other applied plan change.

## Reminders

AI may turn approved structured reminder facts into supportive wording. The
deterministic scheduler decides selection, timing, deduplication, quiet-hour
handling, and delivery eligibility. AI cannot create extra reminders, select a
safety escalation, or convert a safety signal into email or third-party
notification.

The default wellbeing flow sends at most one digest per user per day and may
combine unfinished plan resources, Journal, and emotion check-in prompts. An
individual-resource reminder requires explicit user opt-in. Appointment
reminders are separate and sent once approximately one hour before start.

## Runtime gates

Runtime requires a versioned chat/quota contract, entitlement checks,
server-side quota enforcement, approved model routing, SupportPlan and reminder
context minimization, deterministic provider fakes, and tests for quota races,
retry/idempotency, consent revocation, prompt injection, provider failure, and
attempted business-state mutation.

MB-367 implements the consented exact-revision backend with MongoDB jobs,
normalized results, and a deterministic fake provider. MB-371 implements the
bounded exact-source longitudinal backend, conservative coverage policy,
deletion coupling, and minimized current-consent Care projection; Care
Reassessment Summary composition and its frontend remain Story 6501. Chat/quota
and SupportPlan/reminder accompaniment remain behind their separate gates.
MB-369 implements the entitlement-aware router, structured
Gemini/OpenAI adapters, and synthetic benchmark harness, but does not activate
a real route until that pinned candidate passes its separately reviewed
benchmark gate and an approval version plus its credential are configured.
Benchmarking one provider does not require credentials for an unconfigured
provider.
