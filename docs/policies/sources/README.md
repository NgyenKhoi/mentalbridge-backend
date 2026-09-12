# Questionnaire source artifacts

This directory archives public source artifacts needed to reproduce controlled MentalBridge Capstone questionnaire definitions. Executable wording remains in append-only Care migrations; the files here provide review and checksum evidence.

## GAD-7 Vietnamese for Vietnam

| Field | Value |
| --- | --- |
| Artifact | `GAD7_VietnameseForVietnam_uncvn.pdf` |
| Publisher | NIMH Data Archive |
| Translation | UNC Vietnam, 2024 |
| Source URL | `https://s3.amazonaws.com/nda.nih.gov/cms/prod/GAD7_VietnameseForVietnam_uncvn.pdf` |
| Retrieved | `2026-09-09` |
| SHA-256 | `876A7245EF7BDDFC3EADFA15625E02F132560218C219E4E5251E6B7DC6A8A001` |
| MentalBridge version | `gad7-vi-vn-adult-v1` |
| Review decision | Product Owner approved implementation and conditional Capstone publication through Story 1102 |

MentalBridge retains the seven Vietnamese symptom items and the four score-bearing `0..3` responses. The interviewer instruction and non-score collection codes `88` and `99` are not executable questionnaire content and are excluded from the UI/API. The runtime presents the retained content as a self-administered symptom-screening questionnaire and does not use AI interpretation.

The publication decision becomes `CAPSTONE PUBLISHED` only after the reference-data migration, deterministic scoring, contract checks, and automated verification pass. This evidence does not approve public real-user production deployment.
