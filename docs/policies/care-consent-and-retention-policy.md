# Care consent and assessment retention policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-PRIVACY-CARE-001` |
| Policy version | `1.0-capstone` |
| Status | `PRODUCT OWNER APPROVED — CONTROLLED CAPSTONE ONLY` |
| Effective date | 2026-09-02 for controlled local/test/demo use |
| Capstone decision | MB-178 consent, disclosure, anonymous lifetime, and bounded retention decisions approved by the Product Owner on 2026-09-02 |
| Owning service | Care Service |
| Applies to | Anonymous and registered Care assessment flows in Vietnam |

## Separate consent and disclosure paths

MentalBridge must not combine these purposes into one broad toggle:

| Purpose | Required treatment |
| --- | --- |
| Deterministic assessment processing | The user must view and acknowledge the backend-owned `privacy-capstone-v2` disclosure; this is a processing gate, not a clinical-eligibility rule, and no AI processing is implied |
| AI processing | Deferred; `AI_PROCESSING` is not exposed by the Sprint 2 UI/runtime consent flow |
| Specialist sharing | Separate revocable grant scoped to subject, specialist, data type, purpose, time range, and selected entries where applicable |
| Research use | Deferred; `RESEARCH_DATA` is not exposed and production data is excluded by default |
| Marketing notification | Deferred; `MARKETING_NOTIFICATION` is not exposed until a corresponding feature exists |

The current Care consent table reserves privacy, AI, research, and marketing decision types for compatible future evolution. Sprint 2 accepts and exposes only `PRIVACY_POLICY`. Care publishes the exact Vietnamese disclosure and version; clients must not maintain an independent copy. A registered grant or withdrawal is an append-only decision. A withdrawal takes effect for new processing immediately but neither deletes historical assessments nor rewrites audit evidence. Deletion is a separate future workflow.

The current controlled-Capstone disclosure version is `privacy-capstone-v2`; `privacy-capstone-v1` remains an immutable historical decision value. Version 2 replaces implementation-oriented wording with reviewed user-facing Vietnamese while preserving the same bounded processing purpose: profile data, PHQ-9 answers, and server-computed results support history and reassessment; results are not diagnoses; and the decision does not authorize AI, research, marketing, or specialist sharing. Public real-user deployment still requires a separately reviewed production privacy, retention, security, and legal policy.

## MB-179 specialist-sharing boundary

The Capstone blueprint permits definition and contract planning but does not authorize runtime specialist access. A future specialist handoff is registered-user-only, voluntary and user-initiated. It requires a separate revocable grant naming the subject, specialist, purpose, `ASSESSMENTS` scope, selected assessment identifiers or bounded time range, expiry and grant version.

The minimum proposed projection contains instrument/version, completion time, total score, screening level and PHQ-9 safety status where applicable. Raw answers, all past/future assessments, journal entries and emotion trends are excluded by default. An appointment or paid plan never creates consent. Every read must re-check the current grant in the data owner and record minimized audit evidence.

This boundary is definition-complete for MB-179. Runtime sharing remains unavailable until the grant contract, concurrency-safe authorization, audit behavior and required security/privacy review pass. Public real-user collection and sharing remain production-blocked; Sprint 2 validation uses synthetic/test data.

## Anonymous assessment

Required behavior:

- token is high entropy, returned once, and stored only as a hash;
- session and result are ephemeral;
- no longitudinal history;
- no specialist access;
- no AI personalization requiring a persistent profile;
- no silent attachment to a later registered account;
- expiry and cleanup are enforced by Care and cannot depend on the client clock.

Approved controlled-Capstone values:

```text
anonymous inactivity TTL = 30 minutes
anonymous maximum absolute lifetime = 2 hours
```

Valid authenticated activity extends the inactivity deadline up to, but never beyond, two hours after session creation. A new request at or after the effective deadline is rejected. An idempotent operation accepted before expiry may complete from its authoritative persisted state; expiry does not authorize a new operation or a later read. Cleanup/deletion evidence remains an operational production decision and does not permit access after expiry.

## Registered assessment retention and deletion

Sprint 2 registered history is approved only for synthetic/test/demo data. It deliberately makes no production claim about:

- maximum retention duration or user-controlled history duration;
- immediate versus grace-period deletion behavior;
- whether a voided result has a distinct audit retention period;
- treatment of minimized outbox/audit evidence after content deletion;
- backup expiry and restore behavior;
- legal basis and version owner;
- export format and deadline.

The existing null registered-retention deadline means only that the controlled demo keeps its synthetic history for reassessment. It is not an approved indefinite production-retention rule. A future production policy and migration must define retention, deletion, backup, audit-minimization, and export behavior before real-user collection is enabled.

## Approval blockers

- [x] Product Owner approved synthetic/test-data-only Sprint 2 validation and the non-executable MB-179 sharing boundary.
- [x] Product Owner approved the versioned assessment-processing disclosure and its separation from clinical eligibility.
- [x] Product Owner approved backend ownership of versioned privacy disclosure text; `privacy-capstone-v2` is current and AI, research, and marketing consent remain deferred.
- [x] Product Owner approved a 30-minute sliding inactivity deadline, two-hour absolute lifetime, and persisted idempotent completion semantics.
- [x] Product Owner approved registered history only for controlled synthetic/test/demo use without a production retention claim.
- [ ] Specialist grant policy approved before specialist reads are implemented.
- [ ] Security and legal/privacy reviewers approve a future public real-user production policy.
