# SupportPlan policy v3

## Policy metadata

| Field | Value |
| --- | --- |
| Decision | `MB-SUPPORT-PLAN-V3-ACTIONS-001` |
| Status | Product policy approved; native v3 runtime delivery governed by MB-639 |
| Effective decision date | 2026-10-11 |
| Owner | Care |
| Applies to | Registered `PLUS` and `PREMIUM` users with an admitted Care plan context |
| Decision record | [ADR 0034](../adr/0034-support-plan-v3-cross-domain-actions.md) |
| Amends | [SupportPlan policy v2](support-plan-policy-v2.md) prospectively; v2 history and contracts remain supported |

This policy retains the v2 single-official-plan, explicit user confirmation,
lifecycle/history, entitlement, safety-priority, reassessment, and Care-owner
rules except where it replaces Resource-slot composition with typed actions.

## Guide and plan boundary

A Support Guide remains an immutable answer to “what should I do next?” for one
screening context. It can propose a next step, but does not create a commitment,
schedule, occurrence, completion, or SupportPlan mutation.

A SupportPlan is the user's versioned, longitudinal answer to “what will I do,
why, and when will I review it?” Care owns its orchestration and keeps at most
one official current `ACTIVE`/`PAUSED` plan. The user explicitly accepts every
plan or action change admitted by Care. A proposal, AI output, source-domain
event, reminder, or Support Guide link never mutates the plan by itself.

## Canonical aggregate

```text
SupportPlan -> PlanFocus -> PlanAction -> ActionSchedule -> ActionOccurrence
```

- A PlanFocus is ordered, versioned, non-diagnostic user-facing intent.
- A PlanAction belongs to exactly one focus and carries one known action type,
  presentation metadata, provenance, and a bounded source reference where its
  type requires one.
- An ActionSchedule optionally defines future occurrences using a supported
  recurrence, local time, IANA timezone, and bounded start/end dates.
- An ActionOccurrence belongs to one action and schedule revision. It retains
  the exact action/source/provenance snapshot needed to understand history.

Action type and source identity are immutable after activation. Replacing them
creates a new action in a later plan revision and ends future generation for the
old action. Editing wording, order, rationale, instructions, or a schedule is a
versioned change that affects future behavior only.

Ending an action is also a versioned SupportPlan change: Care advances the
parent plan revision and validator in the same transaction when the nested
action or schedule representation changes. An unchanged retry is a no-op, and
a terminal plan accepts no further action lifecycle mutation.

## Allowed action types

| Type | Source authority | Schedule and completion policy |
| --- | --- | --- |
| `RESOURCE` | Content exact ResourceVersion and publication eligibility | Schedulable; user completes/skips; opening content is not completion |
| `JOURNAL` | Journal entry/capability facts | Schedulable and skippable; a linked entry is completed only by a bounded Journal fact |
| `EMOTION_CHECK_IN` | Journal/AI emotion check-in facts | Schedulable and skippable; completed only by a bounded check-in fact |
| `ROUTINE_SELF_MANAGEMENT` | Care-owned approved wording | Schedulable; user completes/skips explicitly |
| `PROFESSIONAL_SUPPORT` | Consultation service/appointment facts | Reminder is schedulable/skippable; fulfilment comes only from Consultation |
| `REASSESSMENT_SCREENING` | Care questionnaire/assessment history | Reminder is schedulable/skippable; fulfilment comes only from screening history |
| `SELF_REPORT_REVIEW` | Care review event | Schedulable; user submits or skips; review-only with no derived outcome score |

Unknown action types fail closed. An action/source pair must match the authority
in this table. Source IDs are opaque, bounded, well-formed references; they do
not authorize access and cannot be used to inject a fact owned by another user.

## Source and provenance

Every native action records:

- the action model/policy version and canonical type;
- source owner, opaque source ID, and optional exact source revision or policy
  version when applicable;
- one provenance category: `USER_SELECTED`, `SUPPORT_GUIDE`, `SPECIALIST`, or
  `SYSTEM_POLICY`, plus the bounded originating reference;
- bounded label, rationale, instructions, order, and presentation metadata; and
- actor and acceptance provenance for the plan revision.

`RESOURCE` always retains the exact ResourceVersion and publication eligibility
used for admission. Current Content state may block a future change without
rewriting the historical action or occurrence snapshot.

