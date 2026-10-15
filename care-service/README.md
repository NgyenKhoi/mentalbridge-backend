# Care Service

MB-375 adds the Care-authoritative reassessment review and the single audited,
idempotent, atomic SupportPlan replacement path described below.

Care owns user profiles, independent `PRIVACY_POLICY` and `AI_PROCESSING` consent decisions, questionnaires, assessment submissions and results, deterministic safety/support policy, SupportEvaluation, persisted Support Guides, and the single official SupportPlan with its focuses, typed actions, schedules, occurrences, reassessment, and follow-up. Story 1103 exposes immutable coarse v1 evaluation. MB-335 adds `/api/v2/support-evaluations` with exact PHQ-9/GAD-7 provenance, two independent domain contributions, and separate PHQ-9 item-9 safety evidence. MB-372 adds paid deterministic draft creation and current-draft reload. MB-373 adds bounded admitted-choice replacement, exact revalidation, explicit activation idempotency, and authoritative current-plan reload. MB-513 adds deterministic local-time schedules and persisted activity occurrences. MB-374 completes the owner lifecycle with optional coded completion context and immutable terminal history/detail reads. MB-376 adds versioned owner-only completion, skip, reopen, visibility, helpfulness, barrier, private reflection, and deletion semantics on exact occurrences. MB-386 introduced immutable reassessment snapshots; MB-559 completes the canonical fourth dimension with an explicit owner-authored current-period self-report while keeping occurrence helpfulness/reflection separately labelled as supporting activity evidence. MB-639 introduces native cross-domain PlanActions while preserving v2 resource plans through an explicit read adapter. Care never creates a global severity, treatment-adherence score, recovery score, specialist-monitoring feed, or AI-controlled state. Safety-critical scoring and evaluation remain local and do not depend on Eureka, Kafka, Redis, AI, or notification availability.

MB-292 persists `screening_episode` as the Care-authoritative resume and
grouping context for guided initial checks and reassessments. PHQ-9 and GAD-7
submitted through a guided episode stay paired across logout or browser loss;
standalone history never silently replaces either member. SupportPlan proposal
requires an episode-backed v2 SupportEvaluation, and replacement rejects a
ReassessmentSummary whose current assessment pair differs from the draft's
evaluation with `REASSESSMENT_SCREENING_CONTEXT_MISMATCH`.

## MB-667 draft combined screening meaning

The separate owner-only `GET
/api/v1/support-evaluations/{supportEvaluationId}/combined-meaning` supports the
owner-supplied MB-667 Stitch flow and its shared current/historical result
composition. Its twenty neutral copy variants and exact
`mb-combined-screening-meaning-vi-vn-v1-draft.2` version remain pending explicit
Care/content-owner approval and are not deployment-approved. Design approval is
not health-copy approval. See the [reviewable draft catalogue](../docs/policies/combined-screening-meaning-draft-v1.md).

The read uses the exact owned saved v1 pair and stored tier, keeps independent
levels and safety evidence, prioritizes approved safety guidance, and returns
an explicit unavailable response rather than substituting current history.
It introduces no writes, migrations, events, external calls or AI dependency.
Original v1/v2 response shapes, immutable routing/history and existing Guide
and SupportPlan behavior remain unchanged. Source policy/evaluation time and
the later draft presentation/content versions are distinct provenance.

## MB-88 foundation

The canonical [`care-service-v1.yaml`](../contracts/openapi/care-service-v1.yaml) contract marks profile, independent privacy/AI-processing disclosure and consent, questionnaire retrieval, assessment history, and authenticated/anonymous screening paths as `implemented`. MB-370 uses the existing `AI_PROCESSING` decision/UI at current policy v2 for optional ConsultationBrief suggestions; production-provider approval remains separately gated.

MB-205 adds authenticated descriptive progress for a selected owned assessment. Care compares it only with the immediately preceding non-voided result for the same instrument and identical scoring version, ordered by submission instant and assessment ID. The response contains arithmetic score direction, raw delta, band transition and elapsed duration without safety-status comparison, clinical interpretation, causation or optional-service side effects. Missing, voided or incompatible evidence returns `INSUFFICIENT_COMPARABLE_DATA`; missing and cross-owner identifiers share the same `ASSESSMENT_NOT_FOUND` response.

The read-only query lives in the cohesive `progress` feature package. The existing partial index `ix_assessment_submission_user_history (user_id, submitted_at DESC, id DESC) WHERE user_id IS NOT NULL AND voided_at IS NULL` already supports the owner/time/tie-breaker scan, so MB-205 adds no table, field, index or Liquibase changeset.

MB-386 adds `POST /api/v1/reassessment-summaries`, owner-only current/detail/history reads, and the immutable `reassessment_summary` snapshot. Historical v1 calls select one owned PHQ-9 result, one owned GAD-7 result, one Journal/AI analysis and two equal non-overlapping 7-31 day periods. Care selects the immediately preceding compatible result for each instrument and calculates arithmetic direction locally. Sparse coverage remains `INSUFFICIENT_DATA`. SupportPlan evidence is selected by occurrence `scheduled_at` inside the periods and only when the owner previously set `summary_reuse_approved=true`; private reflection is included only under that same approval. Reads never re-query mutable or deleted sources.

MB-386 v1 snapshots remain a compatibility baseline under their exact
policy/source provenance. ADR 0022 does not rewrite them. MB-559 adds
`reassessment-self-report-v1` create/current/replace/delete APIs and canonical
`reassessment-summary-v2` composition. `GET /reassessment-summaries/context`
selects the latest usable owned PHQ-9/GAD-7 evidence and issues two adjacent
14-day periods under `reassessment-comparison-v1`. Canonical composition takes
the Journal `jobId`; Care resolves RUNNING/SUCCEEDED/FAILED authoritatively and
never relies on a browser-created analysis identifier or unavailable claim.
Omitted self-report is
`INSUFFICIENT_DATA`; a selected deleted source is `UNAVAILABLE`; activity
reflection remains separate supporting evidence. Source replacement or deletion
never rewrites an already persisted summary snapshot. MB-559 also supplies the
functional actor journey that renders all four dimensions without an overall
verdict; later presentation work is history and refinement only.

