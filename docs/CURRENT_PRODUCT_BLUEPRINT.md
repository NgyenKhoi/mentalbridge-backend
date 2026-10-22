# MentalBridge current product blueprint

This file is the **navigation and authority map** for current MentalBridge product behavior. It intentionally does not duplicate every domain rule. Detailed behavior remains in the latest accepted ADR amendment and the current approved domain policy linked below.

## 1. Source-of-truth order

Community peer messaging has a separate **accepted target** in
[ADR 0042 / MB-COMMUNITY-CHAT-001](adr/0042-community-chat-contract.md) and
[Community chat policy v1](policies/community-chat-policy-v1.md). MB-684 supplies
privacy/permission/API/event contracts only. Approval and merge accept the target;
MB-685 delivers the opt-in direct provider in Community PostgreSQL, current
Identity eligibility and minimized Kafka facts. MB-687 adds private invite-only
groups, membership lifecycle/epochs and isolated exact-message report intake.
MB-689 adds signed current-viewer projections, isolated `/community` delivery,
ordered private delivery cursors and self read-state synchronization. MB-690
adds privacy-aware foreground presence with multi-device Redis leases and
current owner projections. MB-691 adds exact-target chat notification checks,
durable inbox delivery, unread reaction grouping, quiet-hour deferral and backend
comment reactions. Generic PUSH payloads are contracts only; MB-675/679 own real
device/provider delivery. MB-686/688 and later stories supply clients,
typing/shared receipts, case
administration and epic acceptance.

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
- [ADR 0027 — Independent Community service boundary](adr/0027-community-service-boundary.md)
- [ADR 0034 — SupportPlan v3 cross-domain actions](adr/0034-support-plan-v3-cross-domain-actions.md)
- [ADR 0040 — SupportPlan Companion product and interaction contract](adr/0040-support-plan-companion-contract.md)

The optional SupportPlan Companion is governed by `MB-SUPPORT-PLAN-COMPANION-001`
and [SupportPlan Companion policy v1](policies/support-plan-companion-policy-v1.md).
It may explain, phrase encouragement from explicit saved facts, and navigate
within Today, Plan, Progress and Adjust using a closed minimized context. Care
remains authoritative for plan facts and mutations; the companion never
diagnoses, infers completion or recovery, selects a review outcome, or acts as
a therapist. MB-649 through MB-654 own runtime and client delivery after this
contract is accepted.

The one confirmed-appointment email reminder is governed by
[ADR 0030 — One appointment email reminder](adr/0030-one-appointment-email-reminder.md)
(`MB-APPOINTMENT-EMAIL-REMINDER-001`). It fixes the exact 60-minute target,
dedicated opt-in, quiet-hour deferral/suppression, appointment/version identity,
lifecycle invalidation, strict before-start cutoff, and minimized in-app-only
content separately from the wellbeing digest.

Optional private context on the daily emotion check-in is governed by
[ADR 0035 — Optional emotion influence context](adr/0035-optional-emotion-influence-context.md)
(`MB-EMOTION-CONTEXT-001`). The stable factor list and private note remain
skippable, encrypted in the owner record, clearable, and excluded from every
downstream consumer unless a later approved minimal-data policy authorizes one.

Optional resource participation rewards are governed by
[ADR 0036 — Authoritative non-punitive resource rewards](adr/0036-authoritative-resource-rewards.md)
(`MB-RESOURCE-REWARD-001`) and
[resource reward policy v1](policies/resource-reward-policy-v1.md). Content derives
bounded XP only from exact persisted reviewed-resource evidence. Rewards remain
hideable, non-clinical, penalty-free, and unrelated to access or SupportPlan.

The private multi-domain achievement collection is governed by
[ADR 0038 — Private multi-domain achievements](adr/0038-private-multi-domain-achievements.md)
(`MB-PRIVATE-ACHIEVEMENT-001`) and the
[private achievement policy v1](policies/private-achievement-policy-v1.md).
It recognizes only minimized owner facts, keeps Journal, emotion, Resource and
Journey authority distinct, and defers Journey unlocks until an explicit
SupportPlan v3 owner-confirmation contract exists.

Appointment disputes are governed by
[ADR 0031 — Appointment dispute and settlement gate](adr/0031-appointment-dispute-settlement-gate.md)
(`MB-APPOINTMENT-DISPUTE-001`). One assigned participant may open a minimized
dispute within 24 hours of an eligible settled outcome. An open dispute blocks
future earning eligibility; resolution never rewrites prior financial facts and
any credit return is an explicit ledger adjustment.

