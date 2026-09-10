# Sprint 2 status

## Snapshot

| Field | Value |
| --- | --- |
| Status date | 2026-09-06 |
| Lifecycle | `IN PROGRESS` |
| Nominal capacity | 250 person-hours: 5 members × 5 hours × 10 working days |
| Committed capacity | 180 hours |
| Integration/review/risk buffer | 70 hours |
| Target branch | `dev` |

## Goal

Deliver a safe, backend-authoritative PHQ-9 vertical slice for anonymous and authenticated users, and close Review 1 with a versioned and reviewed business blueprint covering the target cohort, PHQ-9/GAD-7 provenance, screening terminology, severity-to-support routing, specialist handoff, follow-up, reassessment, and descriptive progress evaluation.

Sprint 2 deliberately separates two outcomes:

1. **Runtime outcome:** PHQ-9, profile/consent/history, reviewed resources, and the Sprint 1 Realtime foundation have working evidence.
2. **Business-definition outcome:** the complete screening-to-support journey is defined, reviewed, traceable, and honest about approval or runtime gaps.

Definition-complete does not mean runtime-complete.

## Capacity and ownership

| Member | Committed work | New | Carry-over | Total |
| --- | --- | ---: | ---: | ---: |
| Member 1 | Backend-authoritative PHQ-9 frontend integration | 35h | 0h | 35h |
| Member 2 | Care profile, consent, history, and reassessment | 28h | 7h | 35h |
| Member 3 | Review 1 screening-to-support business blueprint | 35h | 0h | 35h |
| Member 4 | MB-92 and MB-93 Realtime foundations | 0h | 40h | 40h |
| Member 5 | Reviewed resources, safe fallback, E2E, and release evidence | 35h | 0h | 35h |
| **Total** |  | **133h** | **47h** | **180h** |

The 70-hour difference from nominal capacity is reserved for meetings, review, approval sessions, integration, defect correction, environment issues, and delivery uncertainty. It is not pre-approved scope for additional features.

## Committed runtime outcomes

| Outcome | Initial status | Sprint 2 acceptance direction |
| --- | --- | --- |
| Anonymous PHQ-9 | `PLANNED` | Frontend retrieves the published Care questionnaire, creates an isolated anonymous session, submits answers, and renders only server-owned result/safety fields. |
| Authenticated PHQ-9 | `PLANNED` | JWT subject ownership, idempotent submission, owned result reopening, and failure states work through a server-side frontend boundary. |
| Frontend scoring and hotline removal | `PLANNED` | Browser scoring, hard-coded severity/recommendations, and the obsolete hotline are absent from production paths. |
| Care profile and consent | `IMPLEMENTED — VERIFIED` | Own-profile optimistic concurrency, backend-owned current `privacy-capstone-v3`, immutable historical v1/v2 references, and append-only grant/revoke decisions pass the Care PostgreSQL integration suite. |
| Assessment history and reassessment | `IMPLEMENTED — VERIFIED` | Stable owned cursor pagination, exact immutable result reopening, and creation of a distinct reassessment submission pass the Care PostgreSQL integration suite and frontend delivery gates. |
| Reviewed support resources | `PLANNED` | Result pages render only published reviewed backend content and show neutral, explicit empty/unavailable fallback. |
| Realtime foundation | `CARRY-OVER` | NestJS, contracts, authentication, Redis TTL presence, MongoDB durable idempotent messages/history, and failure tests are complete; production chat eligibility remains disabled. |
| Integrated evidence | `PLANNED` | Required backend suites, frontend quality gates, focused Playwright journeys, security/degradation checks, and runbooks have reproducible evidence. |

## Review 1 business-definition outcomes

| Deliverable | Current status | Required result |
| --- | --- | --- |
| Target cohort and terminology | `DEFINITION COMPLETE — MENTOR REVIEW PENDING` | Vietnam users aged 18–30 are the primary Capstone cohort; profile/self-declaration and unsupported cases are defined without a medical cutoff claim. |
| PHQ-9/GAD-7 provenance | `CAPSTONE PUBLISHED — STORY 1102` | Current PHQ-9 v2 and GAD-7 have immutable executable definitions, source/review evidence, migration tests, API/runtime support, and frontend journeys. PHQ-9 v1 remains retired and readable for history. |
| Severity-to-support routing | `CONTROLLED CAPSTONE RUNTIME — STORY 1103` | `mb-support-routing-capstone-v1` evaluates an explicit compatible PHQ-9/GAD-7 pair, keeps every decision dimension independent, excludes AI, emits stable evidence/reasons and uses the PO-approved local safety fallback. Personalized interventions remain unavailable. |
| Specialist handoff and consent | `DEFINITION COMPLETE — RUNTIME UNAVAILABLE` | Registered, voluntary, user-initiated handoff requires entitlement, availability and minimum scoped consent; no auto-contact, booking or journal sharing. |
| Follow-up and reassessment | `DEFINITION COMPLETE — REASSESSMENT RUNTIME OWNED BY MB-178` | User initiates reassessment; Care owns future cadence; no automatic clinical reminder is hard-coded. |
| Descriptive progress | `RUNTIME COMPLETE — MB-205` | Authenticated owner-selected same-instrument/scoring-version previous/current score, raw delta, arithmetic direction, band transition and interval only; no clinical, causal, safety-resolution or combined-score claim. |
| Review 1 closure matrix | `PRODUCT OWNER APPROVED — MENTOR REVIEW PENDING` | Every authoritative MB-179 feedback item links to its decision, evidence, approval, runtime status and defer disposition. |

