--liquibase formatted sql

--changeset mentalbridge:care-009-combined-support-routing runInTransaction:true
CREATE TABLE support_policy_definition (
    version varchar(64) PRIMARY KEY,
    locale varchar(16) NOT NULL,
    status varchar(16) NOT NULL,
    reviewed_by varchar(160) NOT NULL,
    approved_at timestamptz NOT NULL,
    source_reference varchar(512) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_support_policy_definition_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'RETIRED')),
    CONSTRAINT ck_support_policy_definition_locale CHECK (locale ~ '^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$'),
    CONSTRAINT ck_support_policy_definition_review CHECK (length(btrim(reviewed_by)) > 0),
    CONSTRAINT ck_support_policy_definition_source CHECK (length(btrim(source_reference)) > 0)
);

CREATE UNIQUE INDEX ux_support_policy_definition_current
    ON support_policy_definition (locale)
    WHERE status = 'PUBLISHED';

CREATE TABLE support_policy_eligible_definition (
    policy_version varchar(64) NOT NULL REFERENCES support_policy_definition(version) ON DELETE RESTRICT,
    definition_id uuid NOT NULL REFERENCES questionnaire_definition(id) ON DELETE RESTRICT,
    instrument varchar(16) NOT NULL,
    questionnaire_version varchar(32) NOT NULL,
    scoring_version varchar(32) NOT NULL,
    PRIMARY KEY (policy_version, definition_id),
    CONSTRAINT ux_support_policy_eligible_instrument_version UNIQUE (
        policy_version, instrument, questionnaire_version
    ),
    CONSTRAINT ck_support_policy_eligible_instrument CHECK (instrument IN ('PHQ9', 'GAD7')),
    CONSTRAINT ck_support_policy_eligible_questionnaire_version CHECK (
        length(btrim(questionnaire_version)) > 0
    ),
    CONSTRAINT ck_support_policy_eligible_scoring_version CHECK (length(btrim(scoring_version)) > 0)
);

