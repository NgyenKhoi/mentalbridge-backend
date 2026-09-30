-- Up Migration
-- Migration: 7_seed_mb603_resource_experience
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Story:     MB-603 - plan-driven resource journeys

UPDATE resource r
SET resource_kind = metadata.resource_kind,
    interaction_type = metadata.interaction_type,
    repeatability = metadata.repeatability,
    completion_mode = metadata.completion_mode,
    streak_eligible = metadata.streak_eligible,
    expected_duration_minutes = metadata.duration_minutes,
    cooldown_days = metadata.cooldown_days,
    recommended_frequency_per_week = metadata.frequency_per_week,
    plan_tags = metadata.plan_tags,
    interaction_config = metadata.interaction_config,
    safety_notes = metadata.safety_notes,
    source_retrieved_at = TIMESTAMPTZ '2026-09-29 00:00:00+00',
    content_version_label = 'mb-603-curated-v1',
    source_review_status = metadata.review_status
FROM (
  VALUES
    ('00000000-0000-4000-8000-000000000201'::uuid, 'PRACTICE', 'BREATHING_PACER', 'REPEATABLE', 'TIMED', true, 5::smallint, 0::smallint, 7::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[],
      '{"inhaleSeconds":4,"exhaleSeconds":5,"cycles":8,"holdSeconds":0,"steps":[{"id":"settle","label":"Chọn tư thế được nâng đỡ"},{"id":"pace","label":"Thở nhẹ theo nhịp dễ chịu"},{"id":"return","label":"Trở về nhịp thở tự nhiên"}]}'::jsonb,
      ARRAY['Không cố hít thật sâu hoặc giữ hơi.','Dừng và trở về nhịp thở tự nhiên nếu chóng mặt, hụt hơi hoặc khó chịu.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000202'::uuid, 'PRACTICE', 'GROUNDING_GUIDE', 'REPEATABLE', 'STEPS', true, 6::smallint, 0::smallint, 7::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[],
      '{"steps":[{"id":"support","label":"Cảm nhận cơ thể đang được nâng đỡ"},{"id":"see","label":"Gọi tên 5 điều bạn nhìn thấy"},{"id":"hear","label":"Chú ý 3 âm thanh xung quanh"},{"id":"touch","label":"Nhận biết 2 cảm giác chạm"},{"id":"return","label":"Chọn điều bạn muốn làm tiếp theo"}]}'::jsonb,
      ARRAY['Dừng lại nếu việc hướng sự chú ý vào cơ thể làm bạn khó chịu hơn.','Bạn có thể chuyển sang quan sát đồ vật hoặc âm thanh bên ngoài.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000203'::uuid, 'PRACTICE', 'UNHOOKING_PROMPTS', 'REPEATABLE', 'STEPS', true, 6::smallint, 1::smallint, 5::smallint,
      ARRAY['ANXIETY_SYMPTOMS','DEPRESSIVE_SYMPTOMS']::text[],
      '{"steps":[{"id":"notice","label":"Nhận biết điều đang xuất hiện"},{"id":"name","label":"Gọi tên suy nghĩ hoặc cảm xúc"},{"id":"anchor","label":"Đưa chú ý về việc trước mắt"},{"id":"choose","label":"Chọn một hành động nhỏ có ích"}]}'::jsonb,
      ARRAY['Bài tập không yêu cầu bạn phủ nhận hoặc tranh luận với cảm xúc.','Dừng lại nếu bài tập làm trải nghiệm trở nên quá sức.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000204'::uuid, 'PRACTICE', 'SELF_COMPASSION_PROMPTS', 'REPEATABLE', 'STEPS', true, 6::smallint, 1::smallint, 4::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[],
      '{"steps":[{"id":"acknowledge","label":"Công nhận điều đang khó"},{"id":"perspective","label":"Nghĩ xem bạn sẽ nói gì với người mình quý"},{"id":"kind-phrase","label":"Chọn một câu thực tế và tử tế"},{"id":"next-step","label":"Chọn một bước vừa sức"}]}'::jsonb,
      ARRAY['Tự cảm thông không có nghĩa là bỏ qua trách nhiệm hoặc khó khăn thực tế.']::text[], 'REVIEW_REQUIRED'),
    ('00000000-0000-4000-8000-000000000205'::uuid, 'LEARNING', 'STRUCTURED_READER', 'ONE_TIME', 'EXPLICIT', false, 7::smallint, 0::smallint, 1::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS']::text[], '{}'::jsonb,
      ARRAY['Thông tin này không dùng để tự chẩn đoán.','Tìm hỗ trợ chuyên môn nếu triệu chứng kéo dài, gây khổ sở hoặc ảnh hưởng đáng kể.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000206'::uuid, 'LEARNING', 'STRUCTURED_READER', 'ONE_TIME', 'EXPLICIT', false, 7::smallint, 0::smallint, 1::smallint,
      ARRAY['ANXIETY_SYMPTOMS']::text[], '{}'::jsonb,
      ARRAY['Thông tin này không dùng để tự chẩn đoán.','Trao đổi với chuyên gia nếu lo âu kéo dài hoặc ảnh hưởng nhiều đến sinh hoạt.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000207'::uuid, 'LEARNING', 'STRUCTURED_READER', 'ONE_TIME', 'EXPLICIT', false, 7::smallint, 0::smallint, 1::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[], '{}'::jsonb,
      ARRAY['Các gợi ý hỗ trợ thói quen ngủ, không điều trị rối loạn giấc ngủ.','Trao đổi với nhân viên y tế nếu khó ngủ kéo dài hoặc ảnh hưởng nhiều ban ngày.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000208'::uuid, 'ACTION', 'BEHAVIORAL_ACTIVATION_PLANNER', 'REPEATABLE', 'STEPS', true, 8::smallint, 1::smallint, 5::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS']::text[],
      '{"steps":[{"id":"choose","label":"Chọn một việc nhỏ: thường ngày, cần thiết hoặc dễ chịu"},{"id":"shrink","label":"Thu nhỏ thành bước có thể bắt đầu trong 5 phút"},{"id":"schedule","label":"Chọn thời điểm và nơi thực hiện"},{"id":"do","label":"Thử bước đầu tiên"},{"id":"review","label":"Ghi nhận điều đã làm được"}]}'::jsonb,
      ARRAY['Chọn hoạt động phù hợp sức khỏe và năng lượng hiện tại.','Có thể dừng hoặc đổi sang bước nhỏ hơn mà không xem đó là thất bại.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000209'::uuid, 'LEARNING', 'STRUCTURED_READER', 'ONE_TIME', 'EXPLICIT', false, 8::smallint, 0::smallint, 1::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[], '{}'::jsonb,
      ARRAY['Không phải mọi nỗi lo đều có thể giải quyết ngay.','Chỉ chọn bước nằm trong khả năng và quyền kiểm soát của bạn.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000210'::uuid, 'REFLECTION', 'REFLECTION', 'REPEATABLE', 'STEPS', false, 10::smallint, 2::smallint, 3::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[],
      '{"steps":[{"id":"situation","label":"Xác định tình huống"},{"id":"feeling","label":"Gọi tên cảm xúc"},{"id":"thought","label":"Nhận biết suy nghĩ tự động"},{"id":"evidence","label":"Xem dữ kiện ủng hộ và chưa ủng hộ"},{"id":"balanced","label":"Thử một góc nhìn cân bằng"}]}'::jsonb,
      ARRAY['Không cần ghi thông tin nhận dạng hoặc điều quá riêng tư.','Mục tiêu không phải ép bản thân suy nghĩ tích cực.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000211'::uuid, 'PRACTICE', 'REFLECTION', 'REPEATABLE', 'STEPS', false, 10::smallint, 1::smallint, 5::smallint,
      ARRAY['ANXIETY_SYMPTOMS']::text[],
      '{"steps":[{"id":"capture","label":"Ghi ngắn nỗi lo để xem lại sau"},{"id":"classify","label":"Phân biệt vấn đề có thể hành động và lo giả định"},{"id":"act","label":"Chọn một bước hoặc hẹn giờ xem lại"},{"id":"return","label":"Đưa chú ý về hiện tại"}]}'::jsonb,
      ARRAY['Không đặt giờ xem lại nỗi lo quá sát giờ ngủ nếu điều đó làm bạn tỉnh hơn.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000212'::uuid, 'ACTION', 'PREPARE_FOR_SPECIALIST_CHECKLIST', 'ONE_TIME', 'STEPS', false, 10::smallint, 0::smallint, 1::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS','PROFESSIONAL_SUPPORT']::text[],
      '{"steps":[{"id":"concerns","label":"Điều đang làm bạn khó khăn nhất"},{"id":"timeline","label":"Thời điểm bắt đầu và mức ảnh hưởng"},{"id":"medications","label":"Thuốc, thực phẩm bổ sung hoặc cách đã thử"},{"id":"questions","label":"Hai hoặc ba câu hỏi muốn được giải đáp"},{"id":"support","label":"Cân nhắc người hỗ trợ đi cùng nếu bạn muốn"}]}'::jsonb,
      ARRAY['Không thay thế cuộc trao đổi với chuyên gia.','Không lưu nội dung ghi chú nhạy cảm trong tiến độ checklist.']::text[], 'REVIEWED'),
    ('00000000-0000-4000-8000-000000000213'::uuid, 'LEARNING', 'VIDEO_TRANSCRIPT', 'ONE_TIME', 'VIDEO_CONFIRMATION', false, 7::smallint, 0::smallint, 1::smallint,
      ARRAY['ANXIETY_SYMPTOMS']::text[], '{}'::jsonb,
      ARRAY['Không cố kiểm soát hơi thở thật sâu.','Dừng và trở về nhịp tự nhiên nếu chóng mặt hoặc khó chịu.']::text[], 'REVIEW_REQUIRED'),
    ('00000000-0000-4000-8000-000000000214'::uuid, 'LEARNING', 'VIDEO_TRANSCRIPT', 'ONE_TIME', 'VIDEO_CONFIRMATION', false, 7::smallint, 0::smallint, 1::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[], '{}'::jsonb,
      ARRAY['Kỹ thuật không nhằm phủ nhận khó khăn thật sự hoặc ép suy nghĩ tích cực.']::text[], 'REVIEW_REQUIRED'),
    ('00000000-0000-4000-8000-000000000215'::uuid, 'LEARNING', 'VIDEO_TRANSCRIPT', 'ONE_TIME', 'VIDEO_CONFIRMATION', false, 9::smallint, 0::smallint, 1::smallint,
      ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS']::text[], '{}'::jsonb,
      ARRAY['Không gồng mạnh; bỏ qua vùng đau hoặc chấn thương.','Chọn bài khác hoặc hỏi nhân viên y tế nếu việc gồng cơ không phù hợp.']::text[], 'REVIEW_REQUIRED')
) AS metadata(id, resource_kind, interaction_type, repeatability, completion_mode,
              streak_eligible, duration_minutes, cooldown_days, frequency_per_week,
              plan_tags, interaction_config, safety_notes, review_status)
WHERE r.id = metadata.id;

INSERT INTO resource (
  id, category, locale, title, summary, content_body, external_url,
  source_organization, source_title, source_url, source_review_note,
  catalogue_visibility, status, reviewed_by, reviewed_at, effective_at, version,
  resource_kind, interaction_type, repeatability, completion_mode, streak_eligible,
  expected_duration_minutes, cooldown_days, recommended_frequency_per_week,
  plan_tags, structured_content, interaction_config, safety_notes,
  source_retrieved_at, content_version_label, source_review_status
)
VALUES
  ('00000000-0000-4000-8000-000000000216', 'MEDITATION', 'vi-VN',
   'Thả lỏng cơ theo từng vùng',
   'Một phiên thư giãn cơ tiến triển ngắn, lần lượt nhận biết căng và thả lỏng mà không gồng mạnh.',
   'Chọn tư thế được nâng đỡ. Đi lần lượt qua bàn tay, vai, khuôn mặt, bụng và chân. Với mỗi vùng, tạo lực căng rất nhẹ trong vài giây rồi thả hoàn toàn lâu hơn. Chú ý sự khác biệt giữa căng và thả. Có thể bỏ qua bất kỳ vùng nào.',
   NULL, 'National Center for Complementary and Integrative Health', 'Relaxation Techniques: What You Need To Know',
   'https://www.nccih.nih.gov/health/relaxation-techniques-what-you-need-to-know',
   'MentalBridge diễn giải ngắn từ mô tả progressive relaxation của NCCIH; cần rà soát lâm sàng trước production.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'PRACTICE', 'PROGRESSIVE_RELAXATION', 'REPEATABLE', 'TIMED', true, 10, 1, 4,
   ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'], '{}'::jsonb,
   '{"steps":[{"id":"hands","label":"Bàn tay","seconds":20},{"id":"shoulders","label":"Vai","seconds":20},{"id":"face","label":"Khuôn mặt","seconds":20},{"id":"body","label":"Bụng và thân người","seconds":20},{"id":"legs","label":"Chân","seconds":20}]}'::jsonb,
   ARRAY['Không gồng mạnh; bỏ qua vùng đau, chấn thương hoặc khó chịu.','Dừng lại nếu đau, chóng mặt hoặc có triệu chứng đáng lo.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEWED'),
  ('00000000-0000-4000-8000-000000000217', 'COMMUNITY', 'vi-VN',
   'Đi bộ nhẹ trong 10 phút',
   'Một phiên đi bộ vừa sức, không cần GPS hay mục tiêu số bước.',
   'Chọn nơi an toàn và nhịp đi bạn có thể duy trì thoải mái. Bạn có thể đi trong nhà, ngoài trời hoặc rút ngắn thời gian. Chú ý môi trường xung quanh và kết thúc sớm nếu cơ thể báo cần dừng.',
   NULL, 'World Health Organization', 'Physical activity',
   'https://www.who.int/news-room/fact-sheets/detail/physical-activity',
   'MentalBridge chuyển khuyến nghị vận động chung thành hoạt động nhẹ, không tuyên bố điều trị.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'HABIT', 'WALK_TIMER', 'REPEATABLE', 'TIMED', true, 10, 0, 7,
   ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'], '{}'::jsonb,
   '{"durationSeconds":600,"steps":[{"id":"prepare","label":"Chọn nơi an toàn và nhịp vừa sức"},{"id":"walk","label":"Đi bộ hoặc vận động tại chỗ"},{"id":"finish","label":"Chậm lại và kết thúc"}]}'::jsonb,
   ARRAY['Có thể chọn đi tại chỗ hoặc phiên ngắn hơn.','Dừng nếu đau, chóng mặt, khó thở bất thường hoặc thấy không an toàn.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEWED'),
  ('00000000-0000-4000-8000-000000000218', 'COMMUNITY', 'vi-VN',
   'Giãn cơ và đổi tư thế trong 7 phút',
   'Một chuỗi vận động nhẹ để đổi tư thế sau thời gian ngồi hoặc ít vận động.',
   'Thực hiện chậm trong phạm vi chuyển động dễ chịu: xoay vai, nghiêng cổ rất nhẹ, mở bàn tay, vươn người và duỗi cổ chân. Mỗi động tác có thể thực hiện khi ngồi và có thể bỏ qua.',
   NULL, 'NHS Every Mind Matters', 'Be active for your mental health',
   'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/be-active-for-your-mental-health/',
   'Hoạt động vận động nhẹ do MentalBridge biên soạn; không phải hướng dẫn phục hồi chức năng.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'HABIT', 'STRETCH_SEQUENCE', 'REPEATABLE', 'STEPS', true, 7, 0, 7,
   ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'], '{}'::jsonb,
   '{"steps":[{"id":"shoulders","label":"Xoay vai chậm"},{"id":"hands","label":"Mở và khép bàn tay"},{"id":"reach","label":"Vươn người trong tầm dễ chịu"},{"id":"ankles","label":"Duỗi và xoay cổ chân"}]}'::jsonb,
   ARRAY['Có thể làm khi ngồi hoặc bỏ qua động tác không phù hợp.','Dừng nếu đau, chóng mặt hoặc có triệu chứng đáng lo.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEW_REQUIRED'),
  ('00000000-0000-4000-8000-000000000219', 'JOURNALING', 'vi-VN',
   'Giải quyết một vấn đề trong 10 phút',
   'Worksheet ngắn để phân loại vấn đề, cân nhắc lựa chọn và chọn một bước thực tế.',
   'Bắt đầu với một vấn đề cụ thể. Kiểm tra xem có hành động thực tế nào bạn có thể làm. Tạo vài lựa chọn, xem điều gì khả thi, chọn một bước và xác định thời điểm thử. Sau đó có thể quay lại review.',
   NULL, 'NHS Every Mind Matters', 'Problem solving',
   'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/problem-solving/',
   'MentalBridge chuyển quy trình NHS thành worksheet không lưu nội dung tự do trong tiến độ.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'PRACTICE', 'PROBLEM_SOLVING_WORKSHEET', 'REPEATABLE', 'STEPS', true, 10, 2, 3,
   ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'], '{}'::jsonb,
   '{"steps":[{"id":"define","label":"Xác định một vấn đề cụ thể"},{"id":"actionable","label":"Kiểm tra điều gì nằm trong khả năng hành động"},{"id":"options","label":"Tạo vài lựa chọn"},{"id":"choose","label":"Chọn bước nhỏ khả thi"},{"id":"schedule","label":"Chọn thời điểm thử"},{"id":"review","label":"Hẹn lúc xem lại kết quả"}]}'::jsonb,
   ARRAY['Không cần nhập chi tiết nhạy cảm để hoàn thành.','Nếu vấn đề liên quan an toàn khẩn cấp, dùng luồng hỗ trợ khẩn cấp hiện có thay vì worksheet.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEWED'),
  ('00000000-0000-4000-8000-000000000220', 'JOURNALING', 'vi-VN',
   'Lên lịch một hoạt động nhỏ có ý nghĩa',
   'Chọn một hoạt động thường ngày, cần thiết hoặc dễ chịu rồi thu nhỏ để dễ bắt đầu.',
   'Nhìn lại điều bạn đang né tránh hoặc đã ít làm hơn. Chọn một việc đủ nhỏ, cân nhắc năng lượng hiện tại, đặt thời điểm cụ thể và thử trong 5 phút. Sau khi làm, ghi nhận nỗ lực thay vì chỉ đánh giá kết quả.',
   NULL, 'NHS Every Mind Matters', 'Tackling your to-do list',
   'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/tackling-your-to-do-list/',
   'MentalBridge chuyển bốn bước NHS thành planner ngắn; không tuyên bố đây là điều trị độc lập.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'PRACTICE', 'BEHAVIORAL_ACTIVATION_PLANNER', 'REPEATABLE', 'STEPS', true, 10, 1, 5,
   ARRAY['DEPRESSIVE_SYMPTOMS'], '{}'::jsonb,
   '{"steps":[{"id":"area","label":"Chọn việc thường ngày, cần thiết hoặc dễ chịu"},{"id":"effort","label":"Ước lượng năng lượng hiện tại"},{"id":"shrink","label":"Thu nhỏ thành bước 5 phút"},{"id":"schedule","label":"Chọn lúc và nơi thực hiện"},{"id":"result","label":"Đánh dấu đã làm, bỏ qua hoặc cần thu nhỏ hơn"}]}'::jsonb,
   ARRAY['Không dùng hoạt động để tự trách hoặc ép bản thân quá sức.','Có thể chọn bước nhỏ hơn hoặc bỏ qua khi không phù hợp.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEWED'),
  ('00000000-0000-4000-8000-000000000221', 'MEDITATION', 'vi-VN',
   'Một hành động nhỏ theo điều quan trọng',
   'Nhận ra điều bạn coi trọng và chọn một hành động rất nhỏ có thể làm hôm nay.',
   'Chọn một lĩnh vực quan trọng như chăm sóc bản thân, học tập, gia đình hoặc kết nối. Phân biệt giá trị với mục tiêu phải hoàn thành. Sau đó chọn một hành động nhỏ, cụ thể và nằm trong khả năng của bạn hôm nay.',
   NULL, 'World Health Organization', 'Doing What Matters in Times of Stress',
   'https://www.who.int/publications/i/item/9789240003927',
   'MentalBridge diễn giải kỹ năng acting on values của WHO; bản WHO có điều kiện cấp phép riêng.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'ACTION', 'UNHOOKING_PROMPTS', 'REPEATABLE', 'STEPS', true, 7, 1, 4,
   ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'], '{}'::jsonb,
   '{"steps":[{"id":"value","label":"Chọn một điều quan trọng với bạn"},{"id":"tiny","label":"Thu nhỏ thành một hành động hôm nay"},{"id":"obstacle","label":"Nhận biết suy nghĩ hoặc cảm xúc có thể cản trở"},{"id":"act","label":"Làm bước nhỏ theo giá trị đã chọn"}]}'::jsonb,
   ARRAY['Không biến giá trị thành tiêu chuẩn để tự phán xét.','Chọn hành động an toàn và phù hợp hoàn cảnh.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEW_REQUIRED'),
  ('00000000-0000-4000-8000-000000000222', 'MEDITATION', 'vi-VN',
   'Ba phút nhận biết hiện tại',
   'Một bài chú ý ngắn vào môi trường và hoạt động đang diễn ra, không cần làm trống tâm trí.',
   'Dừng lại và nhận biết một điều bạn nhìn thấy, một âm thanh, một cảm giác chạm và chuyển động tự nhiên của cơ thể. Khi tâm trí đi xa, chỉ cần nhận ra rồi đưa chú ý về điều đang làm.',
   NULL, 'World Health Organization', 'Doing What Matters in Times of Stress',
   'https://www.who.int/publications/i/item/9789240003927',
   'MentalBridge diễn giải building awareness/grounding; bản WHO có điều kiện cấp phép riêng.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'PRACTICE', 'GROUNDING_GUIDE', 'REPEATABLE', 'STEPS', true, 3, 0, 7,
   ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'], '{}'::jsonb,
   '{"steps":[{"id":"see","label":"Nhận biết một điều đang thấy"},{"id":"hear","label":"Nhận biết một âm thanh"},{"id":"touch","label":"Nhận biết một cảm giác chạm"},{"id":"activity","label":"Quay về hoạt động đang làm"}]}'::jsonb,
   ARRAY['Không cần nhắm mắt hoặc tập trung vào cơ thể nếu điều đó không dễ chịu.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEW_REQUIRED'),
  ('00000000-0000-4000-8000-000000000223', 'JOURNALING', 'vi-VN',
   'Nhìn lại điều hữu ích để tiếp tục',
   'Một phần tổng kết nhẹ để chọn kỹ năng bạn muốn tiếp tục sau hành trình hiện tại.',
   'Nhìn lại những hoạt động đã thử, chọn một hoặc hai điều phù hợp nhất, nhận biết điều khiến việc thực hành dễ hơn và chọn một nhịp tiếp tục thực tế. Việc chưa hoàn thành không phải thất bại và bản tổng kết không đánh giá hồi phục.',
   NULL, 'NHS Every Mind Matters', 'Staying on top of things',
   'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/staying-on-top-of-things/',
   'MentalBridge diễn giải phần review/maintenance; không lưu nội dung phản ánh tự do trong tiến độ.',
   'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001', TIMESTAMPTZ '2026-09-29 00:00:00+00', TIMESTAMPTZ '2026-09-29 00:00:00+00', 0,
   'REFLECTION', 'REFLECTION', 'REPEATABLE', 'STEPS', false, 8, 7, 1,
   ARRAY['DEPRESSIVE_SYMPTOMS','ANXIETY_SYMPTOMS'], '{}'::jsonb,
   '{"steps":[{"id":"notice","label":"Chọn điều đã hữu ích"},{"id":"barrier","label":"Nhận biết điều làm việc thực hành khó hơn"},{"id":"continue","label":"Chọn một kỹ năng muốn tiếp tục"},{"id":"support","label":"Cân nhắc bước hỗ trợ tiếp theo khi cần"}]}'::jsonb,
   ARRAY['Bản tổng kết không đánh giá hồi phục hoặc thay thế trao đổi chuyên môn.'],
   TIMESTAMPTZ '2026-09-29 00:00:00+00', 'mb-603-curated-v1', 'REVIEWED')
ON CONFLICT (id) DO NOTHING;

UPDATE resource
SET structured_content = jsonb_build_object(
      'overview', summary,
      'whenUseful', CASE resource_kind
        WHEN 'LEARNING' THEN 'Khi bạn muốn hiểu rõ hơn trước khi chọn một bước thực hành.'
        WHEN 'HABIT' THEN 'Khi bạn muốn thêm một hoạt động nhẹ vào nhịp ngày.'
        ELSE 'Khi kỹ năng này phù hợp với điều bạn đang muốn hỗ trợ.'
      END,
      'keyIdeas', jsonb_build_array(content_body),
      'steps', COALESCE(interaction_config -> 'steps', '[]'::jsonb),
      'cautions', to_jsonb(safety_notes),
      'nextStep', CASE repeatability
        WHEN 'ONE_TIME' THEN 'Sau khi hiểu nội dung, hãy chọn một thực hành vừa sức trong kế hoạch của bạn.'
        ELSE 'Bạn có thể quay lại thực hành vào một ngày khác nếu thấy phù hợp.'
      END
    ),
    source_content_hash = encode(
      sha256(convert_to(COALESCE(source_url, '') || E'\n' || COALESCE(content_body, ''), 'UTF8')),
      'hex'
    )
WHERE id BETWEEN '00000000-0000-4000-8000-000000000201'::uuid
             AND '00000000-0000-4000-8000-000000000223'::uuid;

DO $$
BEGIN
  IF (SELECT count(*) FROM resource
      WHERE id BETWEEN '00000000-0000-4000-8000-000000000201'::uuid
                   AND '00000000-0000-4000-8000-000000000223'::uuid
        AND status = 'PUBLISHED'
        AND catalogue_visibility = 'LISTED'
        AND cardinality(plan_tags) > 0
        AND structured_content <> '{}'::jsonb) <> 23 THEN
    RAISE EXCEPTION 'MB-603 curated resource inventory differs from the reviewed demo catalogue';
  END IF;
END;
$$;