## MB-638 SupportPlan personalization

`GET/PUT /api/v2/support-plan-personalization` exposes one USER-owned current
view over immutable `support-plan-personalization-v1` revisions. ETag revision
zero is the deterministic `NOT_PROVIDED` fallback; a replacement with no fields
records `SKIPPED`. Care stores multi-value modes and barriers as ordered bounded
children rather than free text or screening evidence.

Draft composition snapshots the exact revision, persists human-readable focus
copy/provenance, and may reorder only resources already admitted by the current
Care/Content policy. The snapshotted daypart can select the existing schedule
default time. A later preference revision does not rewrite an active or paused
plan and instead returns an explicit plan-review reference. MB-640 implements
future schedule editing and richer cadence/workload controls as described below.

## MB-640 adjustable SupportPlan cadence

`GET /api/v2/support-plans/current-draft` exposes persisted schedule defaults
for review before activation. `PUT
/api/v2/support-plans/{supportPlanId}/actions/{actionId}/schedule` replaces or
removes one draft/active action schedule under strong `If-Match` and
`Idempotency-Key` checks. The bounded request covers recurrence/weekdays,
daypart/local time, duration, IANA timezone, effective dates, and reminders.

Active edits begin no earlier than tomorrow in the requested timezone. Care
versions the schedule and parent plan, cancels only superseded future open
occurrences with `SCHEDULE_CHANGED`, and regenerates the deterministic rolling
horizon, including the predecessor cadence through a deferred future cutover.
Draft defaults share the aggregate daily time budget, stagger local times, and
use a reduced weekly cadence when the five-minute minimum cannot fit every
activity on one day. Existing v2 resource plans use the same additive schedule
revisions through the v3 adapter; their plan, slot, resource, and occurrence
history is not rewritten. Removing or reducing workload is neutral and never
changes eligibility or records non-completion.

## MB-639 SupportPlan v3 actions

Authoritative completion replay compares source owner/kind, fact identity and
revision, and original occurrence time. A later verification instant does not
create a different fact: replay preserves the first accepted verification,
completion time, occurrence version and single outbox event. MB-641 depends on
this behavior when a fresh Emotion evidence read reconciles an already accepted
check-in.

ADR 0034 makes `SupportPlan -> PlanFocus -> PlanAction -> ActionSchedule ->
ActionOccurrence` the prospective canonical hierarchy. Native actions use only
the closed types `RESOURCE`, `JOURNAL`, `EMOTION_CHECK_IN`,
`ROUTINE_SELF_MANAGEMENT`, `PROFESSIONAL_SUPPORT`,
`REASSESSMENT_SCREENING`, and `SELF_REPORT_REVIEW`.

Care owns lifecycle, plan revisions, ordering, bounded presentation/provenance,
generic scheduling, and occurrence orchestration. Content, Journal/AI,
Consultation, and Care screening remain authoritative for their referenced
facts. Unknown types, malformed references, incompatible type/owner pairs,
client-asserted external completion, and cross-owner injection fail closed.
`RESOURCE` retains its exact ResourceVersion and publication eligibility; other
source actions retain only bounded opaque references and accepted completion
facts. Raw journal text, emotion values/notes, assessment answers/raw scores,
and consultation notes/messages never enter SupportPlan storage or events.

The native model adds `support_plan_focus` and `support_plan_action`. The
existing `support_plan_activity_schedule` and
`support_plan_activity_occurrence` tables are extended additively so a row can
retain either its legacy v2 slot/resource provenance or a native v3 action
reference. They are not renamed, and legacy rows are not backfilled.

V3 reads translate a v2 slot, selected exact resource, and existing activity
history into a virtual `RESOURCE` action marked `V2_RESOURCE_ADAPTER`. The
adapter creates no native action rows and changes no v2 write or history
semantics. A schedule edit changes only future generation; elapsed time yields
an overdue read state rather than an automatic failure.

When the persisted v2 routing template is a professional-adjunct family, the
same read boundary also emits one deterministic virtual
`PROFESSIONAL_SUPPORT` action as `PRIMARY`; the resource actions in that focus
are `ADJUNCT`. Consultation owns discovery and appointment state. Care exposes
only the plan path and never books, shares data, or treats an appointment
transition as authority to change the plan lifecycle.

For native v3 reads, a focus with a non-ended `PROFESSIONAL_SUPPORT` action
projects that action as `PRIMARY` and its sibling actions as `ADJUNCT`; a focus
without that context projects its actions as `PRIMARY`. The bounded
`purposeCode` remains an action intent code and is never parsed to determine
path precedence.

## MB-641 Emotion owner-evidence reconciliation

`POST /api/v2/support-plan-occurrences/{occurrenceId}/emotion-check-in/reconcile`
is an explicit USER command on an existing native `EMOTION_CHECK_IN` occurrence.
It accepts no body or browser-supplied source/fact identity. Care derives the
local day, timezone and source link from the owned occurrence, closes the read
transaction, then requests Journal/AI's content-free completion projection with
the verified USER bearer and correlation ID. A separate locked transaction
rechecks the plan/action/occurrence, exact subject/day/timezone, activation time
and one-fact/one-occurrence invariant before accepting the owner fact.

`sourceState` is `RECORDED`, `MISSING` or `DELETED`, separate from persisted
`occurrenceState`. Missing/deleted evidence never creates completion and never
erases already accepted history. An identical recorded fact preserves the first
verification/completion time and version; unavailable, malformed, unattributed,
future or incompatible evidence never becomes a false success/absence. Owner
failure returns `EMOTION_CHECK_IN_EVIDENCE_UNAVAILABLE` (503). Response caching
is disabled. No emotion value, intensity, factors, note, analysis or AI consent
enters this boundary, storage or event payload.

