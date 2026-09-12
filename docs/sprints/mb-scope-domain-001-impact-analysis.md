# MB-SCOPE-DOMAIN-001 impact analysis

## Purpose and status

This document records the repository impact of the Product Owner's 2026-09-12
scope correction in [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md).
It is an implementation stop/go guide, not an executable API or migration.

MentalBridge V1 moves from an implied “general mental-health severity to generic
support plan” model to two explicit screening domains followed by domain-aware,
system-proposed support. Current production code is not changed by this audit.

## Current-state audit and conflict matrix

| Item | Current assumption | Conflict | Disposition | Recommended action |
| --- | --- | --- | --- | --- |
| Story 4101 | The user supplies one SupportEvaluation ID and arbitrary reviewed resource versions to create a draft | Yes | `REVISE` | System proposes the draft from approved domain-aware policy; user choices are bounded; replace the current proposal before implementation ([#49](https://github.com/NgyenKhoi/mentalbridge-backend/issues/49)) |
| Story 4102 | Resources already propose target domains, bands and tiers | Partial | `REVISE` | Preserve ownership/versioning; explicitly prohibit universal eligibility and approve primary-domain versus general-adjunct semantics ([#50](https://github.com/NgyenKhoi/mentalbridge-backend/issues/50)) |
| Story 4103 | UI lets the user select eligible resources and then create a draft | Yes | `REVISE` | Render a server proposal first, then controlled choices and revalidation; keep safety before plan controls ([#51](https://github.com/NgyenKhoi/mentalbridge-backend/issues/51)) |
| Story 4104 | Specialist availability is an independent Consultation capability | No | `KEEP` | Keep independently mergeable; availability does not imply plan selection, booking, or safety response |
| Story 4105 | Immediate help is independent of SupportPlan and optional services | No | `KEEP` | Reword references so safety is a cross-cutting layer, not a third domain or plan state |
| MB-179 blueprint | Uses “severity-to-support” terminology and a band threshold to resolve a coarse tier | Partial | `REWORD` + `REVISE` | Preserve historical Review 1 decisions; define two-domain scope and state that a tier alone cannot select resources or a plan |
| MB-180 reviewed resources | Publishes reviewed generic resources with safe empty/unavailable fallback | No for existing reads | `KEEP` | Do not infer SupportPlan eligibility from review/publication; evolve additively through Story 4102/#50 |
| MB-238 resource administration | Owns draft/review/publish/archive lifecycle | No for existing administration | `KEEP` | Preserve current compatibility; future publication must validate separately approved eligibility metadata |
| MB-271 SupportEvaluation v1 | Persists an explicit PHQ-9/GAD-7 pair, separate bands/safety and one coarse support tier | Partial | `KEEP` historical + `REVISE` forward use | Do not rewrite v1; create a compatible domain-aware policy/contract version before it drives a SupportPlan ([#48](https://github.com/NgyenKhoi/mentalbridge-backend/issues/48)) |
| MB-287 | No authoritative MB-287 artifact or mapping exists on current `dev` | Unknown | `NEW TASK NEEDED` for traceability | The Jira owner must link/export the task before its assumptions can be classified; #47 records this gap |
| SupportEvaluation | Instrument evidence is separate, but domain contribution is implicit and downstream plan suitability is not defined | Partial | `REVISE` | Make domain contribution/reasons explicit in a new immutable version; retain no global severity |
| Resource model | Review/publication metadata exists; domain/band/tier eligibility does not yet exist in the active model | Yes for new plans | `REVISE` | Add reviewed, versioned eligibility through a compatible Content contract and append-only migration |
| SupportPlan proposal | Client-authored resource list creates the draft | Yes | `REVISE` | Stop contract freeze; replace with system proposal plus controlled choices after policy decisions |
| Reassessment/progress | New results remain immutable and do not rewrite earlier records | No | `KEEP` | A new evaluation may offer a new proposal but never silently change an existing plan |
| Follow-up/reminders | Automatic cadence is deferred | No | `DEFER` | Keep outside Story 4101 until opt-in/out, cadence and delivery policy are approved |

## Corrected blueprint flow

```text
PHQ-9 result -> DEPRESSIVE_SYMPTOMS + instrument-specific screeningLevel
GAD-7 result -> ANXIETY_SYMPTOMS + instrument-specific screeningLevel
PHQ-9 item 9 -> independent cross-cutting safety evidence

selected immutable evidence
  -> Care SupportEvaluation
  -> domain-aware pathway and stable reasons
  -> approved template/resource policy
  -> system-proposed DRAFT SupportPlan
  -> user reviews allowed choices
  -> Care revalidates exact versions and policy
  -> explicit user activation
  -> ACTIVE / PAUSED / COMPLETED or explicit replacement
```

The 40 possible PHQ-9/GAD-7/safety input combinations illustrate the input
state space; they do not authorize 40 hard-coded plan templates. Policy should
compose instrument/domain contribution, pathway, safety priority and resource
eligibility. Adding another domain requires a separate product decision and
evidence vertical.

## Required document and contract corrections

| Artifact | Correction in #47 | Runtime follow-up |
| --- | --- | --- |
| ADR 0009 | Mark as amended while preserving its safety/scoring decisions | None |
| MB-179 blueprint and closure matrix | Replace generic severity-to-support wording; add V1 scope and system-proposed plan boundary | #48 and #49 |
| Care support policy | Separate existing coarse v1 routing from future domain-aware plan selection | #48 and #49 |
| Domain/module/traceability docs | Add two-domain vocabulary, owners, out-of-scope conditions and compatibility rule | None until contracts change |
| Care OpenAPI and support event v1 | No change; they describe existing executable history | Compatible v2 under #48 |
| Story 4101 SupportPlan proposal | Do not promote the current draft | Rewrite after #49 decisions |
| Content OpenAPI/database | No change in this docs PR | Additive contract/migration under #50 |
| Frontend contracts/UI | No change in this backend docs PR | Consumer work under #51 after providers stabilize |

## Decisions that remain unresolved

| Question | Why it matters | Options requiring Product Owner review |
| --- | --- | --- |
| Is `SupportPlanTemplate` persisted? | Determines versioning, migration and admin ownership | Immutable Care policy data; persisted Care aggregate; deliberately deferred abstraction |
| What are required and optional resources? | Defines what users may remove/add before activation | Required set plus optional set; minimum categories; no distinction in V1 |
| How many resources may be selected? | Affects usability and validation | Fixed maximum; template-specific bounds; policy-calculated bounds |
| How is a template family selected? | Determines auditability and change control | Versioned code rules; persisted reviewed mapping |
| Does safety-positive activation need extra confirmation? | Affects safety priority without turning the plan into crisis monitoring | Normal activation plus persistent safety guidance; separate reviewed acknowledgement |

No option in this table is approved by this document. Issue
[#49](https://github.com/NgyenKhoi/mentalbridge-backend/issues/49) owns the
decision record before SupportPlan contract or persistence work resumes.

## Delivery issues

- [#47](https://github.com/NgyenKhoi/mentalbridge-backend/issues/47): repository documentation and impact audit.
- [#48](https://github.com/NgyenKhoi/mentalbridge-backend/issues/48): compatible domain-aware SupportEvaluation evolution.
- [#49](https://github.com/NgyenKhoi/mentalbridge-backend/issues/49): system-proposed SupportPlan decisions and contract.
- [#50](https://github.com/NgyenKhoi/mentalbridge-backend/issues/50): reviewed resource eligibility metadata.
- [#51](https://github.com/NgyenKhoi/mentalbridge-backend/issues/51): frontend result-to-proposal journey.
