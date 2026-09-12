# ADR 0012: Two-domain screening and system-proposed SupportPlans

- Status: Accepted
- Date: 2026-09-12
- Decision ID: `MB-SCOPE-DOMAIN-001`
- Tracks: [#47](https://github.com/NgyenKhoi/mentalbridge-backend/issues/47)
- Amends: [ADR 0009](0009-care-screening-safety-and-support-boundaries.md)

## Context

MentalBridge was increasingly described as though one questionnaire band could
represent a person's overall mental health and select a generic support plan.
That interpretation is not supported by the executable product. The controlled
Capstone runtime has two standardized screening instruments with different
meanings:

- PHQ-9 screens the `DEPRESSIVE_SYMPTOMS` domain;
- GAD-7 screens the `ANXIETY_SYMPTOMS` domain, focused on generalized anxiety
  symptoms.

`MILD`, `MODERATE`, and the other screening bands are meaningful only together
with their instrument and domain. Equal bands from PHQ-9 and GAD-7 do not imply
the same support need or resource eligibility. Adding another mental-health
domain would require its own instrument provenance, scoring, interpretation,
safety considerations, routing policy, resource evidence, reassessment rules,
and tests. It is therefore a new product vertical rather than an enum addition.

The first SupportPlan proposal also allowed the user to submit an arbitrary set
of reviewed resource versions to create a draft. That makes the user the initial
author of a support plan and bypasses the domain-aware policy boundary the
product is intended to provide.

## Decision

### V1 product boundary

MentalBridge V1 supports exactly two screening and post-screening support
domains for the primary Capstone cohort of adults aged 18 through 30 residing
in Vietnam:

| Domain | Instrument | Meaning |
| --- | --- | --- |
| `DEPRESSIVE_SYMPTOMS` | PHQ-9 | Depressive symptom frequency within the instrument's reference period |
| `ANXIETY_SYMPTOMS` | GAD-7 | Anxiety symptom frequency, focused on generalized anxiety symptoms, within the instrument's reference period |

The boundary does not claim that these are the only mental-health concerns. V1
does not claim specialized screening for OCD, trauma/PTSD, panic disorder,
social anxiety, bipolar/mania, psychosis, eating disorders, substance use,
ADHD, insomnia as an independent domain, personality disorders, or another
unapproved concern. Product copy must state that the current screenings do not
fully assess an out-of-scope concern and may offer a voluntary professional
evaluation path when that capability is genuinely available.

### Independent evidence and safety

Every screening result keeps its own `instrument`, `domain`, `screeningLevel`,
questionnaire/scoring provenance, and reference-period meaning. No API, event,
database field, UI, analytics view, or AI output may create
`mentalHealthLevel`, `overallSeverity`, `generalMentalHealthSeverity`, a
combined mental-health score, or equivalent wording.

Safety is a cross-cutting layer, not a third screening domain. The current rule
is derived from PHQ-9 item 9 and remains independent of the PHQ-9 band. A
positive safety signal changes presentation priority, reviewed safety guidance,
and the available professional/immediate-help path. It never changes a PHQ-9 or
GAD-7 score or band and never establishes diagnosis, intent, plan, imminence, or
urgency. Safety guidance precedes ordinary resources and SupportPlan controls.

### Domain-aware support evaluation

The forward product model is:

```text
instrument-specific ScreeningResult(s)
  + domain
  + screeningLevel
  + independent safety evidence
  -> immutable SupportEvaluation
  -> domain-aware support pathway
  -> approved template/resource eligibility
```

`supportTier` remains a product pathway, not a severity label. A tier by itself
is insufficient to choose a resource or SupportPlan. An evaluation must retain
the contributing domains and stable instrument-specific reason codes so an
explanation never collapses two results into one health classification.

The executable `mb-support-routing-capstone-v1` evaluation and its historical
records remain immutable and readable. This decision does not silently
reclassify them or alter the active REST/event contract. Any new domain-bearing
shape or routing semantics require a compatible policy and contract version
tracked by [#48](https://github.com/NgyenKhoi/mentalbridge-backend/issues/48).

### System-proposed SupportPlan

The approved conceptual flow is:

```text
SupportEvaluation
  -> domain-aware support pathway
  -> approved template/resource policy selects a bounded proposal
  -> system-proposed DRAFT SupportPlan
  -> user reviews choices allowed by that proposal
  -> Care revalidates current eligibility
  -> user explicitly activates
  -> ACTIVE -> PAUSED -> ACTIVE -> COMPLETED
```

The system defines the safe support boundary; the user chooses preferences
inside it. `DRAFT` means a proposal produced by MentalBridge which the user has
not activated. It does not mean the user authored a treatment plan. Clients do
not submit a support tier, template family, domain eligibility decision, or an
arbitrary initial resource set.

Completing a SupportPlan means its selected period ended; it does not mean
recovery. A new assessment or SupportEvaluation never silently rewrites,
supersedes, or activates an existing plan. Normal activation and replacement
remain explicit user commands. The revised contract and unresolved policy
decisions are tracked by [#49](https://github.com/NgyenKhoi/mentalbridge-backend/issues/49).

### Resource eligibility

A resource is not universally eligible because it is reviewed or belongs to a
generic category. New-plan selection requires an exact reviewed content version
whose approved eligibility accounts for domain, applicable instrument band,
support pathway, locale, publication state, and effective window. Cross-domain
wellbeing resources remain possible, but their applicability must be reviewed
and versioned rather than inferred.

Content/Notification owns resource definitions and review provenance. Care owns
the SupportEvaluation, proposal selection policy, plan lifecycle, and final
eligibility decision for a plan. Neither service reads the other's database.
The resource contract work is tracked by
[#50](https://github.com/NgyenKhoi/mentalbridge-backend/issues/50).

## Open decisions before executable SupportPlan work

This ADR deliberately does not decide:

1. whether `SupportPlanTemplate` is a persisted aggregate or immutable
   versioned policy data;
2. the exact meaning of required and optional proposed resources;
3. the minimum and maximum number of user choices;
4. whether template selection is expressed as code rules or persisted mapping;
5. whether safety-positive activation requires confirmation beyond the normal
   SupportPlan activation confirmation.

The Product Owner and accountable Care/Content reviewers must resolve these in
[#49](https://github.com/NgyenKhoi/mentalbridge-backend/issues/49) before an
OpenAPI proposal is frozen or runtime implementation begins.

## Consequences

- MB-179 terminology and the Sprint 3 backlog must be read through this
  correction; “severity-to-support” is replaced by domain-aware support.
- The unmerged Story 4101 proposal that accepts arbitrary resource versions is
  not an approved implementation contract and must be revised rather than
  promoted.
- Active SupportEvaluation v1 contracts, migrations, events, and historical
  rows are not edited by this documentation change.
- Resource metadata and SupportEvaluation/SupportPlan evolution use compatible
  contract versions and append-only owner migrations after their policies are
  approved.
- Safety, scoring, assessment ownership, non-diagnostic wording, no automatic
  contact, no silent plan rewrite, and AI's supporting-only role remain intact.
- Schedules, reminders, activity tracking, streaks/gamification, AI
  personalization, and additional screening domains remain outside Story 4101.

## Rejected alternatives

- Map `MILD`, `MODERATE`, or another band directly to one generic plan: rejected
  because the same band has instrument-specific meaning.
- Generate one template for every combination of bands and safety state:
  rejected because it creates an unmaintainable combination matrix instead of
  compositional policy.
- Let users freely author a plan from arbitrary reviewed resources: rejected
  because review alone does not establish domain eligibility.
- Treat safety as another screening domain or upgrade a band when safety is
  positive: rejected because safety and symptom frequency are independent.
- Add more disorders by extending enums: rejected because each domain requires
  a separately governed product vertical.