The consumer uses its own bounded connection/read deadlines, at most one safe
GET retry and a fail-fast circuit breaker. Its conservative maximum call budget
is 2.5 seconds. This endpoint does not convert v2 plans or invent native actions.

MB-641 introduced NEW native proposals under
`support-plan-emotion-actions-v1`; those saved plans remain usable under their
snapshotted policy. The same paid entitlement, guided screening
context, deterministic template/resource admission and exact Content checks
apply. The current immutable personalization revision supplies explicit
`EMOTION_CHECK_IN` activity-mode intent; no Emotion data chooses actions. The
proposal retains admitted resources, required professional-support precedence,
bounded presentation and one optional check-in action. It creates no v2 slots
or adapter rows. Draft cadence shares the saved daily workload budget and
spreads actions over local days when their minimum durations cannot fit.

`POST /api/v2/support-plans/{id}/activate` requires strong `If-Match` and an
idempotency key, freshly revalidates admission outside local locks, then
activates plan/actions/schedules atomically through the existing command ledger.
Changed preferences require explicit draft review/regeneration. An existing
current plan cannot be bypassed: the v2 native `replacement-review` and `replace`
commands require the latest canonical reassessment with the exact proposed
screening pair, separately display all four dimensions, and apply only an
explicit user confirmation. The old plan is superseded with future occurrences
cancelled; old v2 rows/identities and terminal facts are never converted.

Native lifecycle uses `PUT /api/v2/support-plans/{id}/status` with existing
versioned lifecycle semantics. `GET /api/v2/support-plans/history` reads bounded
terminal native and v2-adapter history without recomposition. Legacy APIs retain
v2 behavior and reject a native plan before mutation with
`SUPPORT_PLAN_MODEL_VERSION_UNSUPPORTED`, rather than rendering invalid v2 data.

`PUT /api/v2/support-plan-occurrences/{id}/state` keeps native manual actions
usable under the action taxonomy fixed by ADR 0034. It accepts only explicit
`SCHEDULED`, `SKIPPED` or `COMPLETED` intent with the strong occurrence validator.
Only `RESOURCE` and `ROUTINE_SELF_MANAGEMENT` may record a Care-owned
`USER_ACKNOWLEDGEMENT`; opening a resource is not completion. `EMOTION_CHECK_IN`
and `JOURNAL` may be skipped or reopened from a user skip, but never manually
completed. Accepted completion evidence cannot be erased or reopened through
this command. Inactive/cancelled/future-day targets fail closed, same-state
retries preserve version, and state changes reuse the minimized native
occurrence event without copying source content or changing plan lifecycle.
Native helpfulness/private-reflection replacement is not exposed by this slice;
the legacy engagement API remains limited to its v2 resource model.

The web/mobile consumers use canonical Emotion navigation, explicit completion
checks and separate factual owner Emotion coverage. Native device execution
remains separately gated by MB-666; source/browser checks do not prove it.

## MB-642 Journal intent and owner-evidence reconciliation

New native proposals use `support-plan-journal-actions-v1` and admit one bounded
`JOURNAL` action only from the saved immutable `JOURNALING` preference. The
`short-reflection` intention under `journal-writing-intent-v1` is Care-owned
wording, not copied private content or AI guidance. Existing admitted resources,
paid entitlement, exact screening/Content checks and professional-support
precedence remain unchanged. Journal receives the same reviewable generic
duration/cadence defaults within the aggregate workload budget. New preferences
never add actions to a saved active plan; existing Emotion-policy and v2 plans
are not rewritten. Draft/replacement revalidation checks the exact saved
Journal intent and personalization provenance before user-confirmed activation.

`POST /api/v2/support-plan-occurrences/{id}/journal/reconcile` accepts exactly
`journalId`, an untrusted candidate reference. Care resolves its own native
Journal occurrence first, queries Journal's content-free creation projection
outside a transaction with the verified USER bearer/correlation, then locks and
rechecks the action/source link, subject, entry, occurrence day in its schedule
timezone and activation boundary. The fact uses immutable server `createdAt`,
not the entry's user-selected date or an edit timestamp. One fact satisfies only
one occurrence. Retry preserves accepted time, version and event; another entry
cannot replace that historical reference.

Current `MISSING`/`DELETED` source states do not complete an open occurrence or
erase accepted evidence. `JOURNAL_ENTRY_EVIDENCE_UNAVAILABLE` (503) means owner
uncertainty, not absence. The client has separate typed deadlines, at most one
safe GET retry, fail-fast breaker and a maximum 2.5-second conservative budget;
the response is `no-store`. Private text, preview, mood, tags, AI reflection and
analysis never enter the Care DTO, tables, event or logs. A completion check
does not grant AI processing, specialist sharing or reflection reuse.

Reassessment still keeps all four dimensions separate. Future longitudinal
composition uses its independently consented current source revisions and
coverage/provenance; deleted source analyses remain unavailable. Neither entry
revision/deletion nor a completion read rewrites immutable review snapshots.
This slice reuses the native action/evidence schema in migration 025 and the
existing minimized occurrence event; it adds no migration, queue or new ledger.
Web/mobile Journal handoff and its prototype/native evidence are a separate
consumer slice and are not claimed complete by this backend boundary.

## SupportPlan system-copy encoding compatibility

New proposals store readable Vietnamese rationale and standard safety copy.
The non-diagnostic disclaimer is returned with the same meaning and valid
Vietnamese diacritics. V2 reads and their v3 resource adapter also correct the
two exact malformed system-generated rationale/standard-reminder literals from
`mb-support-plan-selection-v1` for presentation only. The rationale code and
negative-screen standard-reminder code must match; other copy, positive safety
guidance, personalized focus copy, and native v3 authored copy remain unchanged.

