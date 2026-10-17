-- Up Migration
-- Migration: 9_update_mb603_resource_wellbeing_summaries
-- Service:   content-notification-service
-- Database:  mentalbridge_content_notification
-- Story:     MB-603 - clarify the mental-wellbeing purpose of each resource
--
-- These concise descriptions use cautious self-help language ("hỗ trợ", "rèn",
-- "giúp nhận biết") and do not claim to diagnose, treat, or guarantee an outcome.

WITH reviewed_summary(id, summary) AS (
  VALUES
    ('00000000-0000-4000-8000-000000000201'::uuid,
     'Luyện nhịp thở chậm để giúp cơ thể dịu lại và hỗ trợ giảm cảm giác căng thẳng, lo lắng trong lúc thực hành.'),
    ('00000000-0000-4000-8000-000000000202'::uuid,
     'Đưa chú ý về cơ thể và môi trường hiện tại để bớt bị cuốn theo căng thẳng và lấy lại cảm giác ổn định.'),
    ('00000000-0000-4000-8000-000000000203'::uuid,
     'Tạo khoảng cách với suy nghĩ, cảm xúc khó chịu để phản ứng linh hoạt hơn và tập trung lại vào việc đang làm.'),
    ('00000000-0000-4000-8000-000000000204'::uuid,
     'Rèn cách tự đối thoại cân bằng, tử tế để giảm tự chỉ trích và nâng đỡ khả năng ứng phó khi gặp khó khăn.'),
    ('00000000-0000-4000-8000-000000000205'::uuid,
     'Giúp nhận biết sớm các dấu hiệu thường gặp của trầm cảm để hiểu trải nghiệm của mình và biết khi nào nên tìm hỗ trợ.'),
    ('00000000-0000-4000-8000-000000000206'::uuid,
     'Giúp nhận biết các biểu hiện thường gặp của lo âu để hiểu phản ứng của mình và biết khi nào nên tìm hỗ trợ.'),
    ('00000000-0000-4000-8000-000000000207'::uuid,
     'Xây dựng thói quen hỗ trợ giấc ngủ đều và dễ chịu hơn, từ đó nâng đỡ tâm trạng, năng lượng và khả năng tập trung.'),
    ('00000000-0000-4000-8000-000000000208'::uuid,
     'Chia việc thành bước nhỏ để dễ bắt đầu, tăng cảm giác hoàn thành và từng bước khôi phục nhịp hoạt động khi động lực thấp.'),
    ('00000000-0000-4000-8000-000000000209'::uuid,
     'Sắp xếp vấn đề thành các bước có thể hành động để giảm cảm giác quá tải và tăng cảm giác chủ động.'),
    ('00000000-0000-4000-8000-000000000210'::uuid,
     'Kiểm tra suy nghĩ bằng dữ kiện để hình thành góc nhìn cân bằng hơn và giảm ảnh hưởng của những suy nghĩ thiếu hữu ích.'),
    ('00000000-0000-4000-8000-000000000211'::uuid,
     'Dành thời gian riêng để xem xét nỗi lo, giúp bớt bị lo lắng chi phối và đưa chú ý trở lại hiện tại.'),
    ('00000000-0000-4000-8000-000000000212'::uuid,
     'Chuẩn bị triệu chứng, mốc thời gian và câu hỏi để trao đổi rõ ràng hơn, giúp buổi gặp chuyên gia sát với nhu cầu.'),
    ('00000000-0000-4000-8000-000000000213'::uuid,
     'Rèn khả năng chú ý vào hơi thở và hiện tại để giúp tâm trí chậm lại khi căng thẳng hoặc lo lắng.'),
    ('00000000-0000-4000-8000-000000000214'::uuid,
     'Học cách kiểm tra bằng chứng và điều chỉnh suy nghĩ thiếu hữu ích để xây dựng góc nhìn cân bằng hơn.'),
    ('00000000-0000-4000-8000-000000000215'::uuid,
     'Nhận biết rồi thả lỏng căng cơ theo từng vùng để hỗ trợ thư giãn cơ thể và giảm cảm giác căng thẳng.'),
    ('00000000-0000-4000-8000-000000000216'::uuid,
     'Thực hành căng – thả nhẹ từng nhóm cơ để nhận biết căng cơ sớm hơn và hỗ trợ cơ thể thư giãn.'),
    ('00000000-0000-4000-8000-000000000217'::uuid,
     'Vận động vừa sức để hỗ trợ tâm trạng, giảm căng thẳng và duy trì sức khỏe tinh thần thông qua hoạt động thể chất.'),
    ('00000000-0000-4000-8000-000000000218'::uuid,
     'Tạo một khoảng nghỉ vận động để giảm cảm giác ì trệ, hỗ trợ sự tỉnh táo và giúp tâm trạng dễ chịu hơn.'),
    ('00000000-0000-4000-8000-000000000219'::uuid,
     'Biến một vấn đề cụ thể thành bước khả thi để giảm cảm giác quá tải và tăng sự chủ động trong việc ứng phó.'),
    ('00000000-0000-4000-8000-000000000220'::uuid,
     'Lên lịch một hoạt động nhỏ có ý nghĩa để hỗ trợ động lực, cảm giác hoàn thành và kết nối lại với sinh hoạt hằng ngày.'),
    ('00000000-0000-4000-8000-000000000221'::uuid,
     'Kết nối với điều mình coi trọng và chọn hành động phù hợp để tăng cảm giác định hướng, ý nghĩa và chủ động.'),
    ('00000000-0000-4000-8000-000000000222'::uuid,
     'Rèn khả năng nhận biết hiện tại để bớt bị cuốn theo suy nghĩ căng thẳng và lấy lại sự tập trung.'),
    ('00000000-0000-4000-8000-000000000223'::uuid,
     'Nhìn lại kỹ năng đã hữu ích để củng cố điều phù hợp và duy trì thói quen chăm sóc sức khỏe tinh thần.')
)
UPDATE resource r
SET summary = reviewed_summary.summary,
    structured_content = jsonb_set(
      r.structured_content,
      '{overview}',
      to_jsonb(reviewed_summary.summary),
      true
    ),
    source_retrieved_at = TIMESTAMPTZ '2026-09-30 00:00:00+00'
FROM reviewed_summary
WHERE r.id = reviewed_summary.id
  AND r.locale = 'vi-VN'
  AND r.status = 'PUBLISHED'
  AND r.catalogue_visibility = 'LISTED';

DO $$
BEGIN
  IF (SELECT count(*)
      FROM resource
      WHERE id BETWEEN '00000000-0000-4000-8000-000000000201'::uuid
                   AND '00000000-0000-4000-8000-000000000223'::uuid
        AND locale = 'vi-VN'
        AND status = 'PUBLISHED'
        AND catalogue_visibility = 'LISTED'
        AND summary = structured_content ->> 'overview'
        AND source_retrieved_at = TIMESTAMPTZ '2026-09-30 00:00:00+00') <> 23 THEN
    RAISE EXCEPTION 'MB-603 wellbeing summaries were not applied to the complete reviewed catalogue';
  END IF;
END;
$$;
