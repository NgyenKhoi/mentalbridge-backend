--liquibase formatted sql

--changeset mentalbridge:care-006-phq9-vi-vn-reference-data runInTransaction:true
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
) VALUES (
    '10000000-0000-0000-0000-000000000002',
    'PHQ9',
    'phq9-vi-vn-capstone-v1',
    'vi-VN',
    'PHQ-9 — Sàng lọc triệu chứng',
    14,
    9,
    'phq9-standard-bands-v1',
    '[{"value":0,"label":"Không có gì"},{"value":1,"label":"Vài ngày"},{"value":2,"label":"Hơn nửa ngày"},{"value":3,"label":"Gần như mỗi ngày"}]'::jsonb,
    'SBIRT Oregon, PHQ-9 Vietnamese PDF; archived 2024-07-20 at https://web.archive.org/web/20240720104123id_/https://www.sbirtoregon.org/wp-content/uploads/PHQ-9-Vietnamese.pdf; SHA-256 E2775444E5AB4A05C3FF097F1CAB356C2DA9ECC73BAC63E91827BAA77E965FF7; scoring DOI 10.1046/j.1525-1497.2001.016009606.x; artifact states no permission is required to copy, translate, display or distribute.',
    'PUBLISHED',
    '2026-09-02T00:00:00Z'
);

INSERT INTO questionnaire_question (id, definition_id, item_number, prompt, safety_item) VALUES
    ('12000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000002', 1, 'Ít quan tâm hoặc niềm vui khi làm việc', false),
    ('12000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000002', 2, 'Cảm thấy chán nản, chán nản hoặc tuyệt vọng', false),
    ('12000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000002', 3, 'Khó ngủ hoặc duy trì giấc ngủ, hoặc ngủ quá nhiều', false),
    ('12000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000002', 4, 'Cảm thấy mệt mỏi hoặc có ít năng lượng', false),
    ('12000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000002', 5, 'Kém ăn hoặc ăn quá nhiều', false),
    ('12000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000002', 6, 'Cảm thấy tồi tệ về bản thân - hoặc rằng bạn là một kẻ thất bại hoặc đã khiến bản thân hoặc gia đình thất vọng', false),
    ('12000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000002', 7, 'Khó tập trung vào mọi thứ, chẳng hạn như đọc báo hoặc xem tivi', false),
    ('12000000-0000-0000-0000-000000000008', '10000000-0000-0000-0000-000000000002', 8, 'Di chuyển hoặc nói chậm đến mức người khác có thể nhận thấy? Hoặc ngược lại - bồn chồn hoặc bồn chồn đến mức bạn đã di chuyển xung quanh nhiều hơn bình thường', false),
    ('12000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000002', 9, 'Suy nghĩ rằng tốt hơn hết là bạn nên chết hoặc làm tổn thương bản thân theo một cách nào đó', true);

INSERT INTO questionnaire_score_band (definition_id, code, minimum_score, maximum_score, ordinal) VALUES
    ('10000000-0000-0000-0000-000000000002', 'MINIMAL', 0, 4, 1),
    ('10000000-0000-0000-0000-000000000002', 'MILD', 5, 9, 2),
    ('10000000-0000-0000-0000-000000000002', 'MODERATE', 10, 14, 3),
    ('10000000-0000-0000-0000-000000000002', 'MODERATELY_SEVERE', 15, 19, 4),
    ('10000000-0000-0000-0000-000000000002', 'SEVERE', 20, 27, 5);