This is a bounded encoding erratum, not recomposition or a history backfill.
Stored text, plan identity/version, policy/source references, lifecycle instants,
and safety decisions are not rewritten. No general character-decoding heuristic
or database repair is applied.

## MB-375 reassessment review and replacement

`POST /api/v1/support-plans/{draftId}/replacement-review` is read-only. It
requires the latest owned `reassessment-summary-v2`, reloads the persisted
current and proposed plans, and freshly checks paid entitlement, current
SupportEvaluation/template compatibility, exact content versions, publication
and effective state, eligibility, and plan constraints. It returns exactly one
governed outcome while preserving the four reassessment dimensions separately:
`CURRENT_PLAN_VALID_NO_BETTER_ALTERNATIVE`,
`CURRENT_PLAN_VALID_ALTERNATIVES_AVAILABLE`, or
`CURRENT_PLAN_NOT_ADMISSIBLE`.

`POST /api/v1/support-plans/{draftId}/replace` is the only mutation path. It
requires `If-Match`, `Idempotency-Key`, the current plan/version, and the exact
reassessment summary ID. After repeating the same fresh checks, one owner-locked
transaction supersedes the current plan, ends its future occurrences, activates
and schedules the draft, records the immutable source-plan/summary/outcome audit
relation, and writes the activation outbox row. Any stale, withdrawn,
unauthorized, concurrent, or unavailable condition leaves the current plan and
draft unchanged. A proposal with the same exact slot/resource versions is not a
replacement and returns `SUPPORT_PLAN_REPLACEMENT_UNCHANGED`.

The contract establishes these boundaries:

- the verified JWT subject is the only authenticated profile and assessment owner;
- only `USER` accounts may use the authenticated profile, consent, and assessment flows;
- profile replacement uses optimistic concurrency and never accepts an account ID from the request;
- consent decisions are append-only, policy-versioned, and idempotent per user and consent type;
- specialist access is a separate future scoped-grant workflow, not a broad consent toggle;
- assessment requests contain only a questionnaire-definition ID and complete `0..3` answers;
- Care computes score, screening band, scoring version, safety status, and safety-policy version;
- submission, answers, result, and outbox record commit atomically when runtime behavior is implemented;
- an anonymous session is authorized by a high-entropy token stored only as a hash, expires at a policy-owned instant, and has no account/claim field;
- anonymous results cannot be silently attached to a later account.

Errors use RFC 9457 Problem Details with stable `code` and `correlationId` fields. Assessment and consent commands require `Idempotency-Key`; a retry with the same owner, key, and request returns the original outcome, while a different request conflicts.

## PostgreSQL ownership

Create the service-owned `mentalbridge_care` database before running this module. Liquibase connects directly to that database and uses its default `public` schema. It never creates a database, schema named `care`, or cross-service foreign key.

The Care Liquibase changelog owns:

| Table | Purpose |
| --- | --- |
| `user_profile` | Care-owned non-credential profile with optimistic versioning |
| `consent_decision` | Append-only versioned consent evidence with owner-scoped idempotency |
| `anonymous_assessment_session` | Short-lived isolated session with hashed bearer credential |
| `questionnaire_definition` | Versioned instrument metadata, scoring identity, provenance, and publication state |
| `questionnaire_question` | Ordered version-owned item wording and safety-item marker |
| `questionnaire_score_band` | Auditable score-to-screening-level ranges |
| `assessment_submission` | Immutable owner, questionnaire version, request hash, idempotency key, and anonymous retention deadline |
| `assessment_answer` | One validated `0..3` answer tied to the same definition as its submission |
| `assessment_result` | Server-owned score, band, independent safety status, scoring/safety-policy versions, and disclaimer code |
| `support_policy_definition` and policy reference tables | Immutable compatible questionnaire versions, localized band meanings, and bounded support-tier guidance |
| `support_evaluation` | Immutable deterministic v1 coarse result for one explicit owned PHQ-9/GAD-7 evidence pair; not sufficient to select a SupportPlan |
| `support_evaluation_request` | Per-user idempotency aliases resolving retries to the immutable evaluation |
| `support_evaluation_v2_policy_definition` and `support_evaluation_v2_eligible_definition` | Immutable v2 policy provenance and exact compatible questionnaire/domain allow-list |
| `support_evaluation_v2` | Immutable v2 owner, explicit evidence pair, policy version, and evaluation instant |
| `support_evaluation_v2_domain` | Two immutable instrument/domain/level/pathway/reason snapshots used for independent composition |
| `support_evaluation_v2_safety` | Independent PHQ-9 item-9 status and safety-policy snapshot without a raw answer |
| `support_evaluation_v2_request` | Per-user v2 idempotency aliases; separate namespace from v1 keys |
| `screening_episode` | Persisted Care-owned grouping/resume context for exact guided PHQ-9, GAD-7, and SupportEvaluation evidence; standalone history is never inferred into it |
| `consultation_brief` | Appointment-scoped owner draft with minimized current situation, exact SupportEvaluation v2 reference, goals, and optimistic versioning |
| `consultation_brief_snapshot` | Immutable exact content and screening-provenance snapshot created only by explicit owner approval |
| `consultation_brief_grant` | Assigned-specialist, appointment, purpose, snapshot, access-window, and revocation authority |
| `consultation_brief_audit` | Content-free allowed/denied draft, approval, revoke, delete, and specialist-read facts |
| `consultation_brief_ai_draft_job` | Owner-private asynchronous suggestion request with exact saved-brief/source versions and consent, entitlement, routing, provider, model, prompt, and schema provenance; deletion scrubs generated content |
| `support_plan` | One Care-owned paid proposal/current-plan snapshot with exact source, entitlement, rationale, safety, lifecycle instants, optional coded completion reason, and optimistic version provenance; terminal rows are immutable owner history |
| `support_plan_focus` | Ordered versioned native v3 focus inside one plan; plain-language intent only, never diagnosis or outcome |
| `support_plan_action` | Ordered typed native v3 action with bounded presentation, source-owner/reference, provenance, capabilities, and optimistic versioning |
| `support_plan_personalization` | Immutable owner-local revisions of optional bounded focus and practical-fit inputs, separate from screening evidence |
| `support_plan_personalization_activity_mode` | Ordered unique bounded activity-mode choices for one personalization revision |
| `support_plan_personalization_barrier` | Ordered unique bounded practical barriers for one personalization revision |
| `support_plan_template_family` | Ordered immutable domain template families composed into the draft |
| `support_plan_slot` | Ordered bounded slots; core selection is required while an optional selection may be explicitly removed |
| `support_plan_slot_alternative` | Server-admitted exact alternatives for a stored slot; not client-authored choices |
| `support_plan_request` | Per-user idempotency aliases that replay the same current draft |
| `support_plan_command` | Owner-scoped idempotent activation outcome plus exact revalidation provenance |
| `support_plan_command_selection` | Ordered exact resource-version intent committed by activation |
| `support_plan_activity_schedule` | Additive shared physical schedule for either legacy v2 slot/resource provenance or a native v3 action; versioned recurrence, local time, and timezone without legacy-row backfill |
| `support_plan_activity_occurrence` | Deterministic dated occurrence for the schedule's legacy source or native action, with exact provenance/evidence and versioned owner engagement/visibility where policy permits |
| `reassessment_self_report` | Versioned owner-authored current-period response with optional bounded helpful/difficult context and content-clearing deletion tombstones |
| `reassessment_summary` | Immutable owner-scoped v1/v2 snapshot with request idempotency, exact periods, external Journal/AI provenance, explicit self-report provenance, separately labelled reusable occurrence evidence, and explicit unavailable/insufficient states |
| `outbox_event` | Minimal integration fact persisted in the aggregate transaction |

