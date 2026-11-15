# AI Companion policy v2

Current AI authorization is prospectively amended by
[ADR 0050 / MB-TWO-LEVEL-AI-CONSENT-001](../adr/0050-two-level-ai-consent.md)
and [Care consent policy 1.2-capstone](care-consent-and-retention-policy.md).
Existing current `AI_PROCESSING` v2 is L1 only; personal sources, analysis and
accompaniment require the independent dependent `AI_CONTEXT_PROCESSING` v1 L2.
Voice retains L1 plus unchanged Voice v2 and bounded empty-kind Text handoff.
Historical policy snapshots and notices below remain unchanged.


## Policy metadata

| Field | Value |
| --- | --- |
| Scope decisions | `MB-SCOPE-V2-001`, amended by `MB-SCOPE-V2-002` |
| Status | `PRODUCT POLICY APPROVED; CHAT/QUOTA AND EXACT-REVISION RUNTIMES IMPLEMENTED; REAL PROVIDER ROUTE REQUIRES APPROVAL` |
| Effective decision date | 2026-09-24 for ADR 0022 amendments |
| AI owner | Journal/AI |
| Care-decision, safety, and consent owner | Care |
| Reminder scheduling owner | Content/Notification |
| Base decision | [ADR 0017](../adr/0017-product-scope-v2.md) |
| Current amendment | [ADR 0022](../adr/0022-current-product-blueprint-amendments.md) |
| Amends | [AI Companion policy v1](ai-companion-policy-v1.md) |
| Prospective TEXT amendment | [ADR 0049](../adr/0049-ai-companion-friend-voice-and-suggestions.md) (`MB-AI-COMPANION-FRIEND-001`, flag-gated) |

The v1 exact-source consent, one-provider-per-run, bounded retry, normalized
result, provenance, deletion coupling, and no-hidden-reasoning rules remain in
force.

For synchronous TEXT companion sends only, at most two calls to the same pinned
provider/model are permitted. The second call may retry HTTP 429, provider 5xx,
or transport/timeout, or repair output that fails parsing, schema, or existing
message validation. These share one extra attempt; no rule is relaxed and no
cross-provider fallback occurs. Each call is capped at 10,000 ms and the whole
send at 24,000 ms; the second call is skipped when less than 3,000 ms remain.
Attempted token use is charged to the daily ledger as reported by the provider.
When no object response body is parsed, only the attempted input estimate is
charged; a parsed object without usage is charged estimated input and output,
including rejected output. Only a committed assistant reply consumes an answer
slot.
Voice and analysis are unchanged.

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
SupportPlan, provide the user's explicit reassessment self-report, schedule
reminders, complete an appointment, consume credits, or create earnings.

Under ADR 0049, offering a published reviewed Resource or an app area as a
server-resolved suggestion is not deciding resource eligibility or package
access; the destination page performs its own checks and the user decides
whether to open it.

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
barriers, recurring context, and helpful patterns.

Care owns the four separate ReassessmentSummary dimensions:

1. deterministic Screening change;
2. bounded Journal context;
3. factual Plan engagement; and
4. explicit Self-reported experience authored by the user during reassessment.

AI-derived Journal context is evidence for dimension 2 only. Activity
helpfulness/reflection may be surfaced as approved supporting evidence for
dimension 4, but AI must not infer, fabricate, summarize into existence, or
replace the user's explicit reassessment self-report.

The dimensions may contradict each other and must not be collapsed into one
improvement/recovery verdict. Care selects eligible plan alternatives and owns
fresh plan revalidation. The user confirms every `PlanChangeRequest` or other
applied plan change.

AI may explain the three Care-owned plan-review outcomes but does not decide
which outcome applies:

- current plan valid with no better alternative;
- current plan valid with alternatives available; or
- current plan not admissible under current policy.

## Reminders

AI may turn approved structured reminder facts into supportive wording. The
deterministic scheduler decides selection, timing, deduplication, quiet-hour
handling, and delivery eligibility. AI cannot create extra reminders, select a
safety escalation, or convert a safety signal into email or third-party
notification.

The default wellbeing flow sends at most one digest per user per day and may
combine unfinished plan resources, Journal, and emotion check-in prompts. An
individual-resource reminder requires explicit user opt-in. Appointment
email reminders are separate and, under
`MB-APPOINTMENT-EMAIL-REMINDER-001`, target exactly 60 minutes before start
with deterministic preference, quiet-hour, lifecycle, and before-start rules.
AI does not select their recipient, timing, suppression, or retry outcome.

## Spoken friend-persona opt-in (MB-791 F1)

The independent `JOURNAL_AI_VOICE_FRIEND_ENABLED` flag defaults off. When
explicitly enabled after an approved real-Voice tone review, spoken guidance
comforts first, offers advice only when requested and avoids repetitive questions,
emoji, markup, model-authored links and navigation suggestions. It never invents
personal facts or takes over clinical, safety, eligibility or business decisions.
Urgent cues retain serious Help Now guidance.

This changes wording only. Existing dual consent and the current-call/explicit
Text-handoff scope remain authoritative; no other saved history or catalogue is
added to Voice. Exact input counting must authorize at most 1,900 tokens before
inference. Existing quota, deadlines, provider pins, structured response, output
caps and END_TURN completion validation remain enforced. TEXT friend approval
does not by itself authorize Voice activation. See the
[ADR 0049 F1 amendment](../adr/0049-ai-companion-friend-voice-and-suggestions.md).

## Runtime gates

Runtime requires a versioned chat/quota contract, entitlement checks,
server-side quota enforcement, approved model routing, SupportPlan and reminder
context minimization, deterministic provider fakes, and tests for quota races,
retry/idempotency, consent revocation, prompt injection, provider failure, and
attempted business-state mutation.

MB-512 implements the consent-gated conversation runtime, encrypted history,
server-authoritative plan quotas, minimized selected Journal/longitudinal/current
SupportPlan context, hard deletion, and same-origin frontend flow under the AI
conversation ADR. Reminder accompaniment remains unavailable until
Content/Notification publishes an approved owner contract.

MB-367 implements the consented exact-revision backend with MongoDB jobs,
normalized results, and a deterministic fake provider. MB-371 implements the
bounded exact-source longitudinal backend, conservative coverage policy,
deletion coupling, and minimized current-consent Care projection.

MB-386 implements the pre-ADR-0022 ReassessmentSummary composition baseline.
Its activity helpfulness/reflection-derived fourth dimension remains readable
as historical runtime provenance, but the canonical target requires the
explicit user reassessment self-report from MB-559 or equivalent delivery.

MB-369 implements the entitlement-aware router, structured Gemini/OpenAI
adapters, and synthetic benchmark harness, but does not activate a real route
until that pinned candidate passes its separately reviewed benchmark gate and
an approval version plus its credential are configured. Benchmarking one
provider does not require credentials for an unconfigured provider.
