# SupportPlan policy v1

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SUPPORT-PLAN-001` |
| Selection policy version | `mb-support-plan-selection-v1` |
| Status | `PRODUCT POLICY APPROVED; RUNTIME NOT IMPLEMENTED` |
| Effective decision date | 2026-09-13 |
| Owner | Care |
| Resource eligibility owner | Content/Notification |
| Applies to | Registered users with a compatible domain-aware SupportEvaluation |
| Decision | [ADR 0013](../adr/0013-freeze-support-plan-policy-v1.md) |

This policy defines a non-clinical platform support proposal. It does not define
a diagnosis, prescription, treatment plan, recovery outcome, emergency
response, specialist booking, or continuous monitoring capability.

## Owned policy and runtime facts

`SupportPlanTemplate` is immutable versioned Care policy data. A published
version is never edited. A change retires the existing version and publishes a
new version; V1 has no administrator CRUD.

Each template policy version has at least:

| Field | Meaning |
| --- | --- |
| `templateId` | Stable Care-owned family identifier |
| `templateVersion` | Immutable version used for audit and replay |
| `status` | `DRAFT`, `PUBLISHED`, or `RETIRED`; only published versions seed new proposals |
| `effectiveAt` / `expiresAt` | UTC admission window; expiry may be absent |
| `domain` | One approved screening domain |
| `family` | One approved template family for that domain and band group |
| `slots` | Ordered immutable `CORE` and `OPTIONAL` slot definitions |

A user-owned `SupportPlan` stores the exact source SupportEvaluation,
`selectionPolicyVersion`, composed template family/version references, selected
exact resource versions, eligibility evidence, status, and optimistic version.
Those runtime fields require a later contract and append-only Care migration.

## Template families

| Family | Input | Core policy | Optional policy | External presentation |
| --- | --- | --- | --- | --- |
| `DEPRESSIVE_MAINTENANCE` | PHQ-9 Minimal | One low-burden `PRIMARY` resource eligible for `DEPRESSIVE_SYMPTOMS` | 0-2 adjunct slots | None required by this family |
| `DEPRESSIVE_SELF_GUIDED` | PHQ-9 Mild | One depression psychoeducation slot and one depression-support activity slot, each filled by `PRIMARY` eligibility | 0-2 adjunct slots | None required by this family |
| `DEPRESSIVE_PROFESSIONAL_ADJUNCT` | PHQ-9 Moderate or higher | No plan core slot; self-help is adjunct only | 0-3 adjunct slots | Professional-support recommendation before plan controls |
| `ANXIETY_MAINTENANCE` | GAD-7 Minimal | One low-burden `PRIMARY` resource eligible for `ANXIETY_SYMPTOMS` | 0-2 adjunct slots | None required by this family |
| `ANXIETY_SELF_GUIDED` | GAD-7 Mild | One anxiety psychoeducation slot and one anxiety-support activity slot, each filled by `PRIMARY` eligibility | 0-2 adjunct slots | None required by this family |
| `ANXIETY_PROFESSIONAL_ADJUNCT` | GAD-7 Moderate or higher | No plan core slot; self-help is adjunct only | 0-3 adjunct slots | Professional-support recommendation before plan controls |

`SAFETY_OVERLAY` is explicitly not a template family. PHQ-9 item-9 safety
guidance and available immediate/professional support stay outside the
SupportPlan aggregate and precede its controls.

For maintenance families, a resource described as general wellbeing may fill
the core slot only when its exact version has been explicitly reviewed as
`PRIMARY` for that target domain. A generic type or `ADJUNCT` role is
insufficient.

## Slot and resource rules

### `CORE`

- Activation requires exactly one selected resource in each composed core slot.
- The exact selected version must have `PRIMARY` eligibility for the slot domain
  and satisfy its instrument band, pathway, locale, publication state, and
  effective window.
- The user may select only an alternative returned for that slot by Care.
- A core slot cannot be removed.

### `OPTIONAL`

- The server may propose one selected resource and bounded alternatives.
- The user may keep, swap, or remove the slot selection.
- An exact `ADJUNCT` or `PRIMARY` version may fill an optional slot when all
  other eligibility dimensions match.

### Bounds

- An activatable plan contains at least 1 and at most 5 selected resources.
- A domain family uses 1-2 core slots and 0-3 optional slots where applicable.
- The normal proposal target is 2-3 selected resources, not the maximum.
- A professional-adjunct family may propose 1-3 optional resources with no core
  slot. The last selected optional cannot be removed while retaining that
  draft; the user discards the draft instead, and no plan is activated.
- Care, not the client, deduplicates composed candidates and enforces the cap.

## Resource eligibility roles

| Role | May fill a domain core slot | May fill an optional slot | Meaning |
| --- | --- | --- | --- |
| `PRIMARY` | Yes, for its exact approved target domain and conditions | Yes | The exact content version may provide domain-specific core support |
| `ADJUNCT` | No | Yes | The exact content version is supplementary only |

An actual content version must be reviewed separately. The following type-level
baseline guides that review and never grants eligibility automatically:

| Resource type | Depression | Anxiety |
| --- | --- | --- |
| Domain-specific psychoeducation | `PRIMARY` | `PRIMARY` |
| Behavioural activation | `PRIMARY` | `ADJUNCT` |
| Problem solving | `PRIMARY` or `ADJUNCT` | `ADJUNCT` |
| Breathing or relaxation | `ADJUNCT` | `PRIMARY` |
| Grounding | `ADJUNCT` | `PRIMARY` or `ADJUNCT` |
| Mindfulness | `ADJUNCT` | `PRIMARY` or `ADJUNCT` |
| Sleep wellbeing | `ADJUNCT` | `ADJUNCT` |
| Physical activity | `PRIMARY` or `ADJUNCT` | `ADJUNCT` |
| Journaling | `ADJUNCT` | `ADJUNCT` |
| Social connection | `ADJUNCT` | `ADJUNCT` |
| Professional-support preparation | `ADJUNCT` | `ADJUNCT` |

Where the baseline allows two roles, the reviewer must choose one explicit role
for each domain and exact content version.

## Deterministic selection and composition

Care executes these ordered rules under `mb-support-plan-selection-v1`:

1. Resolve one owned immutable compatible SupportEvaluation v2.
2. Preserve each contributing instrument, domain, screening level, reason code,
   and independent safety evidence; never derive a global severity.
3. Select at most one approved template family per contributing domain.
4. Compose their core and optional slots without creating a combined-band
   template family.
5. Query Content/Notification for exact currently eligible resource versions.
6. Fill every satisfiable core slot with a deterministic default and expose
   bounded eligible alternatives for user choice.
7. Propose optional resources, deduplicate exact versions and semantically
   duplicate slot purposes, then apply stable policy priority until no more than
   5 selected resources remain.
8. Create one system-proposed `DRAFT` only when the result satisfies the draft
   and per-user uniqueness rules. A client supplies only the owned evaluation
   reference.

Missing core eligibility produces an explicit unavailable proposal outcome; it
never falls back to an adjunct or an unreviewed resource. Content timeout,
malformed response, or uncertain eligibility fails closed and commits no draft.

## Lifecycle invariants

Per user:

- at most one `DRAFT` exists;
- at most one current plan exists across `ACTIVE` and `PAUSED`;
- `COMPLETED` and `SUPERSEDED` history is unlimited;
- a current plan and one replacement draft may coexist until explicit
  replacement.

| Current state | Allowed command | Result |
| --- | --- | --- |
| none | propose | Create one system-proposed `DRAFT` if policy and providers pass |
| `DRAFT` | change allowed choice | Remain `DRAFT`; revalidate the requested alternative and increment version |
| `DRAFT` | remove optional choice | Remain `DRAFT`; core and total activation bounds still apply |
| `DRAFT` | discard | Remove it from current draft consideration while retaining required audit evidence |
| `DRAFT` | activate | Become `ACTIVE` after full revalidation and explicit user command |
| `ACTIVE` | pause | Become `PAUSED` |
| `PAUSED` | resume | Become `ACTIVE` after current-plan validation |
| `ACTIVE` or `PAUSED` | complete | Become `COMPLETED`; no recovery claim |
| current plan plus replacement `DRAFT` | replace | In one Care transaction, old becomes `SUPERSEDED` and replacement becomes `ACTIVE` |
| `COMPLETED` or `SUPERSEDED` | mutate | Reject; historical state is terminal |

Creating a new assessment or SupportEvaluation never changes a SupportPlan. A
second proposal cannot create another draft; the user must keep, change, or
discard the existing draft first.

## Activation and replacement revalidation

Before opening the Care mutation transaction, Care resolves and validates:

- ownership and compatibility of the immutable SupportEvaluation;
- exact selection-policy and template versions and their effective state;
- every selected resource ID and content version;
- target-domain `PRIMARY` or `ADJUNCT` role and slot compatibility;
- instrument band, support pathway, locale, publication state, and effective
  window;
- all core slots, total resource bounds, and per-user draft/current-plan
  invariants.

Care then obtains the required local locks and commits the plan state,
idempotency outcome, history, and minimized outbox fact atomically. It never
holds a Care database transaction or lock across the Content REST call.

Safety-positive activation uses the same explicit `Start this support plan`
action without another checkbox. The presentation order is safety guidance,
available immediate/professional support, then the secondary SupportPlan. Copy
must state that the plan does not replace safety guidance or professional
support.

## Acceptance examples

| Evidence | Composition | Valid result |
| --- | --- | --- |
| PHQ-9 Mild, GAD-7 Moderate, safety negative | `DEPRESSIVE_SELF_GUIDED` plus `ANXIETY_PROFESSIONAL_ADJUNCT` | Depression core slots plus bounded anxiety adjunct options; professional recommendation outside the plan |
| PHQ-9 item 9 positive | Domain families plus separate safety overlay | Safety guidance and available immediate/professional support precede plan controls; normal explicit activation remains possible |
| Depression core has only Sleep and Journaling adjuncts | Core slot cannot be filled | Explicit proposal-unavailable result; no draft committed |
| Composed eligible candidates exceed five | Stable priority, semantic deduplication, exact-version deduplication | At most five server-selected resources; client does not truncate |
| Active plan and newer evaluation | No automatic lifecycle command | Existing plan remains unchanged until the user creates and confirms a replacement |

## Runtime gates

This policy is approved design input, not executable runtime. Runtime remains
unavailable until compatible SupportEvaluation v2, Resource Eligibility v1,
initial item-level eligibility, SupportPlan proposal/lifecycle contracts,
append-only Care persistence, provider/consumer tests, and controlled frontend
evidence pass their separate gates.