The reference-data migrations publish immutable English PHQ-9, current controlled-Capstone Vietnamese PHQ-9 v2, and Vietnamese GAD-7 definitions. PHQ-9 v1 remains readable as a retired immutable definition so historical results reopen against their original wording and bands. GAD-7 contains seven questions, the approved four-choice self-administered mapping, standard `0..21` bands, explicit non-applicable safety semantics, and auditable source provenance. These publications are approved only for controlled local/demo Capstone use and do not represent production clinical/domain approval.

The v1 support-routing policy serves Vietnamese screening meanings with content version `mb-screening-meaning-vi-vn-v2`. The expanded copy explains what each PHQ-9 or GAD-7 band reflects over 14 days, keeps the two instruments separate, and preserves the non-diagnostic limitation. The routing tier and safety behavior are unchanged.

Assessment answer text must never be copied into outbox payloads, logs, errors, metrics, or unrestricted audit metadata.

## Configuration

| Variable | Required | Purpose | Safe local example |
| --- | --- | --- | --- |
| `EUREKA_DEFAULT_ZONE` | Production | Eureka registry endpoint shared by Spring services | `http://localhost:8761/eureka/` |
| `EUREKA_CLIENT_ENABLED` | No | Enables Eureka registration; disable it when running Care by itself locally | `false` |
| `CONTENT_RESOURCE_ELIGIBILITY_BASE_URL` | Local/test only | Optional direct Content URL; leave empty outside tests so OpenFeign resolves `content-notification-service` through Eureka | `http://localhost:3003` |
| `CONTENT_RESOURCE_ELIGIBILITY_CONNECT_TIMEOUT` | No | Bounded TCP connection deadline for exact eligibility queries | `PT0.5S` |
| `CONTENT_RESOURCE_ELIGIBILITY_READ_TIMEOUT` | No | Total response-read deadline for a bounded batch | `PT2S` |
| `CONTENT_RESOURCE_ELIGIBILITY_MAX_ATTEMPTS` | No | Total safe attempts for the read-only batch POST, from 1 through 3 | `2` |
| `CONTENT_RESOURCE_ELIGIBILITY_RETRY_WAIT` | No | Positive base delay for bounded exponential retry with 20% jitter | `PT0.1S` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_WINDOW_SIZE` | No | Resilience4j count-based breaker window | `10` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_MINIMUM_CALLS` | No | Calls required before the breaker may open | `5` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_FAILURE_RATE` | No | Percentage of dependency failures that opens the breaker | `50` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_OPEN_DURATION` | No | Bounded open interval before half-open probes | `PT10S` |
| `CONTENT_RESOURCE_ELIGIBILITY_CIRCUIT_HALF_OPEN_CALLS` | No | Permitted half-open probes | `2` |
| `CONTENT_SAFETY_DIRECTORY_BASE_URL` | Local/test only | Optional direct Content URL for the reviewed directory; leave empty outside tests so OpenFeign uses Eureka | `http://localhost:3003` |
| `CONTENT_SAFETY_DIRECTORY_CONNECT_TIMEOUT` | No | Bounded TCP connection deadline for directory lookup | `PT0.5S` |
| `CONTENT_SAFETY_DIRECTORY_READ_TIMEOUT` | No | Bounded response-read deadline before Care returns its local fallback | `PT1S` |
| `CONSULTATION_ENTITLEMENT_BASE_URL` | Local/test only | Optional direct Consultation URL; leave empty outside tests so OpenFeign resolves `consultation-service` through Eureka | `http://localhost:8082` |
| `CONSULTATION_ENTITLEMENT_CONNECT_TIMEOUT` | No | Bounded TCP connection deadline for the authoritative current entitlement read | `PT0.5S` |
| `CONSULTATION_ENTITLEMENT_READ_TIMEOUT` | No | Total response-read deadline for current entitlement | `PT2S` |
| `JOURNAL_AI_LONGITUDINAL_BASE_URL` | Local/demo only | Direct Journal/AI URL because the Node service does not register with Eureka | `http://localhost:3000` |
| `JOURNAL_AI_LONGITUDINAL_CONNECT_TIMEOUT` | No | Bounded connection deadline for minimized reassessment evidence | `PT0.2S` |
| `JOURNAL_AI_LONGITUDINAL_READ_TIMEOUT` | No | Response-read deadline before one Journal/AI attempt times out | `PT0.8S` |
| `JOURNAL_AI_LONGITUDINAL_MAX_ATTEMPTS` | No | Total attempts for the idempotent GET, one or two | `2` |
| `JOURNAL_AI_LONGITUDINAL_RETRY_WAIT` | No | Positive base delay for bounded transient retry with jitter | `PT0.1S` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_WINDOW_SIZE` | No | Reassessment projection breaker window | `10` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_MINIMUM_CALLS` | No | Calls required before the breaker may open | `5` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_FAILURE_RATE` | No | Percentage of transport/malformed failures that opens the breaker | `50` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_OPEN_DURATION` | No | Bounded breaker-open interval | `PT10S` |
| `JOURNAL_AI_LONGITUDINAL_CIRCUIT_HALF_OPEN_CALLS` | No | Permitted half-open probes | `2` |
| `JOURNAL_AI_EMOTION_EVIDENCE_BASE_URL` | Local/demo | Direct Journal/AI URL for minimized Emotion completion evidence; the Node owner is not registered in Eureka | `http://localhost:3000` |
| `JOURNAL_AI_EMOTION_EVIDENCE_CONNECT_TIMEOUT` | No | Bounded TCP deadline for the owner check-in fact | `PT0.2S` |
| `JOURNAL_AI_EMOTION_EVIDENCE_READ_TIMEOUT` | No | Bounded response deadline per evidence GET | `PT0.8S` |
| `JOURNAL_AI_EMOTION_EVIDENCE_MAX_ATTEMPTS` | No | Total safe GET attempts, one or two | `2` |
| `JOURNAL_AI_EMOTION_EVIDENCE_RETRY_WAIT` | No | Positive transient retry delay with exponential jitter | `PT0.1S` |
| `JOURNAL_AI_EMOTION_EVIDENCE_CIRCUIT_WINDOW_SIZE` | No | Count-based evidence circuit window | `10` |
| `JOURNAL_AI_EMOTION_EVIDENCE_CIRCUIT_MINIMUM_CALLS` | No | Minimum evidence calls before opening | `5` |
| `JOURNAL_AI_EMOTION_EVIDENCE_CIRCUIT_FAILURE_RATE` | No | Transport/malformed evidence failure percentage | `50` |
| `JOURNAL_AI_EMOTION_EVIDENCE_CIRCUIT_OPEN_DURATION` | No | Fail-fast open interval | `PT10S` |
| `JOURNAL_AI_EMOTION_EVIDENCE_CIRCUIT_HALF_OPEN_CALLS` | No | Bounded recovery probes | `2` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_BASE_URL` | Local/demo | Direct Journal/AI URL for exact content-free Journal creation evidence | `http://localhost:3000` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_CONNECT_TIMEOUT` | No | Bounded TCP deadline for the owner entry fact | `PT0.2S` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_READ_TIMEOUT` | No | Bounded response deadline per evidence GET | `PT0.8S` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_MAX_ATTEMPTS` | No | Total safe GET attempts, one or two | `2` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_RETRY_WAIT` | No | Positive transient retry delay with exponential jitter | `PT0.1S` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_CIRCUIT_WINDOW_SIZE` | No | Count-based Journal evidence circuit window | `10` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_CIRCUIT_MINIMUM_CALLS` | No | Minimum calls before opening | `5` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_CIRCUIT_FAILURE_RATE` | No | Transport/malformed evidence failure percentage | `50` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_CIRCUIT_OPEN_DURATION` | No | Fail-fast open interval | `PT10S` |
| `JOURNAL_AI_JOURNAL_EVIDENCE_CIRCUIT_HALF_OPEN_CALLS` | No | Bounded recovery probes | `2` |
| `JOURNAL_AI_SUPPORT_GUIDE_BASE_URL` | Local/demo only | Direct Journal/AI URL for optional consent-gated Support Guide phrasing | `http://localhost:3000` |
| `JOURNAL_AI_SUPPORT_GUIDE_CONNECT_TIMEOUT` | No | Bounded connection deadline for optional Support Guide phrasing | `PT0.5S` |
| `JOURNAL_AI_SUPPORT_GUIDE_READ_TIMEOUT` | No | Bounded response deadline before Care persists its approved-copy fallback | `PT8S` |
| `JOURNAL_AI_SUPPORT_GUIDE_CIRCUIT_*` | No | Circuit-breaker window, threshold, open duration, and half-open probe budget for optional phrasing | See `.env.example` |
| `JOURNAL_AI_SUPPORT_PLAN_COMPANION_BASE_URL` | Local/demo only | Direct Journal/AI URL for explicit SupportPlan Companion requests | `http://localhost:3000` |
| `CARE_SUPPORT_PLAN_COMPANION_SERVICE_TOKEN` | Local/demo only | Dedicated Care-to-Journal/AI credential; configure the same value as Journal/AI `JOURNAL_AI_SUPPORT_PLAN_CARE_SERVICE_TOKEN` | none; missing disables provider phrasing and keeps the deterministic template |
| `JOURNAL_AI_SUPPORT_PLAN_COMPANION_CONNECT_TIMEOUT` | No | Bounded connection deadline for optional Companion phrasing | `PT0.5S` |
| `JOURNAL_AI_SUPPORT_PLAN_COMPANION_READ_TIMEOUT` | No | Bounded response deadline before Care returns its deterministic template | `PT8S` |
| `JOURNAL_AI_SUPPORT_PLAN_COMPANION_CIRCUIT_*` | No | Circuit-breaker window, threshold, open duration, and half-open probe budget for Companion phrasing | See `.env.example` |
| `CARE_REDIS_URL` | When Companion provider phrasing is enabled | Shared Redis URL used only for ephemeral cross-instance pending and rate admission | `redis://localhost:6379` |
| `CARE_REDIS_CONNECT_TIMEOUT` / `CARE_REDIS_COMMAND_TIMEOUT` | No | Bounded Redis deadlines; an unavailable admission store fails closed to template copy | `PT0.5S` |
| `CARE_SUPPORT_PLAN_COMPANION_ADMISSION_ENABLED` | No | Enables the required distributed admission policy; disabling it fails closed and never enables unguarded provider calls | `true` |
| `CARE_SUPPORT_PLAN_COMPANION_ADMISSION_POLICY_VERSION` | No | Version recorded in Redis key scope for safe policy rollout | `companion-admission-v1` |
| `CARE_SUPPORT_PLAN_COMPANION_MAXIMUM_REQUESTS` / `CARE_SUPPORT_PLAN_COMPANION_RATE_WINDOW` | No | Per-user admitted provider-request cap shared by all Care instances | `6` / `PT1M` |
| `CARE_SUPPORT_PLAN_COMPANION_PENDING_TTL` | No | Upper bound for one per-user provider request in flight; exceeds the HTTP deadline | `PT12S` |
| `JOURNAL_AI_CONSULTATION_BRIEF_DRAFT_BASE_URL` | Local/demo only | Direct Journal/AI URL for optional ConsultationBrief suggestions | `http://localhost:3000` |
| `JOURNAL_AI_CONSULTATION_BRIEF_DRAFT_CONNECT_TIMEOUT` | No | Bounded connection deadline for the asynchronous worker call | `PT0.5S` |
| `JOURNAL_AI_CONSULTATION_BRIEF_DRAFT_READ_TIMEOUT` | No | Bounded provider response deadline; a timeout leaves the manual draft usable | `PT12S` |
| `CARE_DB_URL` | Yes | Care-owned PostgreSQL JDBC URL; production uses a TLS-capable connection | `jdbc:postgresql://localhost:5432/mentalbridge_care` |
| `CARE_DB_USERNAME` | Yes | Care-owned PostgreSQL login | `mentalbridge_care` |
| `CARE_DB_PASSWORD` | Yes | Care PostgreSQL password injected outside source control | `replace-with-a-local-secret` |
| `IDENTITY_JWT_ISSUER` | Yes | Expected issuer for Identity access tokens | `https://identity.local.mentalbridge` |
| `IDENTITY_JWT_AUDIENCE` | Yes | Required Care API audience | `mentalbridge-api` |
| `IDENTITY_JWT_PUBLIC_KEY` | Yes | X.509 RSA public key used to verify Identity tokens | `replace-with-x509-pem-public-key` |
| `CARE_ANONYMOUS_SESSION_TTL` | Yes | Sliding inactivity lifetime applied after valid anonymous activity | `PT30M` for controlled local/demo use |
| `CARE_ANONYMOUS_SESSION_MAXIMUM_LIFETIME` | Yes | Absolute lifetime from anonymous session creation; activity cannot extend beyond it | `PT2H` for controlled local/demo use |
| `CARE_PHQ9_SAFETY_POLICY_VERSION` | Yes | Exact environment-approved policy version persisted with every runtime PHQ-9 result | `MB-SAFETY-PHQ9-001/1.0-capstone` only after the Capstone evidence gate passes |
| `CARE_IDEMPOTENCY_HMAC_KEY` | Yes | Random secret of at least 32 characters used to protect assessment request fingerprints from offline enumeration | Secret-manager value; never commit it |