For the rules amended on 2026-09-24, ADR 0022 is authoritative over the older clause.

## 2. Product boundary

MentalBridge is an early mental-health screening and support platform for Vietnamese users, focused on the current adult capstone cohort. It supports screening, reviewed self-help guidance, private Journal/emotion tracking, governed AI assistance, paid ongoing SupportPlan capability, online specialist consultation, and reviewed safety support.

MentalBridge does not diagnose, prescribe, provide psychotherapy as an autonomous system, infer a global recovery score, dispatch emergency responders, automatically contact third parties, or give AI authority over safety, eligibility, SupportPlan mutation, appointment completion, credits, or financial state.

ADR 0027 formally adds an independent peer-support Community context. Its ranking and filtering remain isolated from screening, SupportPlan, Journal, emotion, and AI-analysis data. MB-574 activates authenticated feed and post-detail reads; MB-575 through MB-581 add the bounded publication, interaction, moderation, notification-fact, and display-identity slices. MB-582 adds the governed active topic catalogue and explicit OR filtering across at most three selected topics while preserving historical classifications after deactivation. MB-614 allows one optional stable reference to a published MentalBridge Resource while Content remains authoritative and Community stores no Resource body, version, or clinical/support state. MB-615 adds one explicit author- or moderator-controlled `SENSITIVE_CONTENT` presentation warning; it is governance metadata, never an inferred clinical label, and does not replace hide/remove moderation. MB-616 exposes each authenticated user’s private bookmarks as a newest-saved cursor-paged collection while retaining the normal fail-closed Community visibility rules and never using saved membership for ranking or profiling.

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
      │      ├─ focuses / typed actions
      │      ↓
      │   generic schedules / occurrences / engagement
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

MB-672 adds Care's owner-only `GET /api/v1/home-next-step`, embedding the unchanged guided journey with an ordered current-plan focus and bounded completed-plan context for web/native Home; see [ADR 0044](adr/0044-home-next-step-read.md) (`MB-HOME-NEXT-STEP-001`).

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

The prospective canonical plan model is
`SupportPlan -> PlanFocus -> PlanAction -> ActionSchedule -> ActionOccurrence`.
The allowed action types are `RESOURCE`, `JOURNAL`, `EMOTION_CHECK_IN`,
`ROUTINE_SELF_MANAGEMENT`, `PROFESSIONAL_SUPPORT`,
`REASSESSMENT_SCREENING`, and `SELF_REPORT_REVIEW`. Care owns orchestration;
Content, Journal/AI, Consultation, and Care screening remain authoritative for
their source facts. Care stores only bounded references and completion evidence,
never raw Journal/emotion/assessment/consultation content.

Existing v2 resource plans remain readable and operable. The v3 read boundary
may expose a v2 slot as a virtual `RESOURCE` action marked
`V2_RESOURCE_ADAPTER`; it does not rewrite or backfill v2 rows or identities.
For a persisted professional-adjunct template family, the adapter additionally
projects a stable `PROFESSIONAL_SUPPORT` primary action and marks its self-help
resources as adjunct. Consultation appointment state may be correlated through
an opaque action reference, but cannot mutate or complete the SupportPlan.

Detailed rules: [SupportPlan policy v3](policies/support-plan-policy-v3.md).
Historical v2 behavior remains governed by
[SupportPlan policy v2](policies/support-plan-policy-v2.md).

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

[Adaptive SupportPlan review policy v1](policies/support-plan-review-v1.md),
accepted 2026-10-21 for MB-645, governs early/partial and due review, separate
sourced evidence, repeat thresholds, bounded practical options and explicit
confirmation. The existing 14-local-day/pause cycle and adjacent 14-day UTC
comparison periods remain distinct; review never expires or changes a plan
automatically. Its runtime and consumer delivery remain subject to MB-645 gates.

## 6. Specialist and consultation boundary

Specialist discovery exposes only approved/active specialists and selectable `IN_APP_CHAT` / `IN_APP_VIDEO` slots. Physical PracticeLocation, phone consultation, and external meeting links are not current scope.

Discovery uses the deterministic primary order fixed by
[ADR 0027](adr/0027-approved-online-specialist-discovery.md): minimized
screened domain/pathway compatibility, requested language, current
availability, then timezone. An authoritative rating may break a tie only for
`PREMIUM` after every primary factor ties; it is never a clinical match or sole
recommendation factor. `FREE` may browse the same approved profiles and slots
but cannot enter the booking command.

