# GAD-7 screening policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SCREEN-GAD7-001` |
| Policy version | `1.0-draft.2` |
| Status | `RESEARCH BASIS AND VIETNAMESE SOURCE IDENTIFIED — FORMAT MAPPING PENDING` |
| Capstone publication authority | Product Owner under [`MB-CAPSTONE-SCREENING-PUBLICATION-001`](capstone-questionnaire-publication-policy.md) |
| Capstone effective date | Pending exact localized-content mapping, implementation, and tests |
| Supervisor/domain review | Recommended academic evidence review; not a Capstone publication blocker |
| Production review | Domain, privacy, legal, and operational review required before public real-user deployment |
| Applies to | Proposed GAD-7 for the initial target population of adults aged 18–30 in Vietnam |
| Locale | `vi-VN` |
| Owning service | Care Service |
| Supersedes | N/A |

GAD-7 is a separate screening instrument and must not inherit PHQ-9 questionnaire text, score range, item-9 safety behavior, or diagnostic claims.

## Proposed deterministic scoring core

Each of seven answers is `0..3` for symptom frequency during the preceding 14 days:

```text
totalScore = answer1 + answer2 + ... + answer7
```

The valid total is `0..21`. The proposed immutable reference bands are:

| Total score | `screeningLevel` |
| ---: | --- |
| 0–4 | `MINIMAL` |
| 5–9 | `MILD` |
| 10–14 | `MODERATE` |
| 15–21 | `SEVERE` |

Primary reference: Spitzer RL, Kroenke K, Williams JBW, Löwe B. *A Brief Measure for Assessing Generalized Anxiety Disorder: The GAD-7*. 2006. DOI: [10.1001/archinte.166.10.1092](https://doi.org/10.1001/archinte.166.10.1092).

The original study reported strong reliability and screening performance around a score of 10 in its adult primary-care population. Cutoffs can perform differently in other populations. A Vietnamese validation in an adult methadone-maintenance population reported materially different operating characteristics, so that population-specific result must not be generalized into a diagnostic threshold for all Vietnamese adults: [PMCID PMC8491403](https://pmc.ncbi.nlm.nih.gov/articles/PMC8491403/).

## Vietnamese provenance

The NIMH Data Archive GAD-7 Common Data Element lists `Vietnamese for Vietnam`: [GAD-7 data structure](https://nda.nih.gov/data-structure/cde_gad701). NDA also hosts *GAD-7 — Vietnamese for Vietnam — Translated by UNC Vietnam, 2024*: [source artifact](https://s3.amazonaws.com/nda.nih.gov/cms/prod/GAD7_VietnameseForVietnam_uncvn.pdf).

This artifact is interviewer-oriented. It expresses frequency labels using day ranges and includes non-score `refused` and `do not know` codes. MentalBridge accepts only complete `0..3` scored answers, so the team must record which exact wording is retained, how the interviewer framing is adapted or excluded without changing item meaning, and how non-score codes are handled. Source identification alone does not make the self-administered runtime implementation ready.

## Publication and safety boundary

The reserved target identifier is `gad7-vi-vn-adult-v1`. It remains unpublished until exact Vietnamese wording, response semantics, source artifact/version, applicable use terms, self-administered mapping, and automated tests satisfy the Capstone publication gate. An external clinical/domain signature is recommended but is not mandatory for controlled Capstone publication.

GAD-7 has no PHQ-9 item-9 equivalent in this policy. It may contribute to a `supportTier` only through a separately approved deterministic mapping. Engineering and AI must not derive suicide intent, imminent risk, or a new safety rule from the GAD-7 total.

## Capstone publication checklist

- [x] Original instrument and scoring evidence recorded.
- [x] Recognized `vi-VN` source artifact identified.
- [ ] Exact item wording, response labels, source version, retrieval evidence, and applicable use terms recorded.
- [ ] Interviewer framing, day-range labels, and non-score codes mapped deliberately to the self-administered `0..3` MentalBridge contract without changing item meaning.
- [ ] Scoring version and the `0..21` boundary behavior recorded in versioned reference data.
- [ ] Vietnamese non-diagnostic disclaimer selected for the Capstone environment.
- [ ] Questionnaire, score-boundary, invalid/incomplete-answer, and idempotency tests pass.
- [ ] Product Owner records the `CAPSTONE PUBLISHED` decision and effective version.

Combined PHQ-9/GAD-7 support-tier mapping is not a GAD-7 questionnaire-publication blocker. GAD-7 contributes no new safety status or support action until a separate deterministic policy passes its own gate.

## Production follow-up

- [ ] Domain review of exact localized wording and applicability limits.
- [ ] Privacy, retention, security, and applicable legal review for real-user health data.
- [ ] Production deployment approval and effective date recorded independently from Capstone publication.

No runtime endpoint may advertise GAD-7 as implemented before the Capstone checklist is complete and executable reference data and tests exist.