Application startup validates the Liquibase-owned schema through Hibernate and does not run migrations. Local `.env` loading is optional; real environment variables take precedence, and CI/production disable dotenv loading.

```powershell
$env:CARE_DB_URL='jdbc:postgresql://<host>:5432/mentalbridge_care?sslmode=require'
$env:CARE_DB_USERNAME='<care-username>'
$env:CARE_DB_PASSWORD='<care-password>'
./mvnw.cmd liquibase:validate
./mvnw.cmd liquibase:status
./mvnw.cmd liquibase:updateSQL
./mvnw.cmd liquibase:update
```

`updateSQL` reviews generated SQL without mutating the database. `update` applies pending changesets and records them in `mentalbridge_care.public.databasechangelog`.

## Safety and policy status

The foundation records facts needed by later governed behavior without silently deciding open policy:

- PHQ-9 scoring is deterministic and server-owned; the stored result is a screening result, not a diagnosis.
- A positive versioned safety item is persisted independently of the total score so later policy cannot ignore it.
- ADR 0009 fixes item-9 positivity (`answer >= 1`), keeps it independent from the screening band, prohibits automatic human/emergency notification, and removes the hotline catalogue.
- Current Vietnamese questionnaire content is published as `phq9-vi-vn-capstone-v2` for `DEPRESSIVE_SYMPTOMS` and `gad7-vi-vn-adult-v1` for `ANXIETY_SYMPTOMS` in controlled local/demo use. PHQ-9 v1 is retired without mutation. GAD-7 returns `NOT_APPLICABLE` with null safety fields instead of a false PHQ-style safety result. Safety remains cross-cutting; no combined score or global severity exists. The deterministic v1 support-tier mapping and additive `mb-support-routing-capstone-v2` domain-aware evaluation are published for controlled Capstone use. MB-511 adds the all-tier one-time `mb-support-guide-capstone-v1` runtime with immutable exact provenance and approved-copy fallback. MB-372/MB-373 implement paid draft proposal, bounded choice replacement, explicit activation, and current-plan reload under ADR 0013. Pause/resume, completion, replacement, specialist actions, production consent/retention, production domain review, and minimum-age expansion remain separate gates.
- No endpoint may imply emergency dispatch, continuous human monitoring, or guaranteed notification delivery.