An evidence- or approval-gated deliverable is complete only when the requirements for its applicable environment or feature gate are recorded. If evidence is insufficient or approval is denied, the correct outcome is an explicit unavailable/unpublished decision—not an engineering default.

## Expected end-to-end flow

```text
Eligibility and versioned disclosure
  -> published PHQ-9 questionnaire
  -> anonymous session or authenticated owner
  -> raw answers submitted to Care
  -> Care-owned deterministic score and screening level
  -> independent PHQ-9 item-9 safety status
  -> definition-only support routing and reviewed-resource lookup
  -> result and truthful fallback rendered by the frontend
  -> explicit unavailable state for non-existent personalized support/specialist runtime
  -> optional professional-support recommendation when its feature gate passes
  -> future scoped consent and specialist workflow when runtime is available
  -> user-initiated reassessment
  -> authenticated descriptive progress comparison when compatible evidence exists
```

No score automatically diagnoses a condition, mandates treatment, books or notifies a specialist, shares sensitive data, or claims emergency response.

## Explicit runtime deferrals

| Deferred runtime | Reason |
| --- | --- |
| GAD-7 production deployment | Controlled-Capstone publication is complete in Story 1102. Public real-user deployment remains gated by domain, privacy, legal, security, and operational review. |
| Specialist discovery, booking, and consultation | Consultation runtime and exact eligibility/appointment policies are not implemented. Sprint 2 defines the handoff boundary only. |
| Automatic follow-up | User-initiated reassessment belongs to MB-178 and descriptive progress to MB-205. Automatic cadence remains deliberately unapproved. |
| Journal frontend CRUD | Backend CRUD remains available, but the 35-hour frontend slice moves to Sprint 3 to fund Review 1 closure. |
| AI journal analysis or clinical personalization | AI remains supporting-only and cannot score PHQ/GAD, diagnose, or override deterministic safety/support rules. |
| Production deployment/CD | Not part of this sprint commitment unless separately approved and estimated. |

## Definition of Done

- [ ] All committed runtime Stories satisfy their Acceptance Criteria and Definition of Done.
- [ ] Backend-authoritative PHQ-9 works for anonymous and authenticated users without browser scoring or obsolete hotline behavior.
- [ ] Profile, consent, assessment history, reassessment, reviewed-resource, and Realtime carry-over evidence passes.
- [x] Every authoritative Review 1 question has a versioned artifact and recorded disposition.
- [x] Required Capstone evidence decisions and applicable production, feature, domain, security, privacy, and legal approval states are recorded truthfully.
- [x] Content outside its applicable evidence or approval gate remains unpublished or explicitly unavailable.
- [x] Runtime-complete, definition-complete, approval-blocked, deferred, and out-of-scope work are distinguishable in documentation; Jira administrative updates remain open.
- [x] Contracts, migrations, data descriptions, implementation, tests, policies, service READMEs, coverage, and traceability agree where affected.
- [x] Required backend and frontend quality gates and focused E2E journeys pass with reproducible evidence.
- [ ] Related pull requests are integrated into `dev`; PR links and reviewer approvals remain release administration.
- [ ] No known Critical or High implementation or policy issue is silently left unresolved.

## Evidence to complete during the sprint

- Jira import: [`MentalBridge_Sprint2_Integrated_Screening_Import.csv`](../../MentalBridge_Sprint2_Integrated_Screening_Import.csv)
- Sprint 1 carry-over: MB-92, MB-93, and MB-105
- PHQ-9 policy: [PHQ-9 screening and safety](../policies/phq9-screening-and-safety-policy.md)
- GAD-7 policy: [GAD-7 screening](../policies/gad7-screening-policy.md)
- Routing policy: [Care support and intervention](../policies/care-support-and-intervention-policy.md)
- Consent policy: [Care consent and retention](../policies/care-consent-and-retention-policy.md)
- Architecture decision: [ADR 0009](../adr/0009-care-screening-safety-and-support-boundaries.md)
- Domain flow: [domain and use cases](../domain-and-use-cases.md)
- Coverage: [use-case and WBS coverage](../modules/coverage.md)
- MB-179 blueprint: [screening-to-support blueprint](mb-179-screening-to-support-blueprint.md)
- Review 1 evidence: [closure and traceability matrix](mb-179-review-1-closure-matrix.md)
- Product Owner approval: recorded in the blueprint and closure matrix from the 2026-09-02 MB-179 authorization
- Mentor/supervisor closure validation, Jira administrative updates, pull request links, and reviewer approvals: pending. Exact verification results are recorded in [the Sprint 2 runbook](sprint-2-runbook-traceability.md) and [integrated evidence](../sprint-2-integrated-release-evidence.md).
