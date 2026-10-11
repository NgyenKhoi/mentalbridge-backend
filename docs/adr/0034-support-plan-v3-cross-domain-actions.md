# SupportPlan v3 cross-domain actions

- Decision ID: `MB-SUPPORT-PLAN-V3-ACTIONS-001`
- Status: Accepted
- Accepted: 2026-10-11
- Authority: MentalBridge frontend PR #129 merged and Jira MB-637 completed
- Owner: Care
- Runtime delivery: MB-639
- Amends: SupportPlan portions of `MB-SCOPE-V2-001` and `MB-SCOPE-V2-002`

## Context

The v2 SupportPlan models an ongoing plan as resource slots. That contract is a
supported runtime and historical contract, but Resource cannot be the parent
abstraction for Journal, emotion check-in, professional-support, reassessment,
or user-authored routine behavior.

MB-637 fixed the product language and boundary. This ADR records that accepted
decision in the backend source-of-truth chain. It does not reinterpret the
frontend workspace or its tabs as domain authority.

## Decision

Support Guide and SupportPlan serve different jobs:

- **Support Guide** is immutable guidance for one screening context. It may
  propose policy-approved next steps, but it has no commitment, lifecycle,
  schedule, occurrence, or engagement state.
- **SupportPlan** is the user-controlled longitudinal container for focuses,
  typed actions, optional schedules, occurrence history, lifecycle, and
  revisions. Adding or changing an action requires an authorized Care command
  and explicit user intent.

The canonical relationship is:

```text
SupportPlan -> PlanFocus -> PlanAction -> ActionSchedule -> ActionOccurrence
```

- **PlanFocus** is a plain-language area the user chooses to work on and why it
  matters. It is not a diagnosis, score, or clinical outcome.
- **PlanAction** is a typed intention within one focus. It can hold a bounded
  reference to a source-domain fact; it is not a Resource subtype.
- **ActionSchedule** is an optional rule for future opportunities or reminders,
  including timezone. A schedule edit affects future occurrences only.
- **ActionOccurrence** is one dated instance. Completion or skip facts and
  accepted evidence are historical facts; an open past-due occurrence is shown
  as overdue and is not silently converted into failure.

## Action taxonomy and authority

| Action type | Intent | Authoritative source/evidence | Completion mode |
| --- | --- | --- | --- |
| `RESOURCE` | Read, watch, or practise reviewed content | Content owns the exact resource version and publication eligibility | Explicit user acknowledgement; opening content is not completion |
| `JOURNAL` | Create a reflective journal entry | Journal owns the entry fact and occurrence time | Bounded external fact; no manual completion while linked |
| `EMOTION_CHECK_IN` | Record a self-reported emotion check-in | Journal/AI's emotion capability owns the check-in fact | Bounded external fact; no manual completion while linked |
| `ROUTINE_SELF_MANAGEMENT` | Carry out a user-approved everyday wellbeing routine | Care owns the plan wording and manual evidence | Explicit user acknowledgement |
| `PROFESSIONAL_SUPPORT` | Reach or attend an appropriate professional-support pathway | Consultation owns appointment/service facts | External fulfilment; reminder dismissal is not attendance |
| `REASSESSMENT_SCREENING` | Complete a policy-appropriate reassessment | Care screening owns questionnaire and assessment history | External Care screening fact; no copied answers or raw score |
| `SELF_REPORT_REVIEW` | Review the plan and optionally record a reflection or adjustment choice | Care owns the explicit review event | Explicit user submission; review-only and never an outcome score |

All seven types may have future schedule behavior. Professional-support and
reassessment schedules are reminders only; they do not change the underlying
appointment or assessment. Linked Journal, emotion, professional-support, and
screening actions are satisfied only by the authoritative owner fact.

## Care-owned envelope

Care may persist only the bounded orchestration data needed to operate and
audit a plan:

- plan, focus, action, schedule, occurrence, and revision identities;
- action type, order, lifecycle, bounded labels/rationale/instructions, and
  presentation metadata;
- schedule rule, timezone, boundaries, and future reminder settings;
- source owner, opaque source ID, and optional source revision/policy version;
- provenance category (`USER_SELECTED`, `SUPPORT_GUIDE`, `SPECIALIST`, or
  `SYSTEM_POLICY`) and its bounded policy/source reference;
- occurrence state, timestamp, evidence kind, and bounded external evidence
  reference; and
- actor, consent, and audit metadata required to prove acceptance or change.

Action type and source identity become immutable on activation. Changing either
ends the old action and creates a new action in a later plan revision. Labels,
rationale, order, and future schedules change only through a versioned plan
command; prior occurrences never move to the replacement action.

Care validates the allowed action-type/source-owner combination, source
reference shape, plan ownership, and evidence authority. Unknown action types,
malformed references, client-asserted external facts, and cross-owner source or
evidence injection fail closed.

## Ownership and privacy

Care owns SupportPlan orchestration, revision/lifecycle, user plan intent, and
review events. Content, Journal/AI, Consultation, and Care screening retain
their own facts and authorization rules. Integration uses versioned contracts;
Care never reads another owner's storage.

Care does not copy journal content, AI reflection, emotion values or notes,
assessment answers or raw scores, appointment notes or messages, entitlement
detail, or other source-domain payloads into SupportPlan. AI may explain or
propose, but it cannot create, activate, reorder, complete, skip, replace, or
delete an action without explicit auditable user confirmation.

Professional-support routing remains non-diagnostic. When current routing
policy requires professional support, it is the primary path and self-help is
only complementary. Urgent and crisis guidance always precedes plan controls.

## v2 compatibility

Compatibility is an explicit read-model adapter, not destructive migration or
silent dual-write:

1. Existing v2 plans, slots, schedules, occurrences, engagement, lifecycle,
   and history remain readable and operable under their versioned contract.
2. Existing v2 rows and identities are never rewritten into generated v3 rows.
3. A v3 read may project a v2 slot as a virtual `RESOURCE` action, its selected
   resource as the Content source, and its schedule/occurrences as virtual
   action history.
4. The adapter groups selected slots by their exact stored `targetDomain` into
   deterministic virtual focus IDs. It uses stable compatibility-neutral copy
   (`Hỗ trợ tâm trạng` or `Hỗ trợ bình tâm`) instead of exposing the internal
   enum as a title. The stored plan rationale is adapter context; neither title
   nor rationale is presented as a user-authored focus, diagnosis, or newly
   inferred clinical claim.
5. Every such projection is marked `V2_RESOURCE_ADAPTER` and keeps the exact
   ResourceVersion/publication provenance.
6. Native v3 actions use their own versioned model and do not make the adapter
   projection canonical persistence.

A later materialized migration requires a separate accepted, auditable plan.
Until then, read-model translation is the default compatibility mechanism.

## Consequences

- Occurrence generation can apply one generic scheduling model to every
  schedulable action without a Resource-specific fork.
- Completion contracts distinguish Care orchestration from authoritative
  external evidence.
- Cross-domain actions can be added without copying source payloads into Care.
- v2 clients and histories remain stable while v3 consumers adopt the additive
  contract deliberately.
- Completion, skips, streaks, and reminders cannot be presented as adherence,
  treatment success, recovery, diagnosis, or an overall wellbeing score.