The canonical policy register is maintained in [`docs/policies/`](../docs/policies/). `MB-CAPSTONE-SCREENING-PUBLICATION-001` defines a bounded evidence gate for controlled local/demo publication; a Capstone decision is not executable production approval.

Questionnaire publication does not depend on SupportPlan or specialist workflow. Each capability follows its own gate. Publishing localized content must update the source artifact/provenance record, tests, configuration, append-only migrations/data dictionary when needed, and this README together. ADR 0012 requires the forward plan to be system-proposed from domain-aware evaluation and exact eligible content; ADR 0013 freezes the product policy and still does not change active v1 contracts or rows. Public real-user deployment additionally requires production privacy, retention, security, legal, safety-content, and operational review.

MB-89 implements the deterministic PHQ-9 runtime. MB-178 adds the backend-owned, versioned privacy disclosure and `PRIVACY_POLICY`; Story 1102 makes `privacy-capstone-v3` current for PHQ-9/GAD-7. MB-367 introduced the independent `AI_PROCESSING` stream. MB-370 makes `ai-processing-capstone-v2` current and extends the explicit user-request scope to an already saved private ConsultationBrief plus minimized PHQ-9/GAD-7 levels. The AI output remains an editable suggestion and cannot save, approve, share, grant access, change appointments, score answers, or create clinical conclusions.

