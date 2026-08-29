--liquibase formatted sql

--changeset mentalbridge:care-004-phq9-reference-data runInTransaction:true
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
    '10000000-0000-0000-0000-000000000001',
    'PHQ9',
    'phq9-en-us-v1',
    'en-US',
    'Patient Health Questionnaire-9',
    14,
    9,
    'phq9-standard-bands-v1',
    '[{"value":0,"label":"Not at all"},{"value":1,"label":"Several days"},{"value":2,"label":"More than half the days"},{"value":3,"label":"Nearly every day"}]'::jsonb,
    'Kroenke K, Spitzer RL, Williams JBW. J Gen Intern Med. 2001;16(9):606-613. doi:10.1046/j.1525-1497.2001.016009606.x',
    'PUBLISHED',
    '2026-08-29T00:00:00Z'
);

INSERT INTO questionnaire_question (id, definition_id, item_number, prompt, safety_item) VALUES
    ('11000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 1, 'Little interest or pleasure in doing things', false),
    ('11000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', 2, 'Feeling down, depressed, or hopeless', false),
    ('11000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', 3, 'Trouble falling or staying asleep, or sleeping too much', false),
    ('11000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', 4, 'Feeling tired or having little energy', false),
    ('11000000-0000-0000-0000-000000000005', '10000000-0000-0000-0000-000000000001', 5, 'Poor appetite or overeating', false),
    ('11000000-0000-0000-0000-000000000006', '10000000-0000-0000-0000-000000000001', 6, 'Feeling bad about yourself — or that you are a failure or have let yourself or your family down', false),
    ('11000000-0000-0000-0000-000000000007', '10000000-0000-0000-0000-000000000001', 7, 'Trouble concentrating on things, such as reading the newspaper or watching television', false),
    ('11000000-0000-0000-0000-000000000008', '10000000-0000-0000-0000-000000000001', 8, 'Moving or speaking so slowly that other people could have noticed, or the opposite — being so fidgety or restless that you have been moving around a lot more than usual', false),
    ('11000000-0000-0000-0000-000000000009', '10000000-0000-0000-0000-000000000001', 9, 'Thoughts that you would be better off dead or of hurting yourself in some way', true);

INSERT INTO questionnaire_score_band (definition_id, code, minimum_score, maximum_score, ordinal) VALUES
    ('10000000-0000-0000-0000-000000000001', 'MINIMAL', 0, 4, 1),
    ('10000000-0000-0000-0000-000000000001', 'MILD', 5, 9, 2),
    ('10000000-0000-0000-0000-000000000001', 'MODERATE', 10, 14, 3),
    ('10000000-0000-0000-0000-000000000001', 'MODERATELY_SEVERE', 15, 19, 4),
    ('10000000-0000-0000-0000-000000000001', 'SEVERE', 20, 27, 5);
