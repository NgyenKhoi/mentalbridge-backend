# PHQ-9 screening and item-9 safety policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SAFETY-PHQ9-001` |
| Policy version | `1.0-capstone` |
| Status | `CAPSTONE PUBLISHED` |
| Capstone publication authority | Product Owner under [`MB-CAPSTONE-SCREENING-PUBLICATION-001`](capstone-questionnaire-publication-policy.md) |
| Capstone effective date | 2026-09-02 |
| Product Owner decision | Approved by the MentalBridge Project Lead through the MB-177 implementation authorization on 2026-09-02 |
| Supervisor/domain review | Recommended academic evidence review; not a Capstone publication blocker |
| Production review | Domain, privacy, legal, safety-content, and operational review required before public real-user deployment |
| Applies to | PHQ-9 for the initial target population of adults aged 18–30 in Vietnam |
| Locale | `vi-VN` |
| Display timezone | `Asia/Ho_Chi_Minh` |
| Owning service | Care Service |
| Supersedes | N/A |
| Architecture decisions | [ADR 0009](../adr/0009-care-screening-safety-and-support-boundaries.md); [ADR 0012](../adr/0012-two-domain-screening-and-system-proposed-support-plans.md) |

This policy specifies screening behavior, not diagnosis, treatment, suicide-risk stratification, emergency dispatch, or continuous human monitoring. The instrument, scoring research basis, and exact Vietnamese Capstone artifact have passed the bounded evidence gate. Optional support and production deployment follow separate gates.

For V1 post-screening support, PHQ-9 produces evidence for the `DEPRESSIVE_SYMPTOMS` domain only. It is not a general mental-health assessment, and its band cannot be combined with another instrument into global severity.

## Terminology

- `screeningLevel` means **instrument-specific screening symptom severity** (`mức độ triệu chứng qua sàng lọc`) for `DEPRESSIVE_SYMPTOMS`. It is not disease severity, global severity, or a diagnosis.
- `safetyStatus` reports the deterministic item-9 cross-cutting safety screen independently of `screeningLevel`; it is not a third domain.
- `supportTier` determines a reviewed support pathway. It is not a suicide-risk label and is governed by a separate approved policy.
- `supportActions` are selected only from versioned, reviewed catalogue entries.
- `entitlementPlan` controls commercial feature access and never suppresses scoring, disclaimers, safety status, or safety guidance.

## Questionnaire version and provenance

The current published controlled-Capstone identifier is `phq9-vi-vn-capstone-v2`. Story 1102 corrected question 2 to `Cảm thấy chán nản, buồn rầu hoặc vô vọng` in this new immutable definition. That correction was approved by the Product Owner and is not claimed verbatim from the archived SBIRT artifact. The original `phq9-vi-vn-capstone-v1` definition is `RETIRED`, remains readable for historical results, and retains its exact original content and provenance without mutation.

Artifact evidence:

- Source: *PATIENT HEALTH QUESTIONNAIRE-9 (PHQ-9 Vietnamese)* distributed by SBIRT Oregon.
- Stable archived retrieval: `https://web.archive.org/web/20240720104123id_/https://www.sbirtoregon.org/wp-content/uploads/PHQ-9-Vietnamese.pdf`.
- Archive timestamp: `2024-07-20T10:41:23Z`; retrieved for MB-177 on `2026-09-02`.
- SHA-256: `E2775444E5AB4A05C3FF097F1CAB356C2DA9ECC73BAC63E91827BAA77E965FF7`.
- Use statement in the artifact: no permission is required to copy, translate, display, or distribute.
- Original executable source: [`005-phq9-vi-vn-reference-data.sql`](../../care-service/src/main/resources/db/changelog/changes/005-phq9-vi-vn-reference-data.sql).
- Corrective v2 source and lifecycle transition: [`008-gad7-and-phq9-v2-reference-data.sql`](../../care-service/src/main/resources/db/changelog/changes/008-gad7-and-phq9-v2-reference-data.sql).

The artifact preserves the canonical item-9 concepts of being better off dead or self-harm. Its Vietnamese phrasing is accepted by the Product Owner for the bounded academic demo, while language/domain review remains a production follow-up because several phrases are mechanically worded.

Candidate provenance for domain review:

- Kroenke K, Spitzer RL, Williams JBW. *The PHQ-9: Validity of a Brief Depression Severity Measure*. 2001. DOI: [10.1046/j.1525-1497.2001.016009606.x](https://doi.org/10.1046/j.1525-1497.2001.016009606.x).
- Phi HNY et al. Vietnamese PHQ-9 validation in primary healthcare settings. 2023. DOI: [10.12809/eaap2258](https://doi.org/10.12809/eaap2258).
- Le Hoang Ngoc Tram et al. Vietnamese PHQ-9 validation in adults with epilepsy. 2021. DOI: [10.1016/j.yebeh.2021.108446](https://doi.org/10.1016/j.yebeh.2021.108446).

Additional distribution and localization evidence:

- Pfizer states that PHQ tools may be downloaded without a formal permission request when its Terms of Use are accepted and that approved translations are available through PHQ Screeners: [Pfizer FAQ](https://www.pfizer.com/contact/faqs).
- The NIMH Data Archive PHQ-9 Common Data Element lists `Vietnamese for Vietnam`: [PHQ-9 data-structure history](https://nda.nih.gov/data_structure_history.html?short_name=cde_phq901).

A language listing alone does not substitute for the exact artifact. MentalBridge therefore persists the archived artifact evidence above with the executable definition.

Validation in a particular clinical population does not by itself establish diagnostic performance for every Vietnamese adult. MentalBridge therefore displays the result only as a screening result.

## Deterministic scoring

Each of the nine required answers is an integer from `0` through `3`, representing increasing frequency during the preceding 14 days. Care rejects missing, duplicate, unknown, or out-of-range answers and never accepts a client-computed score or band.

```text
totalScore = answer1 + answer2 + ... + answer9
```

The valid total is `0..27`. Care resolves the band from immutable questionnaire reference data using `scoringVersion = phq9-standard-bands-v1`:

| Total score | `screeningLevel` |
| ---: | --- |
| 0–4 | `MINIMAL` |
| 5–9 | `MILD` |
| 10–14 | `MODERATE` |
| 15–19 | `MODERATELY_SEVERE` |
| 20–27 | `SEVERE` |

These bands describe symptom frequency/severity in screening. A cutoff may support further evaluation, but no score authorizes MentalBridge to diagnose a disorder.

## Item-9 safety rule

Item 9 is the PHQ-9 item concerning thoughts of being better off dead or self-harm during the preceding two weeks. The response value represents frequency:

| Value | Canonical frequency meaning | MentalBridge v1 safety result |
| ---: | --- | --- |
| 0 | Not at all | `NEGATIVE_SAFETY_SCREEN` |
| 1 | Several days | `POSITIVE_SAFETY_SCREEN` |
| 2 | More than half the days | `POSITIVE_SAFETY_SCREEN` |
| 3 | Nearly every day | `POSITIVE_SAFETY_SCREEN` |

The table explains scoring semantics and is not the approved Vietnamese questionnaire wording.

```text
safetyStatus = answer(item9) >= 1
  ? POSITIVE_SAFETY_SCREEN
  : NEGATIVE_SAFETY_SCREEN
```

The following invariants are mandatory:

1. A positive item 9 never overwrites or elevates `screeningLevel`.
2. Values `1`, `2`, and `3` do not independently establish intent, plan, imminence, or urgency.
3. MentalBridge v1 returns the same approved immediate safety pathway for values `1`, `2`, and `3`, while preserving the raw value in the protected assessment record.
4. No positive result automatically contacts a specialist, administrator, family member, emergency service, or notification provider.
5. Optional AI, Kafka, Redis, realtime, and notification failures cannot block the synchronous result or safety guidance.

Example:

```text
totalScore = 8
item9 = 1

screeningLevel = MILD
safetyStatus = POSITIVE_SAFETY_SCREEN
```

## User-facing safety response

Safety output is available to anonymous, Free, Premium Care, and Premium Plus users. It includes the screening result, non-diagnostic disclaimer, safety status, any content approved for the active environment, and an explicit statement that MentalBridge does not provide emergency dispatch or 24/7 human monitoring.

The Capstone non-diagnostic capability statement is:

> Đây là kết quả sàng lọc triệu chứng, không phải chẩn đoán y khoa. MentalBridge không cung cấp dịch vụ ứng cứu khẩn cấp, không giám sát con người 24/7 và không tự động liên hệ bên thứ ba.

Until a versioned safety-guidance contract and catalogue pass their separate gate, the UI explicitly reports that guidance is unavailable. It does not silently return an empty area or invent a recommendation.

MentalBridge has no hotline catalogue, hotline CRUD, geolocation, or current-facility database. It must not claim that a facility is the “nearest”. A candidate fallback for legal/domain review is:

> Nếu bạn cảm thấy mình không an toàn hoặc có nguy cơ gây hại cho bản thân, hãy chủ động liên hệ dịch vụ khẩn cấp hoặc cơ sở y tế phù hợp tại khu vực của bạn.

This wording is not approved production content. It may be adopted for a controlled Capstone demo only through an explicit Product Owner content decision. A specific number such as `115` remains excluded unless a separate production legal/domain decision approves it inside versioned safety content.

Self-screening may be available 24/7. That availability never implies 24/7 human monitoring. Operating hours apply only to specialist availability and appointment slots.

## Runtime decision flow

```text
Validate published questionnaire and exact version
  -> validate all required answers
  -> calculate totalScore in Care
  -> resolve screeningLevel from versioned score bands
  -> evaluate the deterministic item-9 rule
  -> persist the result and every governing version atomically
  -> return result, disclaimer, safety status, and approved guidance synchronously
  -> start optional entitlement-aware support, AI, follow-up, or notification work
```

The persisted assessment result must include:

- `questionnaireVersion`;
- `scoringVersion`;
- `safetyPolicyVersion`;
- `totalScore`;
- `screeningLevel`;
- `safetyStatus`;
- the protected item responses and calculation instant.

No later policy publication rewrites a historical result. Re-evaluation creates new versioned evidence.

## Entitlement and fallback

Anonymous users may view their current score, screening level, disclaimer, safety status, and safety guidance. They receive no longitudinal history, specialist access, or profile-dependent personalization.

Registered Free users may receive basic support only after the separate support catalogue gate passes. Premium tiers may add deeper longitudinal personalization, advanced follow-up, booking, and consultation according to their own approved contracts. These optional capabilities do not block base questionnaire publication. Safety output and access to an owned assessment are never paywalled.

Story 1103 makes `mb-support-routing-capstone-v1` executable as immutable coarse routing. That tier does not select resources or create a SupportPlan. ADR 0012 requires a compatible domain-aware evaluation and system-proposed plan flow before personalized plan runtime becomes available. MB-205 separately implements bounded authenticated descriptive progress without changing the PHQ-9 score, safety status or support capability. The [screening-to-support blueprint](../sprints/mb-179-screening-to-support-blueprint.md) is authoritative for their Capstone boundaries.

If optional personalization is unavailable, the response uses an explicit availability status and reviewed generic guidance. It must not silently return empty output or imply that an unavailable AI, specialist, slot, notification, or emergency response succeeded.

## Acceptance examples

| Scenario | Required result |
| --- | --- |
| Nine zero answers | Score `0`, `MINIMAL`, negative safety screen |
| Score boundary 4/5 | `MINIMAL` / `MILD` respectively |
| Score boundary 9/10 | `MILD` / `MODERATE` respectively |
| Score boundary 14/15 | `MODERATE` / `MODERATELY_SEVERE` respectively |
| Score boundary 19/20 | `MODERATELY_SEVERE` / `SEVERE` respectively |
| Item 9 equals 1 with total 8 | `MILD` plus positive safety screen |
| Item 9 equals 1, 2, or 3 | Same v1 safety pathway; raw frequency retained |
| Missing, duplicate, unknown, or out-of-range answer | Reject without a partial result |
| Identical idempotent retry | Return the original result without duplicate persistence/events |
| AI, broker, cache, realtime, or notification unavailable | Deterministic result and local safety guidance still returned |
| Anonymous read after expiry | Deny access and never attach the result to an account |

## Capstone publication checklist

- [x] Original instrument and scoring evidence recorded.
- [x] Vietnamese-language availability and validation evidence identified.
- [x] Exact `vi-VN` PHQ-9 artifact, response labels, source version, retrieval evidence, and applicable use terms recorded.
- [x] Imported wording compared exactly with the recorded source; no developer translation or paraphrase.
- [x] Vietnamese non-diagnostic disclaimer and capability statement selected for the Capstone environment.
- [x] Item-9 positive/negative behavior and explicit unavailable-guidance fallback verified in UI/API tests.
- [x] Questionnaire, score-boundary, validation, idempotency, and safety-sensitive tests pass.
- [x] Product Owner records the `CAPSTONE PUBLISHED` decision and effective version.

The support-tier matrix, SupportPlan catalogue, specialist workflow, consultation features, and paid-plan behavior are not questionnaire-publication blockers. Coarse v1 support routing later passed its Story 1103 gate; domain-aware SupportPlan selection and the other capabilities remain unavailable until their own gates pass. Consent/retention review is not required for synthetic controlled demos, but it remains mandatory before public real-user data collection.

## Production follow-up

- [ ] Domain review of exact localized questionnaire and user-facing safety wording.
- [ ] Privacy, retention, security, and applicable legal review for real-user health data.
- [ ] Operational decision on production safety content and whether any specific emergency number may appear.
- [ ] Production deployment approval and effective date recorded independently from Capstone publication.

Capstone lifecycle: `DRAFT -> RESEARCH BASIS VERIFIED -> LOCALIZED CONTENT VERIFIED -> CAPSTONE IMPLEMENTATION READY -> CAPSTONE PUBLISHED`.

Production lifecycle: `PRODUCTION CANDIDATE -> DOMAIN / PRIVACY / LEGAL / OPERATIONAL REVIEW -> PRODUCTION APPROVED -> RETIRED`.