## Integration

- Inbound REST: the canonical Care OpenAPI file is the source of truth.
- Inbound specialist continuity REST: MB-592 exposes
  `GET /api/v1/specialist/client-continuity`. Care composes the bounded
  Consultation relationship authority with only its own display name and
  current ConsultationBrief access provenance. The response contains no raw
  journal, screening answers, AI analysis, SupportPlan state, chat content, or
  another specialist's notes. Dependency failure, suspension, revocation,
  deletion, schedule/version drift, early access, and expiry remain explicit
  fail-closed states; the browser cannot create or extend access.
- Inbound Support Guide REST: `care-support-guide-v1.yaml` defines authenticated generation, owner-only history/detail, idempotency, immutable provenance, and stable resource-resolution states. It is intentionally separate from SupportPlan lifecycle.
- Inbound SupportPlan v3 REST: the canonical Care OpenAPI exposes owner-only
  v3 plan/detail and bounded occurrence reads. `NATIVE_V3` identifies native
  actions; `V2_RESOURCE_ADAPTER` identifies the non-destructive v2 projection.
  The response separates Care orchestration from bounded source/evidence facts
  and never embeds an owner-domain payload.
- Outbound REST: the consumer-owned OpenFeign Resource Eligibility v1 adapter queries Content with the end-user bearer context, explicit correlation, 500 ms connect and 2 s read deadlines, bounded exponential transient retry with jitter, and a Resilience4j circuit breaker. HTTP 429 is not retried because the provider contract does not define `Retry-After`; timeout, dependency errors, malformed payloads and enum evolution map every candidate to `UNAVAILABLE`. Callers must commit no proposal mutation. No Care transaction spans the call.
- Outbound reassessment REST: the consumer-owned Journal/AI adapter forwards the verified end-user bearer and correlation ID to the canonical `REASSESSMENT_SUMMARY` projection. It applies a 200 ms connect deadline, 800 ms read deadline, at most one transient retry, and a separate circuit breaker. Startup rejects overrides whose conservative two-attempt budget exceeds 2.5 seconds, preserving time for Care to return the explicit safe fallback before the three-second caller deadline. It validates attribution, exact periods, source counts, coverage sufficiency, directions, and provenance before persistence. No transaction spans the remote call; every safe fallback is snapshotted explicitly.
- Outbound Support Guide phrasing REST: Care forwards the verified end-user bearer and only the exact approved explanation copy to Journal/AI. The call has bounded connect/read deadlines, no retry, and a fail-fast circuit breaker. Current `AI_PROCESSING` consent and real-provider approval remain Journal/AI responsibilities; any rejection, timeout, open circuit, malformed output, or provider failure persists the exact Care-approved fallback with `AI_UNAVAILABLE_FALLBACK`.
- Outbound ConsultationBrief AI draft REST: an owner-only asynchronous Care job sends one exact saved draft version and minimized screening levels to Journal/AI with the verified bearer. Care persists source and model provenance, retries a transient provider failure once, rejects stale results, and never mutates or shares the brief from the worker.
- Async: future assessment, support, consent, intervention, and follow-up events use Kafka with a transactional outbox and language-neutral schemas.
- Discovery: Care registers as `care-service`; registry metadata never grants authorization.

## Run and test

```powershell
.\mvnw.cmd test
```

The PostgreSQL integration suite applies Liquibase to a disposable real PostgreSQL database and validates owner isolation, profile optimistic concurrency, append-only consent history/idempotency/revocation, disclosure enforcement, stable history pagination, deterministic compatible progress selection, support-routing evidence validation and concurrency/idempotency, Support Guide safety/resource failure/history/ownership behavior, immutable Reassessment Summary creation/replay/current/history behavior and source-change independence, policy lookup serialization and fallback behavior, seed data, authenticated-versus-anonymous ownership, scoring boundaries, item-9 independence, token isolation/expiry, answer/result ranges, questionnaire version uniqueness, and minimized outbox payloads. Resource eligibility and Journal/AI consumer tests cover auth/correlation propagation, documented failures, deadlines, bounded retry, breaker opening, malformed data, response attribution, source deletion, consent denial, and safe fallback. Live Eureka registration is disabled in tests. The versioned assessment and support-tier event contracts exist, while a Kafka relay remains a separate delivery slice; scoring, progress, support routing, Support Guide safety, and local reassessment dimensions never wait for a broker.