A specialist never authors or mutates the official SupportPlan. After an eligible completed appointment they may publish bounded `SessionSummary`, `AgreedNextSteps`, and an exact-version resource proposal. Care owns `PlanChangeRequest` revalidation and the user confirms any applied change.

Every appointment is exactly 60 minutes. The scheduled end produces `SESSION_ENDED`; elapsed time alone never produces `COMPLETED`, credit consumption, earning, or payout. Completion requires approved server/provider participation evidence.

Detailed rules: [Consultation and specialist policy v2](policies/consultation-specialist-policy-v2.md).

Approved-profile edits use the separate reviewed amendment direction in
[ADR 0033](adr/0033-reviewed-specialist-profile-amendment.md)
(`MB-SPECIALIST-PROFILE-AMENDMENT-001`, MB-635). Draft/review/rejection preserves
the live approved profile; only ADMIN approval promotes new public content.
Existing appointments and availability are not rewritten by normal amendments.

Specialist credential files follow [ADR 0041](adr/0041-specialist-credential-review.md)
(`MB-SPECIALIST-CREDENTIAL-001`): originals remain private, ADMIN verifies,
and only a verified, specialist-enabled badge may be public. The reviewed
language list now includes `vi`, `en`, `zh`, and `fr`. Appointment duration and
online-only modes are unchanged.

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

ADR 0032 fixes planned production design values at 1,390,000 VND for `PLUS`
and 3,490,000 VND for `PREMIUM`, with 300,000 VND allocated per consumed credit
and a 210,000 VND specialist earning. These are not runtime prices. The current
integration-test catalogue is only 0/5,000/10,000 VND, and real payment/payout
remains disabled pending explicit production approval and credentials.

The owner-approved MB-622 sandbox amendment to ADR 0032 makes new test upgrades
charge the full 10,000 VND PREMIUM price with zero offset under the explicit
`sandbox-full-price-v1` policy. Existing ordinary offsets and historical facts
are preserved. Sandbox allocations/earnings are synthetic and cannot be paid
through a real provider.

Detailed amendment: [ADR 0022](adr/0022-current-product-blueprint-amendments.md).

## 8. Safety boundary

Safety activates when either:

- PHQ-9 item 9 is positive under the versioned deterministic rule; or
- the user explicitly selects “Tôi cần hỗ trợ ngay”.

A `High` or `Severe` screening band alone is not a safety trigger.

The global anonymous crisis-support panel exposes the reviewed national `112`
and `115` contacts before any area selection. The user must deliberately select
`Gọi`; MentalBridge never starts a call automatically. Area-specific results
still require a manually chosen coarse area. MentalBridge does not infer precise
location, dispatch, contact a third party, auto-book a specialist, or send an
automatic safety email.

Safety is independent of package, AI availability, consultation credits, and specialist availability.

Detailed national-contact amendment: [ADR 0024](adr/0024-global-anonymous-crisis-support.md).

## 9. Owner boundaries

- **Care**: screening, safety triggers, SupportEvaluation, Support Guide, SupportPlan and its focuses/actions/schedules/occurrences, reassessment composition, eligibility revalidation, PlanChangeRequest decision, user-confirmed plan mutation.
- **Content/Notification**: reviewed resource catalogue, exact-version resource eligibility and progress, owner-scoped resource bookmarks, persisted weekly resource Bingo with separate self-confirmed and verified evidence, safety-directory content/provenance, reminder preferences/scheduling/delivery attempts.
- MB-670 ([ADR 0043](adr/0043-plan-scoped-resource-progress.md)): Care supplies current plan RESOURCE actions; Content syncs plan-scoped Bingo and exact-version facts; Care alone reconciles those facts into occurrence completion.
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
- the MB-363 Consultation owner now implements approved-only discovery,
  deterministic explanations, current selectable-slot projection, and exact
  MB-378 handoff; its frontend/BFF consumer now provides list, filter, detail,
  explanation, slot selection, and truthful plan states. The remaining
  session/payment/payout journeys remain gated by their Jira delivery chain.

As of the MB-637 decision gate, `MB-SUPPORT-PLAN-V3-ACTIONS-001` is the accepted
prospective SupportPlan model. MB-639 owns native v3 persistence/API/event
delivery and the explicit v2 adapter. Until that runtime is present, v2 remains
the executable plan contract and must not be silently dual-written or migrated.

## 11. Historical documents

Historical ADRs, SRS drafts, evidence files, and older policy versions remain for provenance. They must not be used to override a newer explicit amendment.

When citing an ADR with a duplicated numeric prefix, use its filename and Decision ID rather than only the number. See [ADR register](adr/README.md).
