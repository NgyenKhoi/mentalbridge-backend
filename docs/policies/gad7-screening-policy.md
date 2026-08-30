# GAD-7 screening policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-SCREEN-GAD7-001` |
| Policy version | `1.0-draft.1` |
| Status | `DRAFT — RESEARCH AND DOMAIN REVIEW REQUIRED` |
| Effective date | Pending approval |
| Product owner | Pending recorded approval |
| Supervisor | Pending recorded approval |
| Domain expert | Pending recorded approval |
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

## Publication and safety boundary

The reserved target identifier is `gad7-vi-vn-adult-v1`. It remains unpublished until exact Vietnamese wording, provenance, validated population, reviewer, response labels, and publication rights are approved.

GAD-7 has no PHQ-9 item-9 equivalent in this policy. It may contribute to a `supportTier` only through a separately approved deterministic mapping. Engineering and AI must not derive suicide intent, imminent risk, or a new safety rule from the GAD-7 total.

## Approval blockers

- [ ] Exact validated Vietnamese GAD-7 wording, response labels, source, and publication rights recorded.
- [ ] Applicability to the target Vietnamese adult population reviewed.
- [ ] Vietnamese non-diagnostic disclaimer approved.
- [ ] Scoring version identifier approved.
- [ ] Combined PHQ-9/GAD-7 support-tier mapping approved.
- [ ] Product owner, supervisor, and domain expert approvals recorded.

No GAD-7 questionnaire may be published and no runtime endpoint may advertise GAD-7 as implemented before these blockers are closed.