A specialist may propose only through an authorized bounded handoff. Care
validates ownership, current plan/version, entitlement, policy compatibility,
and source authority before presenting a user-confirmable action. A specialist
does not acquire direct plan mutation authority.

## Generic scheduling and occurrences

The occurrence generator operates on schedule capability and policy, not on a
Resource-specific branch. A schedulable native action produces deterministic,
idempotent occurrences from its action ID, schedule revision, and local date or
equivalent schedule intent.

Persisted occurrence state distinguishes an open occurrence, user skip,
accepted completion evidence, and lifecycle cancellation. “Overdue” is a read
projection for an open past-due occurrence; time alone never writes failure,
skip, or completion. Pausing, completing, replacing, or superseding a plan may
cancel future open occurrences under the existing owner lifecycle rules but
never rewrites terminal occurrence evidence.

Skipping records only the user's choice for that occurrence. It does not cancel
an appointment, delete a Journal entry, revoke consent, alter an assessment,
or mark the plan unsuccessful.

## Completion evidence

Completion evidence is one of:

- Care-owned explicit user acknowledgement for `RESOURCE` or
  `ROUTINE_SELF_MANAGEMENT`;
- a Care-owned explicit review submission for `SELF_REPORT_REVIEW`; or
- a minimized, authoritative external fact for linked `JOURNAL`,
  `EMOTION_CHECK_IN`, `PROFESSIONAL_SUPPORT`, or `REASSESSMENT_SCREENING`.

An external evidence reference records only its owner, opaque fact ID, optional
revision/status code allowed by contract, and occurred-at time. Authenticated
owner intake must resolve the fact's subject and exact linked action source;
Care verifies both against the locked action before accepting the minimized
reference. The source owner must validate authoritative state. Browser-supplied
IDs, opening a route, dismissing a reminder, draft content, or AI inference are
not completion evidence. Repeated delivery of the same fact is idempotent; a
fact cannot satisfy another user's action or an incompatible action type.

## Privacy and interpretation

Care stores orchestration, not source-domain content. It must not copy:

- journal body, AI reflection, tags, or mood;
- emotion value, note, interpretation, or trend;
- assessment answers or raw scores;
- appointment notes, messages, disputes, clinical detail, or entitlement
  ledger data; or
- full Content payload beyond the minimal display snapshot required to keep an
  exact historical resource action understandable.

Action completion, skips, streaks, and review responses are wellbeing context,
not treatment adherence, recovery, diagnosis, clinical improvement, or a
global wellbeing score. Contradictory evidence remains visible without being
collapsed into a verdict.

## Professional-support precedence

When authoritative routing requires moderate-or-higher professional support,
the Support Guide presents that pathway as primary. Self-help may be
complementary but cannot be described as an equivalent replacement or a reason
to delay professional or urgent help.

If accepted into the plan, Care creates a `PROFESSIONAL_SUPPORT` action with
routing provenance. Consultation remains authoritative for availability,
booking, appointment state, and fulfilment. SupportPlan edits never change an
appointment. Current crisis/safety policy always precedes plan creation and
ordinary plan controls.

## v2 compatibility

Existing v2 resource plans remain authoritative historical records under
[SupportPlan policy v2](support-plan-policy-v2.md). They are not backfilled,
renumbered, or silently dual-written.

The v3 read boundary may translate a v2 slot into a virtual `RESOURCE` action:

- slot identity becomes the virtual action identity;
- selected slots are grouped by exact stored `targetDomain` under deterministic
  virtual focus IDs and shown with stable compatibility-neutral copy (`Hỗ trợ
  tâm trạng` or `Hỗ trợ bình tâm`), not the internal enum or a user-authored or
  diagnostic focus;
- selected exact resource becomes the Content source reference;
- existing activity schedule/occurrence and engagement data become virtual
  history; and
- the projection is labelled `V2_RESOURCE_ADAPTER` with its v2 plan/policy
  version and exact source provenance.

The adapter does not create native v3 rows and does not change v2 write rules.
Contracts and events expose the model version and distinguish native Care
orchestration fields from source-owner facts. Removing the compatibility path
or materializing v2 state requires a separate accepted migration and exit plan.