CREATE TABLE screening_band_meaning (
    policy_version varchar(64) NOT NULL REFERENCES support_policy_definition(version) ON DELETE RESTRICT,
    instrument varchar(16) NOT NULL,
    screening_level varchar(24) NOT NULL,
    meaning_code varchar(64) NOT NULL,
    content_version varchar(64) NOT NULL,
    reference_period_days smallint NOT NULL,
    meaning_text varchar(1000) NOT NULL,
    limitation_text varchar(1000) NOT NULL,
    PRIMARY KEY (policy_version, instrument, screening_level),
    CONSTRAINT ux_screening_band_meaning_code UNIQUE (policy_version, meaning_code),
    CONSTRAINT ck_screening_band_meaning_instrument CHECK (instrument IN ('PHQ9', 'GAD7')),
    CONSTRAINT ck_screening_band_meaning_level CHECK (
        (instrument = 'PHQ9' AND screening_level IN (
            'MINIMAL', 'MILD', 'MODERATE', 'MODERATELY_SEVERE', 'SEVERE'
        )) OR
        (instrument = 'GAD7' AND screening_level IN ('MINIMAL', 'MILD', 'MODERATE', 'SEVERE'))
    ),
    CONSTRAINT ck_screening_band_meaning_code CHECK (meaning_code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT ck_screening_band_meaning_content_version CHECK (length(btrim(content_version)) > 0),
    CONSTRAINT ck_screening_band_meaning_reference_period CHECK (reference_period_days = 14),
    CONSTRAINT ck_screening_band_meaning_text CHECK (length(btrim(meaning_text)) > 0),
    CONSTRAINT ck_screening_band_meaning_limitation CHECK (length(btrim(limitation_text)) > 0)
);

CREATE TABLE support_tier_guidance (
    policy_version varchar(64) NOT NULL REFERENCES support_policy_definition(version) ON DELETE RESTRICT,
    support_tier varchar(48) NOT NULL,
    next_step_code varchar(64) NOT NULL,
    content_version varchar(64) NOT NULL,
    next_step_text varchar(1000) NOT NULL,
    boundary_text varchar(1000) NOT NULL,
    safety_guidance_text varchar(1000),
    PRIMARY KEY (policy_version, support_tier),
    CONSTRAINT ux_support_tier_guidance_code UNIQUE (policy_version, next_step_code),
    CONSTRAINT ck_support_tier_guidance_tier CHECK (support_tier IN (
        'SELF_GUIDED_SUPPORT',
        'PROFESSIONAL_SUPPORT_RECOMMENDED',
        'SAFETY_FOLLOW_UP_RECOMMENDED'
    )),
    CONSTRAINT ck_support_tier_guidance_next_step_code CHECK (next_step_code ~ '^[A-Z0-9_]+$'),
    CONSTRAINT ck_support_tier_guidance_content_version CHECK (length(btrim(content_version)) > 0),
    CONSTRAINT ck_support_tier_guidance_next_step CHECK (length(btrim(next_step_text)) > 0),
    CONSTRAINT ck_support_tier_guidance_boundary CHECK (length(btrim(boundary_text)) > 0),
    CONSTRAINT ck_support_tier_guidance_safety CHECK (
        (support_tier = 'SAFETY_FOLLOW_UP_RECOMMENDED' AND length(btrim(safety_guidance_text)) > 0) OR
        (support_tier <> 'SAFETY_FOLLOW_UP_RECOMMENDED' AND safety_guidance_text IS NULL)
    )
);

CREATE TABLE support_evaluation (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id uuid NOT NULL REFERENCES user_profile(account_id) ON DELETE RESTRICT,
    phq9_assessment_id uuid NOT NULL REFERENCES assessment_submission(id) ON DELETE RESTRICT,
    gad7_assessment_id uuid NOT NULL REFERENCES assessment_submission(id) ON DELETE RESTRICT,
    policy_version varchar(64) NOT NULL REFERENCES support_policy_definition(version) ON DELETE RESTRICT,
    support_tier varchar(48) NOT NULL,
    primary_reason_code varchar(64) NOT NULL,
    secondary_reason_code varchar(64),
    idempotency_key varchar(128) NOT NULL,
    request_hash varchar(64) NOT NULL,
    evaluated_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_support_evaluation_guidance
        FOREIGN KEY (policy_version, support_tier)
        REFERENCES support_tier_guidance(policy_version, support_tier) ON DELETE RESTRICT,
    CONSTRAINT ux_support_evaluation_idempotency UNIQUE (user_id, idempotency_key),
    CONSTRAINT ux_support_evaluation_evidence UNIQUE (
        user_id, phq9_assessment_id, gad7_assessment_id, policy_version
    ),
    CONSTRAINT ck_support_evaluation_distinct_evidence CHECK (phq9_assessment_id <> gad7_assessment_id),
    CONSTRAINT ck_support_evaluation_tier CHECK (support_tier IN (
        'SELF_GUIDED_SUPPORT',
        'PROFESSIONAL_SUPPORT_RECOMMENDED',
        'SAFETY_FOLLOW_UP_RECOMMENDED'
    )),
    CONSTRAINT ck_support_evaluation_primary_reason CHECK (primary_reason_code IN (
        'ALL_SCREENING_LEVELS_MINIMAL_OR_MILD',
        'PHQ9_MODERATE_OR_HIGHER',
        'GAD7_MODERATE_OR_HIGHER',
        'PHQ9_SAFETY_SCREEN_POSITIVE'
    )),
    CONSTRAINT ck_support_evaluation_secondary_reason CHECK (
        secondary_reason_code IS NULL OR secondary_reason_code IN (
            'PHQ9_MODERATE_OR_HIGHER', 'GAD7_MODERATE_OR_HIGHER'
        )
    ),
    CONSTRAINT ck_support_evaluation_reason_order CHECK (
        secondary_reason_code IS NULL OR (
            primary_reason_code = 'PHQ9_MODERATE_OR_HIGHER' AND
            secondary_reason_code = 'GAD7_MODERATE_OR_HIGHER'
        )
    ),
    CONSTRAINT ck_support_evaluation_tier_reason CHECK (
        (support_tier = 'SELF_GUIDED_SUPPORT' AND
         primary_reason_code = 'ALL_SCREENING_LEVELS_MINIMAL_OR_MILD' AND
         secondary_reason_code IS NULL) OR
        (support_tier = 'PROFESSIONAL_SUPPORT_RECOMMENDED' AND
         primary_reason_code IN ('PHQ9_MODERATE_OR_HIGHER', 'GAD7_MODERATE_OR_HIGHER')) OR
        (support_tier = 'SAFETY_FOLLOW_UP_RECOMMENDED' AND
         primary_reason_code = 'PHQ9_SAFETY_SCREEN_POSITIVE' AND
         secondary_reason_code IS NULL)
    ),
    CONSTRAINT ck_support_evaluation_idempotency_key CHECK (length(idempotency_key) BETWEEN 16 AND 128),
    CONSTRAINT ck_support_evaluation_request_hash CHECK (request_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_support_evaluation_user_history
    ON support_evaluation (user_id, evaluated_at DESC, id DESC);

INSERT INTO support_policy_definition (
    version, locale, status, reviewed_by, approved_at, source_reference
) VALUES (
    'mb-support-routing-capstone-v1',
    'vi-VN',
    'PUBLISHED',
    'Story 1103 policy baseline; PO-approved safety fallback (2026-09-10)',
    '2026-09-10T00:00:00Z',
    'Story 1103; MB-SUPPORT-CARE-001; ADR 0009; MB-179 screening-to-support blueprint'
);

INSERT INTO support_policy_eligible_definition (
    policy_version, definition_id, instrument, questionnaire_version, scoring_version
) VALUES
    ('mb-support-routing-capstone-v1', '10000000-0000-0000-0000-000000000002', 'PHQ9', 'phq9-vi-vn-capstone-v1', 'phq9-standard-bands-v1'),
    ('mb-support-routing-capstone-v1', '10000000-0000-0000-0000-000000000004', 'PHQ9', 'phq9-vi-vn-capstone-v2', 'phq9-standard-bands-v1'),
    ('mb-support-routing-capstone-v1', '10000000-0000-0000-0000-000000000003', 'GAD7', 'gad7-vi-vn-adult-v1', 'gad7-standard-bands-v1');

INSERT INTO screening_band_meaning (
    policy_version, instrument, screening_level, meaning_code, content_version,
    reference_period_days, meaning_text, limitation_text
) VALUES
    ('mb-support-routing-capstone-v1', 'PHQ9', 'MINIMAL', 'PHQ9_MINIMAL_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời PHQ-9 của bạn thuộc mức triệu chứng tối thiểu trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'PHQ9', 'MILD', 'PHQ9_MILD_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời PHQ-9 của bạn thuộc mức triệu chứng nhẹ trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'PHQ9', 'MODERATE', 'PHQ9_MODERATE_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời PHQ-9 của bạn thuộc mức triệu chứng trung bình trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'PHQ9', 'MODERATELY_SEVERE', 'PHQ9_MODERATELY_SEVERE_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời PHQ-9 của bạn thuộc mức triệu chứng khá nặng trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'PHQ9', 'SEVERE', 'PHQ9_SEVERE_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời PHQ-9 của bạn thuộc mức triệu chứng nặng trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'GAD7', 'MINIMAL', 'GAD7_MINIMAL_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời GAD-7 của bạn thuộc mức triệu chứng tối thiểu trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'GAD7', 'MILD', 'GAD7_MILD_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời GAD-7 của bạn thuộc mức triệu chứng nhẹ trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'GAD7', 'MODERATE', 'GAD7_MODERATE_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời GAD-7 của bạn thuộc mức triệu chứng trung bình trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.'),
    ('mb-support-routing-capstone-v1', 'GAD7', 'SEVERE', 'GAD7_SEVERE_14D', 'mb-screening-meaning-vi-vn-v1', 14, 'Câu trả lời GAD-7 của bạn thuộc mức triệu chứng nặng trong 14 ngày qua.', 'Kết quả này chỉ phản ánh câu trả lời tự khai trong 14 ngày qua. Đây là sàng lọc triệu chứng, không phải chẩn đoán y khoa và không thể hiện mức độ bệnh lý tổng thể.');

INSERT INTO support_tier_guidance (
    policy_version, support_tier, next_step_code, content_version,
    next_step_text, boundary_text, safety_guidance_text
) VALUES
    ('mb-support-routing-capstone-v1', 'SELF_GUIDED_SUPPORT', 'REVIEW_SELF_GUIDED_RESOURCE', 'mb-support-next-step-vi-vn-v1', 'Bạn có thể chọn một tài nguyên tự hỗ trợ đã được MentalBridge rà soát và tiếp tục theo dõi cảm nhận của mình.', 'Không có cuộc hẹn, liên hệ với chuyên gia, chia sẻ dữ liệu hoặc can thiệp tự động nào được thực hiện.', null),
    ('mb-support-routing-capstone-v1', 'PROFESSIONAL_SUPPORT_RECOMMENDED', 'CONSIDER_PROFESSIONAL_SUPPORT', 'mb-support-next-step-vi-vn-v1', 'Bạn có thể cân nhắc chủ động tìm sự hỗ trợ từ một chuyên gia phù hợp nếu mong muốn.', 'MentalBridge chưa liên hệ chuyên gia, đặt lịch hoặc chia sẻ dữ liệu của bạn; các hành động đó cần quy trình và sự đồng ý riêng.', null),
    ('mb-support-routing-capstone-v1', 'SAFETY_FOLLOW_UP_RECOMMENDED', 'REVIEW_SAFETY_GUIDANCE', 'mb-safety-guidance-vi-vn-v1', 'Ưu tiên xem hướng dẫn an toàn ngay bên dưới và chủ động tìm hỗ trợ trực tiếp nếu bạn cảm thấy không an toàn.', 'Kết quả này không xác định ý định, kế hoạch hay mức độ khẩn cấp. MentalBridge không tự động liên hệ người khác, đặt lịch hoặc chia sẻ dữ liệu.', 'Nếu bạn cảm thấy mình không an toàn hoặc có nguy cơ gây hại cho bản thân, hãy chủ động liên hệ dịch vụ khẩn cấp hoặc cơ sở y tế phù hợp tại khu vực của bạn.');
