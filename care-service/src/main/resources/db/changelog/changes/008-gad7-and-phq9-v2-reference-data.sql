--liquibase formatted sql

--changeset mentalbridge:care-008-gad7-and-phq9-v2-reference-data runInTransaction:true
ALTER TABLE assessment_result
    ALTER COLUMN safety_item_positive DROP NOT NULL;

ALTER TABLE assessment_result
    DROP CONSTRAINT ck_assessment_result_safety_provenance;

ALTER TABLE assessment_result
    ADD CONSTRAINT ck_assessment_result_safety_provenance CHECK (
        (
            safety_status IS NULL AND
            safety_policy_version IS NULL AND
            safety_item_positive IS NOT NULL
        ) OR
        (
            safety_status IN ('NEGATIVE_SAFETY_SCREEN', 'POSITIVE_SAFETY_SCREEN') AND
            safety_item_positive IS NOT NULL AND
            length(btrim(safety_policy_version)) > 0 AND
            (
                (safety_item_positive = false AND safety_status = 'NEGATIVE_SAFETY_SCREEN') OR
                (safety_item_positive = true AND safety_status = 'POSITIVE_SAFETY_SCREEN')
            )
        ) OR
        (
            safety_status = 'NOT_APPLICABLE' AND
            safety_item_positive IS NULL AND
            safety_policy_version IS NULL
        )
    );

UPDATE questionnaire_definition
SET status = 'RETIRED'
WHERE id = '10000000-0000-0000-0000-000000000002'
  AND instrument = 'PHQ9'
  AND version = 'phq9-vi-vn-capstone-v1'
  AND locale = 'vi-VN'
  AND status = 'PUBLISHED';

INSERT INTO questionnaire_definition (
    id,
    instrument,
    version,
    locale,
    title,
    reference_period_days,
    expected_question_count,
    scoring_version,
    response_options,
    source_reference,
    status,
    published_at
) VALUES
(
    '10000000-0000-0000-0000-000000000003',
    'GAD7',
    'gad7-vi-vn-adult-v1',
    'vi-VN',
    'GAD-7 — Sàng lọc triệu chứng lo âu',
    14,
    7,
    'gad7-standard-bands-v1',
    '[{"value":0,"label":"Không bao giờ (0 ngày nào)"},{"value":1,"label":"Vài ngày (1-7 ngày)"},{"value":2,"label":"Hơn một nửa số ngày (8-10 ngày)"},{"value":3,"label":"Gần như hàng ngày (11-14 ngày)"}]'::jsonb,
    'NIMH NDA, GAD-7 Vietnamese for Vietnam, translated by UNC Vietnam 2024; https://s3.amazonaws.com/nda.nih.gov/cms/prod/GAD7_VietnameseForVietnam_uncvn.pdf; retrieved 2026-09-09; SHA-256 876A7245EF7BDDFC3EADFA15625E02F132560218C219E4E5251E6B7DC6A8A001; self-administered scored items 0..3; interviewer instructions and codes 88/99 excluded; scoring DOI 10.1001/archinte.166.10.1092.',
    'PUBLISHED',
    '2026-09-09T00:00:00Z'
),
(
    '10000000-0000-0000-0000-000000000004',
    'PHQ9',
    'phq9-vi-vn-capstone-v2',
    'vi-VN',
    'PHQ-9 — Sàng lọc triệu chứng',
    14,
    9,
    'phq9-standard-bands-v1',
    '[{"value":0,"label":"Không có gì"},{"value":1,"label":"Vài ngày"},{"value":2,"label":"Hơn nửa ngày"},{"value":3,"label":"Gần như mỗi ngày"}]'::jsonb,
    'MentalBridge Capstone corrective version of phq9-vi-vn-capstone-v1. Q2 duplicated-word defect corrected by Product Owner decision in Story 1102; corrected wording is not claimed verbatim from the SBIRT artifact. All other wording, responses, scoring and item-9 safety semantics retain v1 provenance and SHA-256 E2775444E5AB4A05C3FF097F1CAB356C2DA9ECC73BAC63E91827BAA77E965FF7.',
    'PUBLISHED',
    '2026-09-09T00:00:00Z'
);

INSERT INTO questionnaire_question (id, definition_id, item_number, prompt, safety_item) VALUES
    ('13000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000003', 1, 'Cảm giác hồi hộp, lo lắng hoặc cáu kỉnh', false),
    ('13000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000003', 2, 'Không thể dừng hoặc kiểm soát được việc lo lắng', false),
    ('13000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000003', 3, 'Lo lắng quá nhiều về những điều khác nhau', false),
    ('13000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000003', 4, 'Không thể thư giãn được', false),
    ('13000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000003', 5, 'Cảm thấy bồn chồn đến mức mà khó có thể ngồi yên một chỗ', false),
    ('13000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000003', 6, 'Trở nên dễ bực mình hoặc cáu kỉnh', false),
    ('13000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000003', 7, 'Cảm thấy sợ như thể có một điều gì đó khủng khiếp có thể xảy ra', false),
    ('14000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000004', 1, 'Ít quan tâm hoặc niềm vui khi làm việc', false),
    ('14000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000004', 2, 'Cảm thấy chán nản, buồn rầu hoặc vô vọng', false),
    ('14000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000004', 3, 'Khó ngủ hoặc duy trì giấc ngủ, hoặc ngủ quá nhiều', false),
    ('14000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000004', 4, 'Cảm thấy mệt mỏi hoặc có ít năng lượng', false),
    ('14000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000004', 5, 'Kém ăn hoặc ăn quá nhiều', false),
    ('14000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000004', 6, 'Cảm thấy tồi tệ về bản thân - hoặc rằng bạn là một kẻ thất bại hoặc đã khiến bản thân hoặc gia đình thất vọng', false),
    ('14000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000004', 7, 'Khó tập trung vào mọi thứ, chẳng hạn như đọc báo hoặc xem tivi', false),
    ('14000000-0000-0000-0000-000000000008', '10000000-0000-0000-0000-000000000004', 8, 'Di chuyển hoặc nói chậm đến mức người khác có thể nhận thấy? Hoặc ngược lại - bồn chồn hoặc bồn chồn đến mức bạn đã di chuyển xung quanh nhiều hơn bình thường', false),
    ('14000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000004', 9, 'Suy nghĩ rằng tốt hơn hết là bạn nên chết hoặc làm tổn thương bản thân theo một cách nào đó', true);

INSERT INTO questionnaire_score_band (definition_id, code, minimum_score, maximum_score, ordinal) VALUES
    ('10000000-0000-0000-0000-000000000003', 'MINIMAL', 0, 4, 1),
    ('10000000-0000-0000-0000-000000000003', 'MILD', 5, 9, 2),
    ('10000000-0000-0000-0000-000000000003', 'MODERATE', 10, 14, 3),
    ('10000000-0000-0000-0000-000000000003', 'SEVERE', 15, 21, 4),
    ('10000000-0000-0000-0000-000000000004', 'MINIMAL', 0, 4, 1),
    ('10000000-0000-0000-0000-000000000004', 'MILD', 5, 9, 2),
    ('10000000-0000-0000-0000-000000000004', 'MODERATE', 10, 14, 3),
    ('10000000-0000-0000-0000-000000000004', 'MODERATELY_SEVERE', 15, 19, 4),
    ('10000000-0000-0000-0000-000000000004', 'SEVERE', 20, 27, 5);
