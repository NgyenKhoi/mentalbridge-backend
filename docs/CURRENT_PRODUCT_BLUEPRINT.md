# MentalBridge current product blueprint

This file is the **navigation and authority map** for current MentalBridge product behavior. It intentionally does not duplicate every domain rule. Detailed behavior remains in the latest accepted ADR amendment and the current approved domain policy linked below.

## 1. Source-of-truth order

For product/business behavior, use this order:

1. the latest accepted product-scope ADR or explicit amendment that covers the rule;
2. the current approved domain policy implementing that decision;
3. versioned API/event contracts and owner migrations;
4. current domain/architecture documentation;
5. owner-service implementation and tests;
6. Jira delivery state and task text.

Jira tells the team **what is being delivered and whether it is done**. Jira does not redefine approved business behavior.

When an older ADR or historical runtime policy is retained for provenance, the newer explicit amendment wins prospectively. Historical records keep the policy/version under which they were created.

Current cross-feature scope is governed by:

- [ADR 0017 — Product scope v2](adr/0017-product-scope-v2.md)
- [ADR 0022 — Current product blueprint amendments](adr/0022-current-product-blueprint-amendments.md)

For the rules amended on 2026-09-24, ADR 0022 is authoritative over the older clause.

## 2. Product boundary

MentalBridge is an early mental-health screening and support platform for Vietnamese users, focused on the current adult capstone cohort. It supports screening, reviewed self-help guidance, private Journal/emotion tracking, governed AI assistance, paid ongoing SupportPlan capability, online specialist consultation, and reviewed safety support.

MentalBridge does not diagnose, prescribe, provide psychotherapy as an autonomous system, infer a global recovery score, dispatch emergency responders, automatically contact third parties, or give AI authority over safety, eligibility, SupportPlan mutation, appointment completion, credits, or financial state.

The governing AI invariant is:

```text
AI understands and supports -> Care decides -> User confirms
```

## 3. Canonical user journey

```text
Profile / consent
      ↓
PHQ-9 + GAD-7 screening
      ↓
Care SupportEvaluation
      ├──────────────→ Safety flow when item-9 positive
      │                 or user selects “Tôi cần hỗ trợ ngay”
      ↓
Persisted Support Guide
      ├─ reviewed resources
      ├─ Journal
      ├─ emotion check-in
      ├─ AI Companion
      └─ specialist discovery

FREE stops before paid SupportPlan/consultation booking.

PLUS / PREMIUM
      ├─ persistent SupportPlan
      │      ↓
      │   schedules / occurrences / engagement
      │      ↓
      │   reassessment
      │      ↓
      │   Care revalidation
      │      ↓
      │   continue / keep / replace as allowed
      │
      └─ online specialist consultation
             ↓
          request + hold slot/credit
             ↓
          specialist decision
             ↓
          ConsultationBrief
             ↓
          60-minute in-app chat/video
             ↓
          SESSION_ENDED
             ↓
          participation evidence
             ↓
          COMPLETED when evidence passes
             ↓
          SessionSummary / AgreedNextSteps
             ↓
          optional resource proposal
             ↓
          Care PlanChangeRequest
             ↓
          user-confirmed SupportPlan change
```

## 4. Support Guide versus SupportPlan

The current distinction is mandatory:

| Capability | Support Guide | SupportPlan |
| --- | --- | --- |
| Packages | `FREE`, `PLUS`, `PREMIUM` | `PLUS`, `PREMIUM` |
| Persistence | persisted immutable historical snapshot | persisted ongoing aggregate |
| One-time meaning | one guidance result for one screening context | not applicable |
| Automatic expiry | none unless a separate retention/deletion policy applies | governed by lifecycle, not TTL |
| Official current state | none | at most one official current `ACTIVE`/`PAUSED` plan |
| Lifecycle | none | draft/activation/pause/resume/complete/replacement/history |
| Schedule / occurrence tracking | none | yes |
| Engagement / helpfulness | not Guide lifecycle | occurrence-scoped evidence |

Opening a Support Guide resource never turns the Guide into an activity checklist or a free SupportPlan.

Detailed rules: [SupportPlan policy v2](policies/support-plan-policy-v2.md).

## 5. Reassessment

The four canonical dimensions are separate:

1. **Screening change** — deterministic PHQ-9/GAD-7 comparison.
2. **Journal context** — bounded longitudinal context with explicit coverage.
3. **Plan engagement** — factual occurrence engagement such as completion/skip/barrier.
4. **Self-reported experience** — explicit reassessment input authored by the user.

Activity helpfulness/reflection may support dimension 4 but cannot replace the explicit reassessment self-report.

No combined recovery score or overall clinical-improvement verdict is allowed. Contradictory dimensions remain visible.

Reassessment never changes a SupportPlan automatically. After fresh Care revalidation:

- `CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE` -> continue current plan;
- `CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE` -> user may keep or replace;
- `CURRENT_PLAN_NOT_ADMISSIBLE` -> current plan is not offered as a normal keep choice; present an admissible replacement path.

