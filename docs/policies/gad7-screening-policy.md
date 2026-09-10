# GAD-7 screening policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SCREEN-GAD7-001` |
| Policy version | `1.0-capstone` |
| Status | `CAPSTONE PUBLISHED` |
| Capstone publication authority | Product Owner under [`MB-CAPSTONE-SCREENING-PUBLICATION-001`](capstone-questionnaire-publication-policy.md) |
| Capstone effective date | 2026-09-09 |
| Product Owner decision | Story 1102 approves the exact mapping below and publication after implementation and verification pass |
| Supervisor/domain review | Recommended academic evidence review; not a controlled-Capstone blocker |
| Production review | Domain, privacy, legal, and operational review required before public real-user deployment |
| Applies to | Self-administered GAD-7 for the initial product cohort of adults aged 18–30 in Vietnam |
| Locale | `vi-VN` |
| Owning service | Care Service |
| Supersedes | `MB-SCREEN-GAD7-001/1.0-draft.3` |

GAD-7 is a separate symptom-screening instrument. It does not inherit PHQ-9 text, its `0..27` range, item-9 behavior, or diagnostic meaning. This publication is for the controlled local/demo Capstone environment only.

## Immutable definition and provenance

The published definition is `gad7-vi-vn-adult-v1`; its scoring version is `gad7-standard-bands-v1`, locale is `vi-VN`, and recall period is 14 days.

Artifact evidence:

- Source: *GAD-7 — Vietnamese for Vietnam — Translated by UNC Vietnam, 2024* hosted by the NIMH Data Archive.
- Source URL: `https://s3.amazonaws.com/nda.nih.gov/cms/prod/GAD7_VietnameseForVietnam_uncvn.pdf`.
- Retrieved: `2026-09-09`.
- Archived copy: [`GAD7_VietnameseForVietnam_uncvn.pdf`](sources/GAD7_VietnameseForVietnam_uncvn.pdf).
- SHA-256: `876A7245EF7BDDFC3EADFA15625E02F132560218C219E4E5251E6B7DC6A8A001`.
- Executable mapping: [`008-gad7-and-phq9-v2-reference-data.sql`](../../care-service/src/main/resources/db/changelog/changes/008-gad7-and-phq9-v2-reference-data.sql).

MentalBridge retains the seven symptom items exactly as recorded in that artifact. The interviewer instructions are excluded. The artifact's `88` (refused) and `99` (do not know) codes are not score values and are rejected; a submission must contain exactly one `0..3` answer for every item.

The self-administered response mapping approved in Story 1102 is:

| Value | Display label |
| ---: | --- |
| 0 | `Không bao giờ (0 ngày nào)` |
| 1 | `Vài ngày (1-7 ngày)` |
| 2 | `Hơn một nửa số ngày (8-10 ngày)` |
| 3 | `Gần như hàng ngày (11-14 ngày)` |

## Deterministic scoring

```text
totalScore = answer1 + answer2 + ... + answer7
```

| Total score | `screeningLevel` |
| ---: | --- |
| 0–4 | `MINIMAL` |
| 5–9 | `MILD` |
| 10–14 | `MODERATE` |
| 15–21 | `SEVERE` |

The valid total is `0..21`. Care alone validates answers, calculates the total, and selects a band from immutable reference data. The browser cannot submit a total or band.

Primary scoring reference: Spitzer RL, Kroenke K, Williams JBW, Löwe B. *A Brief Measure for Assessing Generalized Anxiety Disorder: The GAD-7*. 2006. DOI: [10.1001/archinte.166.10.1092](https://doi.org/10.1001/archinte.166.10.1092).

Scores describe symptoms through screening; they are not diagnoses. Evidence from one clinical population must not be generalized into diagnostic performance for every Vietnamese adult.

## Safety and runtime boundary

GAD-7 has no PHQ-9 item-9 equivalent. Every GAD-7 result therefore stores and returns:

- `safetyStatus = NOT_APPLICABLE`;
- `safetyItemPositive = null` in persistence;
- `safetyPolicyVersion = null`.

`NOT_APPLICABLE` does not mean a negative safety screen was performed. Engineering and AI must not infer suicide intent, imminence, risk, or a new safety policy from a GAD-7 item or total.

The same Vietnamese non-diagnostic capability statement used by the screening flow remains visible:

> Đây là kết quả sàng lọc triệu chứng, không phải chẩn đoán y khoa. MentalBridge không cung cấp dịch vụ ứng cứu khẩn cấp, không giám sát con người 24/7 và không tự động liên hệ bên thứ ba.

Support routing, personalized actions, specialist sharing, automatic follow-up, notifications, and AI behavior are not part of this publication.

## Acceptance evidence

- [x] Source artifact, retrieval date, checksum, exact symptom items, response mapping, exclusions, and review decision are recorded.
- [x] `gad7-vi-vn-adult-v1` and `gad7-standard-bands-v1` are immutable published reference data.
- [x] Clean and pre-Story-1102 PostgreSQL migrations pass without rewriting historical PHQ-9 content or results.
- [x] Boundary scores `0/4/5/9/10/14/15/21` produce the recorded bands.
- [x] Missing, duplicate, unknown, `-1`, `4`, `88`, and `99` answers are rejected without a partial result.
- [x] Idempotent retry creates one assessment result and one version-2 outbox event.
- [x] API, frontend/BFF, authenticated browser journey, result, history, and immutable-definition reopening are implemented and verified.
- [x] Product Owner approved publication through Story 1102 after the implementation evidence above passed.

## Production follow-up

- [ ] Domain review of exact localized wording and applicability limits.
- [ ] Privacy, retention, security, and applicable legal review for real-user health data.
- [ ] Production deployment approval and effective date recorded independently from Capstone publication.
