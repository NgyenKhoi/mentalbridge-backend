# Capstone questionnaire evidence and publication policy

## Policy metadata

| Field | Value |
| --- | --- |
| Policy ID | `MB-CAPSTONE-SCREENING-PUBLICATION-001` |
| Policy version | `1.0` |
| Status | `EFFECTIVE FOR CAPSTONE` |
| Effective date | 2026-09-01 |
| Decision owner | MentalBridge Product Owner / Project Lead |
| Applies to | PHQ-9 and GAD-7 publication in controlled MentalBridge Capstone local/demo environments |
| Production deployment | Not approved by this policy |
| Architecture decision | [ADR 0010](../adr/0010-capstone-questionnaire-publication-gates.md) |

MentalBridge is an academic Capstone project, not a regulated clinical service. The team may adopt an established screening instrument for controlled local/demo use through documented research verification. An external psychiatrist, clinical-domain signature, legal review, completed intervention catalogue, and completed specialist workflow are not prerequisites for publishing the questionnaire in that bounded environment.

This policy does not exempt a public deployment that collects real-user health data from applicable privacy, security, legal, retention, or operational review. It also does not claim that MentalBridge has clinically validated an instrument for every Vietnamese adult aged 18–30.

## Capstone publication gate

A questionnaire is `CAPSTONE IMPLEMENTATION READY` only when all of the following evidence is present:

1. **Instrument source:** the original peer-reviewed validation study and instrument identity are recorded.
2. **Localized provenance:** exact `vi-VN` wording and response options come from a named, traceable source; developers do not independently translate or paraphrase them.
3. **Use basis:** the source's access or use terms and the retrieved artifact/version are recorded. A public listing of a language alone is not treated as the exact localized instrument.
4. **Scoring provenance:** score range, bands, reference period, missing-answer behavior, and scoring version are traceable to published evidence.
5. **Backend authority:** Care accepts answers only and computes the score, screening level, and questionnaire-specific deterministic safety status.
6. **Presentation boundary:** UI and API describe symptom screening, not diagnosis, treatment, suicide-risk stratification, or guaranteed human response.
7. **Verification:** automated tests cover every band boundary, invalid/incomplete answers, idempotency, and any safety-sensitive rule before publication.

The Product Owner records the Capstone publication decision after this checklist passes. Supervisor or domain-expert review may strengthen the academic evidence, but absence of those signatures does not block controlled Capstone publication.

## Independent feature gates

The following work does not block base questionnaire publication and must not be silently inferred from a score:

| Feature | Separate gate |
| --- | --- |
| SupportEvaluation | Approved deterministic instrument/domain-aware policy, independent safety, provenance and missing/stale-input behavior |
| SupportPlan | System-proposed draft policy, bounded user choices, exact versioned resource eligibility, revalidation and explicit activation under ADR 0012 |
| Specialist handoff | Entitlement, availability, consent, and data-sharing rules |
| Follow-up and reminders | Cadence, opt-out, ownership, and unavailable-delivery fallback |
| Public real-user deployment | Privacy, security, retention, legal, and production-safety review |

Until a separate feature gate passes, the runtime returns an explicit unavailable state. It does not fabricate guidance, contact a human, or imply that a support action occurred.

## Evidence basis

### PHQ-9

- Original validation: Kroenke K, Spitzer RL, Williams JBW. *The PHQ-9: Validity of a Brief Depression Severity Measure*. 2001. DOI: [10.1046/j.1525-1497.2001.016009606.x](https://doi.org/10.1046/j.1525-1497.2001.016009606.x).
- Pfizer states that PHQ and GAD instruments are available without copyright restriction and at no charge, and its current FAQ permits PHQ download without a formal permission request when the Terms of Use are accepted: [public-access announcement](https://www.pfizer.com/news/press-release/press-release-detail/pfizer_to_offer_free_public_access_to_mental_health_assessment_tools_to_improve_diagnosis_and_patient_care), [FAQ](https://www.pfizer.com/contact/faqs).
- The NIMH Data Archive PHQ-9 Common Data Element lists `Vietnamese for Vietnam`: [PHQ-9 data-structure history](https://nda.nih.gov/data_structure_history.html?short_name=cde_phq901).
- Exact Capstone artifact: *PHQ-9 Vietnamese* distributed by SBIRT Oregon, [archived 2024-07-20](https://web.archive.org/web/20240720104123id_/https://www.sbirtoregon.org/wp-content/uploads/PHQ-9-Vietnamese.pdf), SHA-256 `E2775444E5AB4A05C3FF097F1CAB356C2DA9ECC73BAC63E91827BAA77E965FF7`. The artifact states that no permission is required to copy, translate, display, or distribute.
- Vietnamese applicability evidence includes Phi HNY et al., 2023, DOI [10.12809/eaap2258](https://doi.org/10.12809/eaap2258), and a community psychometric study, DOI [10.3389/fpsyt.2022.838747](https://doi.org/10.3389/fpsyt.2022.838747).

### GAD-7

- Original validation: Spitzer RL, Kroenke K, Williams JBW, Löwe B. *A Brief Measure for Assessing Generalized Anxiety Disorder: The GAD-7*. 2006. DOI: [10.1001/archinte.166.10.1092](https://doi.org/10.1001/archinte.166.10.1092).
- The NIMH Data Archive GAD-7 Common Data Element lists `Vietnamese for Vietnam`: [GAD-7 data structure](https://nda.nih.gov/data-structure/cde_gad701).
- NDA hosts *GAD-7 — Vietnamese for Vietnam — Translated by UNC Vietnam, 2024*: [source artifact](https://s3.amazonaws.com/nda.nih.gov/cms/prod/GAD7_VietnameseForVietnam_uncvn.pdf).

The UNC artifact is interviewer-oriented and includes non-score response codes. Before publishing it in a self-administered MentalBridge flow, the team must document the exact `0..3` mapping, treatment of non-score codes, and retained wording. The Product Owner may authorize engineering to prepare that mapping and its tests, but source identification alone is not publication readiness.

## Scope statement for reports

MentalBridge performs an evidence-based academic adoption of internationally established screening instruments. It uses traceable Vietnamese-language sources and standardized server-side scoring. Results are symptom-screening outputs, not autonomous psychiatric diagnoses or treatment decisions. The 18–30 range is the initial product cohort, not a claimed medical-validity boundary.

## Lifecycle

```text
DRAFT
  -> RESEARCH BASIS VERIFIED
  -> LOCALIZED CONTENT VERIFIED
  -> CAPSTONE IMPLEMENTATION READY
  -> CAPSTONE PUBLISHED

PRODUCTION CANDIDATE
  -> DOMAIN / PRIVACY / LEGAL / OPERATIONAL REVIEW
  -> PRODUCTION APPROVED
```