Historical MB-386 v1 snapshots remain a compatibility baseline. MB-559 implements the explicit owner-authored self-report and canonical v2 composition without rewriting those records.

## 6. Specialist and consultation boundary

Specialist discovery exposes only approved/active specialists and selectable `IN_APP_CHAT` / `IN_APP_VIDEO` slots. Physical PracticeLocation, phone consultation, and external meeting links are not current scope.

A specialist never authors or mutates the official SupportPlan. After an eligible completed appointment they may publish bounded `SessionSummary`, `AgreedNextSteps`, and an exact-version resource proposal. Care owns `PlanChangeRequest` revalidation and the user confirms any applied change.

Every appointment is exactly 60 minutes. The scheduled end produces `SESSION_ENDED`; elapsed time alone never produces `COMPLETED`, credit consumption, earning, or payout. Completion requires approved server/provider participation evidence.

Detailed rules: [Consultation and specialist policy v2](policies/consultation-specialist-policy-v2.md).

## 7. Service plans and consultation credits

Current target entitlement for new `consultation-credit-v2` plan periods:

| Package | Consultation credits / paid period | Rollover | Max active appointment reservations |
| --- | ---: | --- | ---: |
| `FREE` | 0 | none | 0 |
| `PLUS` | 4 | no rollover | 2 |
| `PREMIUM` | 10 | no rollover | 4 |

Active reservation capacity counts `REQUESTED`, `CONFIRMED`, and `IN_PROGRESS` or exact equivalent reserving states. It does not count `SESSION_ENDED` or terminal/non-reserving states.

Credit balance and reservation capacity are separate checks. A request requires both an `AVAILABLE` credit and reservation capacity.

Historical `consultation-credit-v1` periods remain `0/1/3`; they are not rewritten.

Commercial direction remains:

- `FREE -> PLUS` or `FREE -> PREMIUM` purchase;
- `PLUS -> PREMIUM` upgrade;
- no user downgrade API in V1;
- no user refund API in V1;
- VND + MoMo for real payment/payout;
- specialist earning = 70% of the consumed credit's snapshotted `creditAllocation`, not 70% of package price.

Detailed amendment: [ADR 0022](adr/0022-current-product-blueprint-amendments.md).

## 8. Safety boundary

Safety activates when either:

- PHQ-9 item 9 is positive under the versioned deterministic rule; or
- the user explicitly selects “Tôi cần hỗ trợ ngay”.

A `High` or `Severe` screening band alone is not a safety trigger.

The user manually chooses an area. MentalBridge does not infer precise location, automatically call, dispatch, contact a third party, auto-book a specialist, or send an automatic safety email.

Safety is independent of package, AI availability, consultation credits, and specialist availability.

## 9. Owner boundaries

- **Care**: screening, safety triggers, SupportEvaluation, Support Guide, SupportPlan, reassessment composition, eligibility revalidation, PlanChangeRequest decision, user-confirmed plan mutation.
- **Content/Notification**: reviewed resource catalogue, exact-version resource eligibility, safety-directory content/provenance, reminder preferences/scheduling/delivery attempts.
- **Consultation/Billing**: specialist profile/approval/availability/discovery, service-plan entitlement, consultation-credit ledger, appointments, evidence-backed completion, SessionSummary/AgreedNextSteps persistence, earnings and payout.
- **Realtime**: in-app chat transport/delivery under Consultation appointment eligibility.
- **Journal/AI**: private Journal, AI analyses/conversations, model execution and minimized projections; no Care/financial authority.

Owners communicate through versioned contracts and never read another owner's storage.

## 10. Runtime truth versus target truth

An accepted ADR/policy can approve a target before runtime delivery is complete. Documentation must distinguish:

- **approved target** — business rule is decided;
- **implemented runtime** — owner contract/persistence/code exist on `dev`;
- **consumer-ready** — dependent services/frontend are integrated;
- **live/provider-ready** — required real credentials/provider gates have passed.

Do not claim a target as executable merely because it appears in this blueprint.

As of the ADR 0022 amendment:

- Support Guide persistence/history and SupportPlan draft/activation/lifecycle/scheduling/engagement foundations exist on `dev`;
- MB-559 completes canonical reassessment composition with a versioned explicit self-report while preserving MB-386 v1 history;
- `consultation-credit-v1` periods remain historical `0/1/3`; MB-558 implements new `consultation-credit-v2` `0/4/10` periods, no rollover, separate `0/2/4` reservation caps, and atomic replacement requests;
- specialist discovery and the full appointment/session/payment/payout journeys remain gated by their Jira delivery chain.

## 11. Historical documents

Historical ADRs, SRS drafts, evidence files, and older policy versions remain for provenance. They must not be used to override a newer explicit amendment.

When citing an ADR with a duplicated numeric prefix, use its filename and Decision ID rather than only the number. See [ADR register](adr/README.md).
