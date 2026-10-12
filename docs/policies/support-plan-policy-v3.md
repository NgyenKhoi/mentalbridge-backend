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

## Bounded personalization input

MB-638 adds the versioned `support-plan-personalization-v1` input owned by Care.
Every field is optional and the user may explicitly skip all fields. The closed
vocabulary is:

- focus: `ANXIETY`, `MOOD`, `SLEEP`, `MOTIVATION`, or `UNDERSTANDING_EMOTIONS`;
- pace: `GENTLE`, `BALANCED`, or `ACTIVE`;
- daily time budget: 5, 10, 15, 20, or 30 approximate minutes;
- daypart: `MORNING`, `AFTERNOON`, `EVENING`, or `FLEXIBLE`;
- activity modes: ordered unique choices from `READING`, `GUIDED_PRACTICE`,
  `JOURNALING`, `EMOTION_CHECK_IN`, and `ROUTINE`; and
- practical barriers: unique choices from `LOW_ENERGY`, `LIMITED_TIME`, and
  `DIFFICULT_TO_START`.

These values describe user intent and practical fit. They are not screening
answers, symptoms, diagnosis, risk, adherence, or clinical evidence. Care may
use them only to order already admitted choices, create plain-language focus
copy, and select safe schedule defaults. They cannot add an ineligible action,
remove professional-support precedence, weaken safety guidance, or override
source authority.

Each generated plan snapshots the exact immutable preference revision and
persists the human-readable focus reason. No saved preference produces the
existing deterministic policy fallback. Replacing preferences never rewrites
an `ACTIVE` or `PAUSED` plan; the owner receives an explicit review-required
reference and chooses a governed plan change separately.

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
| `PROFESSIONAL_SUPPORT` | Consultation service/appointment facts | Not assigned a recurrence by SupportPlan; fulfilment comes only from Consultation |
| `REASSESSMENT_SCREENING` | Care questionnaire/assessment history | One-time reminder only; fulfilment comes only from screening history |
| `SELF_REPORT_REVIEW` | Care review event | Review-only and not assigned a recurrence; user submission never becomes an outcome score |

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

MB-640 uses `support-plan-activity-schedule-v2` for reviewable cadence and
workload. A schedule is optional and, when present, contains:

- `ONCE`, `DAILY`, or `WEEKLY` recurrence, with one through seven unique ISO
  weekdays required only for `WEEKLY`;
- an optional preferred daypart plus a resolved local time;
- a 5 through 180 minute estimated duration in five-minute steps;
- an IANA timezone, bounded effective start/end dates, and reminder choice.

Draft creation persists deterministic safe defaults before activation. The
user reviews those defaults and may replace or remove each schedulable action's
rule. `GENTLE` and absent pace default to one weekly day; `BALANCED` defaults to
two weekly days; `ACTIVE` defaults to daily when the admitted activities fit
the saved daily time budget. Default durations share that aggregate budget and
default local times never overlap. When the five-minute minimum for every
admitted activity cannot fit on one day, Care uses a reduced weekly cadence
spread across local days rather than multiplying the budget. These defaults
never change action eligibility. Resource, Journal, emotion-check-in, and
routine actions support the bounded cadence; reassessment supports `ONCE` only.
Professional-support and self-report-review actions are not forced into
recurrence.

A schedule replacement is an explicit owner command on a `DRAFT` or `ACTIVE`
plan. It requires the strong plan ETag and an idempotency key, advances the plan
version when state changes, and records identical retries, including no-op
commands. It does not re-run or alter entitlement, source eligibility, action
admission, safety, or professional-support precedence. Removing a schedule or
choosing less work is neutral and never records skip, failure, non-adherence,
or a negative outcome.

For an active plan, a replacement starts no earlier than the next local day in
the requested timezone. Care ends the prior schedule revision, cancels only its
future open occurrences with `SCHEDULE_CHANGED`, preserves completed/skipped
and elapsed history, creates the next schedule revision, and fills the rolling
horizon in the same transaction. The predecessor never extends beyond an
earlier explicit end date. Removing a future-effective revision before its
first valid day records a separate occurrence-generation cutoff, so it cannot
generate new occurrences after removal while its public effective-date range
remains valid. Occurrence identity is deterministic from the schedule revision
and local date. Timezone resolution applies zone rules to each local date, so
daylight-saving gaps/overlaps are deterministic; retries and concurrent stale
writers cannot duplicate or silently move occurrences.

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

Discovery is navigation only. Consultation may persist the originating Care
action ID only when the user explicitly submits a booking request. That opaque
reference lets clients present bounded request/confirmation/cancellation/
completion progress in the originating plan; it grants no plan authority and
is separate from any `ConsultationBrief` sharing consent. Appointment changes
never advance, pause, complete, or replace the plan, and specialist proposals
remain separate user-confirmed `PlanChangeRequest` decisions.

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

For a stored `*_PROFESSIONAL_ADJUNCT` template family, the adapter also exposes
a deterministic virtual `PROFESSIONAL_SUPPORT` action as `PRIMARY` and marks
the projected self-help resources in that focus as `ADJUNCT`. Minimal/mild
self-guided families retain primary resource actions. This classification is a
projection of persisted routing provenance, not a client or adapter severity
inference.

The adapter does not create native v3 rows and does not change v2 write rules.
Contracts and events expose the model version and distinguish native Care
orchestration fields from source-owner facts. Removing the compatibility path
or materializing v2 state requires a separate accepted migration and exit plan.
