-- Up Migration
-- Migration: 6_seed_mb556_reviewed_resource_catalogue
-- Service: content-notification-service
-- Database: mentalbridge_content_notification
-- Story: MB-556 - Reviewed resource catalogue and consumable resource detail

UPDATE resource
SET catalogue_visibility = 'DIRECT_ONLY'
WHERE id IN (
  '00000000-0000-4000-8000-000000000101'::uuid,
  '00000000-0000-4000-8000-000000000102'::uuid,
  '00000000-0000-4000-8000-000000000103'::uuid,
  '00000000-0000-4000-8000-000000000104'::uuid,
  '00000000-0000-4000-8000-000000000105'::uuid,
  '00000000-0000-4000-8000-000000000106'::uuid
);

INSERT INTO resource (
  id, category, locale, title, summary, content_body, external_url,
  source_organization, source_title, source_url, source_review_note,
  catalogue_visibility, status, reviewed_by, reviewed_at, effective_at, version
)
VALUES
  (
    '00000000-0000-4000-8000-000000000201', 'BREATHING', 'vi-VN',
    'Thở chậm trong 5 phút',
    'Một bài thở đơn giản giúp bạn chậm lại và chú ý đến nhịp thở khi cảm thấy căng thẳng hoặc lo lắng.',
    'Chọn tư thế ngồi, đứng hoặc nằm mà bạn thấy thoải mái. Thả lỏng vai và để bàn chân hoặc cơ thể được nâng đỡ chắc chắn. Hít vào nhẹ nhàng qua mũi, sau đó thở ra chậm. Bạn có thể đếm đều từ 1 đến 4 hoặc 1 đến 5 cho mỗi nhịp nếu thấy dễ chịu. Không cần cố hít thật sâu hoặc giữ hơi. Tiếp tục khoảng 3–5 phút và chú ý cảm giác không khí đi vào, đi ra. Nếu thấy chóng mặt, khó chịu hoặc hụt hơi, hãy dừng lại và trở về nhịp thở tự nhiên. Đây là bài thực hành thư giãn, không phải cách xử trí tình huống khẩn cấp.',
    NULL, 'NHS', 'Breathing exercises for stress',
    'https://www.nhs.uk/mental-health/self-help/guides-tools-and-activities/breathing-exercises-for-stress/',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000202', 'MEDITATION', 'vi-VN',
    'Grounding: quay về với hiện tại',
    'Một bài thực hành ngắn giúp bạn đưa sự chú ý trở lại cơ thể và môi trường xung quanh khi tâm trí đang bị cuốn vào căng thẳng.',
    'Dành một hoặc hai phút để nhận biết mình đang ở đâu. Cảm nhận bàn chân chạm sàn hoặc cơ thể chạm ghế. Thả chậm nhịp thở mà không ép buộc. Nhìn quanh và gọi tên vài điều bạn đang thấy; sau đó chú ý một vài âm thanh, cảm giác chạm hoặc nhiệt độ trên da. Mục tiêu không phải là làm biến mất mọi suy nghĩ hay cảm xúc, mà là giúp sự chú ý quay về những gì đang xảy ra ở hiện tại. Bạn có thể luyện tập khi đang bình thường để dễ sử dụng hơn lúc căng thẳng. Nếu bài tập làm bạn khó chịu hơn, hãy dừng lại và chọn một hoạt động ổn định khác.',
    NULL, 'World Health Organization', 'Doing What Matters in Times of Stress / Grounding',
    'https://www.emro.who.int/mhps/dealing_with_stress.html',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000203', 'MEDITATION', 'vi-VN',
    'Nhận biết, gọi tên và đưa sự chú ý trở lại',
    'Thực hành nhận ra suy nghĩ hoặc cảm xúc khó chịu mà không cần tranh đấu với chúng ngay lập tức.',
    'Khi nhận thấy mình đang bị cuốn vào một suy nghĩ hoặc cảm xúc khó chịu, thử dừng lại trong vài giây. Gọi tên trải nghiệm theo cách đơn giản như “mình đang nhận thấy sự lo lắng” hoặc “mình đang có suy nghĩ rằng…”. Sau đó đưa sự chú ý trở lại một việc đang diễn ra trước mắt: hơi thở, bàn chân chạm sàn, âm thanh trong phòng hoặc công việc bạn đang làm. Bạn không cần chứng minh suy nghĩ đó đúng hay sai ngay lúc này. Bài tập nhằm tạo một khoảng nhỏ giữa trải nghiệm và cách bạn phản ứng với nó, để bạn có thêm lựa chọn cho bước tiếp theo.',
    NULL, 'World Health Organization', 'Doing What Matters in Times of Stress',
    'https://www.who.int/publications-detail-redirect/9789240003927',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000204', 'MEDITATION', 'vi-VN',
    'Một khoảng dừng tử tế với bản thân',
    'Một bài thực hành ngắn để nhận ra lúc mình đang tự chỉ trích và thử chuyển sang cách nói chuyện cân bằng, tử tế hơn.',
    'Hãy chọn một tình huống gần đây khiến bạn thất vọng về bản thân. Trước tiên, nhận biết câu tự phê bình đang xuất hiện mà không cố xua nó đi. Sau đó tự hỏi: nếu một người mình quý gặp đúng tình huống này, mình sẽ nói gì với họ? Thử viết hoặc nói với chính mình một câu vừa thực tế vừa tử tế, ví dụ công nhận điều đang khó khăn và điều nhỏ bạn có thể làm tiếp theo. Tự cảm thông không có nghĩa là bỏ qua trách nhiệm; mục tiêu là giảm cách tự công kích để bạn có thể nhìn tình huống rõ hơn và lựa chọn hành động hữu ích hơn.',
    NULL, 'Centre for Clinical Interventions, Government of Western Australia', 'Building Self-Compassion',
    'https://cci.health.wa.gov.au/Resources/Looking-After-Yourself/Self-Compassion',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; URL CCI cần được rà soát thủ công cùng nội dung và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000205', 'ARTICLE', 'vi-VN',
    'Hiểu các dấu hiệu thường gặp của trầm cảm',
    'Thông tin cơ bản giúp bạn nhận biết những thay đổi về tâm trạng, hứng thú, năng lượng, giấc ngủ và khả năng sinh hoạt có thể đi cùng trầm cảm.',
    'Trầm cảm không chỉ là cảm giác buồn. Một số người có thể thấy mất hứng thú với những việc từng thích, ít năng lượng, khó tập trung, thay đổi giấc ngủ hoặc ăn uống, cảm giác vô vọng, tội lỗi hoặc thu mình khỏi người khác. Mỗi người có thể trải nghiệm khác nhau và không cần có tất cả dấu hiệu. Những biểu hiện này cũng có thể liên quan đến các vấn đề sức khỏe khác, vì vậy một bài sàng lọc hoặc tài liệu tự đọc không thể tự xác định chẩn đoán. Nếu các triệu chứng kéo dài, gây khổ sở hoặc ảnh hưởng đáng kể đến sinh hoạt, nên trao đổi với chuyên gia y tế hoặc sức khỏe tâm thần.',
    NULL, 'National Institute of Mental Health (NIMH)', 'Depression',
    'https://www.nimh.nih.gov/health/publications/depression',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000206', 'ARTICLE', 'vi-VN',
    'Hiểu lo âu và những dấu hiệu thường gặp',
    'Tổng quan ngắn về lo âu kéo dài, bao gồm lo lắng khó kiểm soát, căng thẳng cơ thể, khó thư giãn và khó ngủ.',
    'Lo âu là một phản ứng bình thường, nhưng có thể trở thành vấn đề khi lo lắng xuất hiện thường xuyên, khó kiểm soát và ảnh hưởng đến cuộc sống. Một số dấu hiệu có thể gồm bồn chồn, khó thư giãn, dễ cáu, khó tập trung, mệt mỏi, căng cơ hoặc khó ngủ. Lo lắng có thể xoay quanh nhiều lĩnh vực như công việc, sức khỏe, tài chính hoặc gia đình. Các triệu chứng thể chất và tâm lý cũng có thể do nguyên nhân khác, nên nội dung này không dùng để tự chẩn đoán. Nếu lo âu kéo dài hoặc khiến bạn khó học tập, làm việc hay sinh hoạt, hãy cân nhắc trao đổi với chuyên gia.',
    NULL, 'National Institute of Mental Health (NIMH)', 'Generalized Anxiety Disorder: What You Need to Know',
    'https://www.nimh.nih.gov/health/publications/generalized-anxiety-disorder-gad',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000207', 'ARTICLE', 'vi-VN',
    'Chuẩn bị một nhịp ngủ dễ chịu hơn',
    'Các thay đổi nhỏ về giờ giấc, không gian ngủ và thói quen trước khi ngủ có thể giúp tạo điều kiện thuận lợi hơn cho giấc ngủ.',
    'Nếu giấc ngủ gần đây thất thường, hãy bắt đầu bằng vài thay đổi nhỏ thay vì cố ép mình phải ngủ ngay. Cố gắng giữ giờ thức dậy tương đối ổn định, dành một khoảng thời gian thư giãn trước khi ngủ và làm phòng ngủ yên, tối, mát ở mức bạn thấy dễ chịu. Hạn chế caffeine vào cuối ngày và giảm các hoạt động quá kích thích ngay trước giờ ngủ. Nếu nằm lâu mà vẫn tỉnh, có thể rời giường một lúc để làm việc nhẹ nhàng rồi quay lại khi buồn ngủ. Những gợi ý này hỗ trợ thói quen ngủ; nếu khó ngủ kéo dài hoặc ảnh hưởng nhiều đến ban ngày, nên trao đổi với nhân viên y tế.',
    NULL, 'NHS Every Mind Matters', 'How to fall asleep faster and sleep better',
    'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/how-to-fall-asleep-faster-and-sleep-better/',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000208', 'ARTICLE', 'vi-VN',
    'Bắt đầu bằng một hoạt động nhỏ',
    'Khi thiếu động lực, chia hoạt động thành bước rất nhỏ có thể giúp việc bắt đầu bớt nặng nề hơn.',
    'Khi tâm trạng thấp, việc né tránh hoặc trì hoãn có thể khiến ngày càng ít hoạt động mang lại cảm giác hoàn thành hoặc dễ chịu. Hãy chọn một việc đủ nhỏ để bắt đầu hôm nay, chẳng hạn cất một món đồ, đi bộ vài phút, tắm, trả lời một tin nhắn hoặc chuẩn bị một bữa đơn giản. Đặt mục tiêu chỉ làm trong khoảng 5 phút nếu điều đó giúp việc bắt đầu dễ hơn. Sau đó tự hỏi: mình có muốn tiếp tục thêm một chút hay dừng ở đây là đủ? Khi lên kế hoạch cho tuần, cố gắng có sự cân bằng giữa việc cần làm, việc thường ngày và một vài hoạt động bạn từng thấy dễ chịu hoặc có ý nghĩa.',
    NULL, 'NHS Every Mind Matters', 'Tackling your to-do list',
    'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/tackling-your-to-do-list/',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000209', 'ARTICLE', 'vi-VN',
    'Giải quyết một vấn đề theo từng bước',
    'Một khung đơn giản giúp tách vấn đề có thể hành động khỏi những điều hiện chưa thể kiểm soát.',
    'Chọn một vấn đề cụ thể đang làm bạn thấy quá tải. Trước hết hỏi: có hành động thực tế nào mình có thể làm với vấn đề này không? Nếu có, viết ra vài phương án mà chưa cần đánh giá ngay. Sau đó xem từng phương án: điều gì có lợi, điều gì khó, mình cần ai hoặc nguồn lực nào hỗ trợ? Chọn một bước nhỏ và ghi rõ bạn sẽ làm gì, khi nào và ở đâu. Sau khi thử, dành ít phút xem điều gì hiệu quả và điều gì cần điều chỉnh. Nếu vấn đề hiện chưa thể giải quyết, hãy ghi lại để xem vào một thời điểm đã định thay vì tiếp tục suy nghĩ về nó cả ngày.',
    NULL, 'NHS Every Mind Matters', 'Problem solving',
    'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/problem-solving/',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000210', 'JOURNALING', 'vi-VN',
    '7 câu hỏi để nhìn lại một suy nghĩ khó chịu',
    'Một mẫu ghi chép giúp bạn tách tình huống, cảm xúc, suy nghĩ và bằng chứng để thử tìm góc nhìn cân bằng hơn.',
    'Chọn một tình huống gần đây và lần lượt ghi ngắn gọn: (1) Điều gì đã xảy ra? (2) Bạn cảm thấy gì và mức độ mạnh ra sao? (3) Suy nghĩ tự động nào xuất hiện? (4) Điều gì khiến suy nghĩ đó có vẻ đúng? (5) Có dữ kiện nào cho thấy bức tranh chưa đầy đủ hoặc có cách hiểu khác? (6) Một cách nhìn thực tế, cân bằng hơn có thể là gì? (7) Sau khi xem lại, cảm xúc của bạn thay đổi thế nào? Mục tiêu không phải ép mình “nghĩ tích cực”, mà là kiểm tra xem suy nghĩ ban đầu có bỏ sót thông tin quan trọng hay không.',
    NULL, 'NHS Every Mind Matters', 'Thought record',
    'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/thought-record/',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000211', 'JOURNALING', 'vi-VN',
    'Dành một khoảng thời gian riêng cho nỗi lo',
    'Một cách ghi lại lo lắng và hẹn thời điểm xem xét chúng thay vì để chúng chiếm cả ngày.',
    'Khi một nỗi lo xuất hiện, ghi nhanh một câu đủ để bạn nhớ lại rồi quay về việc đang làm. Chọn một khoảng 10–15 phút cố định trong ngày để xem lại danh sách, tốt nhất không quá sát giờ ngủ nếu việc suy nghĩ làm bạn tỉnh hơn. Với từng nỗi lo, hỏi: đây là vấn đề mình có thể làm gì ngay hoặc lên lịch xử lý không? Nếu có, ghi bước cụ thể tiếp theo. Nếu chưa có hành động nào khả thi, thử ghi nhận rằng hiện tại bạn chưa thể kiểm soát điều này và đưa sự chú ý trở lại hiện tại. Bài tập cần thời gian luyện tập và không nhằm loại bỏ hoàn toàn mọi lo lắng.',
    NULL, 'NHS Every Mind Matters', 'Tackling your worries',
    'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/tackling-your-worries/',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000212', 'JOURNALING', 'vi-VN',
    'Chuẩn bị trước khi trao đổi với chuyên gia',
    'Một danh sách gợi ý để bạn mang những điều quan trọng vào buổi gặp với bác sĩ hoặc chuyên gia sức khỏe tâm thần.',
    'Trước buổi gặp, bạn có thể ghi lại: điều gì đang làm bạn khó khăn nhất; triệu chứng hoặc thay đổi bắt đầu từ khi nào; chúng xuất hiện bao lâu, thường xuyên đến đâu và ảnh hưởng thế nào đến học tập, công việc, giấc ngủ hoặc các mối quan hệ. Ghi thêm thuốc, thực phẩm bổ sung hoặc chất bạn đang dùng nếu có, cùng những cách bạn đã thử để tự hỗ trợ. Cuối cùng, viết 2–3 câu hỏi bạn muốn được giải đáp. Nếu thấy khó nhớ thông tin khi trao đổi, bạn có thể cân nhắc nhờ một người tin cậy đi cùng nếu phù hợp và bạn muốn điều đó.',
    NULL, 'National Institute of Mental Health (NIMH)', 'Tips for Talking With a Health Care Provider About Your Mental Health',
    'https://www.nimh.nih.gov/health/publications/tips-for-talking-with-your-health-care-provider',
    'Nội dung tiếng Việt do MentalBridge biên soạn dựa trên nguồn tham khảo; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000213', 'VIDEO', 'vi-VN',
    'Video: Thở chánh niệm ngắn',
    'Video hướng dẫn ngắn của Every Mind Matters giúp đưa sự chú ý về nhịp thở và hiện tại.',
    'Bạn có thể xem video và thực hành theo ở nơi tương đối yên tĩnh. Không cần cố kiểm soát hơi thở thật sâu; chỉ cần quan sát và làm theo ở mức bạn thấy dễ chịu. Nếu thấy chóng mặt hoặc khó chịu, hãy dừng lại và trở về nhịp thở tự nhiên.',
    'https://www.youtube.com/watch?v=wfDTp2GogaQ', 'NHS Every Mind Matters', 'Mindful Breathing Exercise',
    'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/top-tips-to-improve-your-mental-wellbeing/',
    'Video và tác giả Every Mind Matters được xác minh qua YouTube oEmbed ngày 2026-09-23; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000214', 'VIDEO', 'vi-VN',
    'Video: Nhìn lại những suy nghĩ chưa hữu ích',
    'Video của Every Mind Matters giới thiệu cách dừng lại, kiểm tra bằng chứng và thử một góc nhìn cân bằng hơn.',
    'Video này phù hợp khi bạn muốn làm quen nhanh với kỹ thuật nhìn lại suy nghĩ. Sau khi xem, hãy chọn một suy nghĩ gần đây và thử ghi ra điều ủng hộ, điều không ủng hộ, rồi viết một cách diễn đạt thực tế hơn. Kỹ thuật này không nhằm phủ nhận khó khăn thật sự hoặc ép bạn phải suy nghĩ tích cực.',
    'https://www.youtube.com/watch?v=tfkhkFwCtxs', 'NHS Every Mind Matters', 'Reframe Unhelpful Thoughts',
    'https://www.nhs.uk/every-mind-matters/mental-wellbeing-tips/self-help-cbt-techniques/reframing-unhelpful-thoughts/',
    'Video và tác giả Every Mind Matters được xác minh qua YouTube oEmbed ngày 2026-09-23; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  ),
  (
    '00000000-0000-4000-8000-000000000215', 'VIDEO', 'vi-VN',
    'Video: Thư giãn cơ tiến triển',
    'Bài hướng dẫn giúp nhận biết sự khác biệt giữa căng và thả lỏng từng nhóm cơ.',
    'Chọn nơi bạn có thể ngồi hoặc nằm thoải mái. Video sẽ hướng dẫn lần lượt làm căng nhẹ rồi thả lỏng các nhóm cơ để bạn nhận biết rõ hơn cảm giác căng và thư giãn. Không cần gồng mạnh. Bỏ qua vùng đang đau, bị chấn thương hoặc khiến bạn khó chịu. Đây là bài thư giãn bổ trợ; nếu bạn có tình trạng cơ xương, thần kinh hoặc sức khỏe khiến việc gồng cơ không phù hợp, hãy chọn một bài thư giãn khác hoặc hỏi nhân viên y tế.',
    'https://www.youtube.com/watch?v=9GURt2pvdAg', 'NHS Every Mind Matters', 'Progressive Muscle Relaxation - Audio Only',
    'https://www.nhs.uk/every-mind-matters/lifes-challenges/health-issues/',
    'Video và tác giả Every Mind Matters được xác minh qua YouTube oEmbed ngày 2026-09-23; cần rà soát lâm sàng và quyền sử dụng trước production.',
    'LISTED', 'PUBLISHED', '00000000-0000-4000-8000-000000000001',
    TIMESTAMPTZ '2026-09-23 00:00:00+00', TIMESTAMPTZ '2026-09-23 00:00:00+00', 0
  )
ON CONFLICT (id) DO NOTHING;

DO $$
BEGIN
  IF EXISTS (
    WITH expected(id, title) AS (
      VALUES
        ('00000000-0000-4000-8000-000000000201'::uuid, 'Thở chậm trong 5 phút'),
        ('00000000-0000-4000-8000-000000000202'::uuid, 'Grounding: quay về với hiện tại'),
        ('00000000-0000-4000-8000-000000000203'::uuid, 'Nhận biết, gọi tên và đưa sự chú ý trở lại'),
        ('00000000-0000-4000-8000-000000000204'::uuid, 'Một khoảng dừng tử tế với bản thân'),
        ('00000000-0000-4000-8000-000000000205'::uuid, 'Hiểu các dấu hiệu thường gặp của trầm cảm'),
        ('00000000-0000-4000-8000-000000000206'::uuid, 'Hiểu lo âu và những dấu hiệu thường gặp'),
        ('00000000-0000-4000-8000-000000000207'::uuid, 'Chuẩn bị một nhịp ngủ dễ chịu hơn'),
        ('00000000-0000-4000-8000-000000000208'::uuid, 'Bắt đầu bằng một hoạt động nhỏ'),
        ('00000000-0000-4000-8000-000000000209'::uuid, 'Giải quyết một vấn đề theo từng bước'),
        ('00000000-0000-4000-8000-000000000210'::uuid, '7 câu hỏi để nhìn lại một suy nghĩ khó chịu'),
        ('00000000-0000-4000-8000-000000000211'::uuid, 'Dành một khoảng thời gian riêng cho nỗi lo'),
        ('00000000-0000-4000-8000-000000000212'::uuid, 'Chuẩn bị trước khi trao đổi với chuyên gia'),
        ('00000000-0000-4000-8000-000000000213'::uuid, 'Video: Thở chánh niệm ngắn'),
        ('00000000-0000-4000-8000-000000000214'::uuid, 'Video: Nhìn lại những suy nghĩ chưa hữu ích'),
        ('00000000-0000-4000-8000-000000000215'::uuid, 'Video: Thư giãn cơ tiến triển')
    )
    SELECT 1
    FROM expected e
    LEFT JOIN resource r ON r.id = e.id
    WHERE r.id IS NULL
       OR r.title IS DISTINCT FROM e.title
       OR r.locale IS DISTINCT FROM 'vi-VN'
       OR r.status IS DISTINCT FROM 'PUBLISHED'
       OR r.catalogue_visibility IS DISTINCT FROM 'LISTED'
       OR r.source_organization IS NULL
       OR r.source_title IS NULL
       OR r.source_url IS NULL
       OR r.source_review_note IS NULL
       OR r.version IS DISTINCT FROM 0
  ) THEN
    RAISE EXCEPTION 'MB-556 reviewed resource catalogue differs from the reviewed draft';
  END IF;
END;
$$;
INSERT INTO resource_eligibility_publication (
  id, resource_id, content_version, policy_version, locale, effective_at,
  expires_at, published_by, published_at
)
SELECT
  publication_id, resource_id, 0, 'content-eligibility-v1', 'vi-VN',
  TIMESTAMPTZ '2026-09-23 00:00:00+00', NULL,
  '00000000-0000-4000-8000-000000000001'::uuid,
  TIMESTAMPTZ '2026-09-23 00:00:00+00'
FROM (
  VALUES
    ('00000000-0000-4000-9000-000000000201'::uuid, '00000000-0000-4000-8000-000000000201'::uuid),
    ('00000000-0000-4000-9000-000000000202'::uuid, '00000000-0000-4000-8000-000000000202'::uuid),
    ('00000000-0000-4000-9000-000000000203'::uuid, '00000000-0000-4000-8000-000000000203'::uuid),
    ('00000000-0000-4000-9000-000000000204'::uuid, '00000000-0000-4000-8000-000000000204'::uuid),
    ('00000000-0000-4000-9000-000000000205'::uuid, '00000000-0000-4000-8000-000000000205'::uuid),
    ('00000000-0000-4000-9000-000000000206'::uuid, '00000000-0000-4000-8000-000000000206'::uuid),
    ('00000000-0000-4000-9000-000000000207'::uuid, '00000000-0000-4000-8000-000000000207'::uuid),
    ('00000000-0000-4000-9000-000000000208'::uuid, '00000000-0000-4000-8000-000000000208'::uuid),
    ('00000000-0000-4000-9000-000000000209'::uuid, '00000000-0000-4000-8000-000000000209'::uuid),
    ('00000000-0000-4000-9000-000000000210'::uuid, '00000000-0000-4000-8000-000000000210'::uuid),
    ('00000000-0000-4000-9000-000000000211'::uuid, '00000000-0000-4000-8000-000000000211'::uuid),
    ('00000000-0000-4000-9000-000000000212'::uuid, '00000000-0000-4000-8000-000000000212'::uuid),
    ('00000000-0000-4000-9000-000000000213'::uuid, '00000000-0000-4000-8000-000000000213'::uuid),
    ('00000000-0000-4000-9000-000000000214'::uuid, '00000000-0000-4000-8000-000000000214'::uuid),
    ('00000000-0000-4000-9000-000000000215'::uuid, '00000000-0000-4000-8000-000000000215'::uuid)
) AS reviewed(publication_id, resource_id)
ON CONFLICT (resource_id, content_version, policy_version) DO NOTHING;

INSERT INTO resource_eligibility_declaration (
  publication_id, target_domain, eligibility_role, instrument,
  screening_levels, support_tiers
)
VALUES
  ('00000000-0000-4000-9000-000000000201', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000201', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000202', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000202', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000203', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000203', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000204', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000204', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000205', 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000206', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000207', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000207', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000208', 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000208', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000209', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000209', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000210', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000210', 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000211', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000212', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000212', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MODERATE','SEVERE'], ARRAY['PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000213', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000213', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000214', 'ANXIETY_SYMPTOMS', 'PRIMARY', 'GAD_7', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000214', 'DEPRESSIVE_SYMPTOMS', 'PRIMARY', 'PHQ_9', ARRAY['MINIMAL','MILD'], ARRAY['SELF_GUIDED_SUPPORT']),
  ('00000000-0000-4000-9000-000000000215', 'ANXIETY_SYMPTOMS', 'ADJUNCT', 'GAD_7', ARRAY['MINIMAL','MILD','MODERATE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED']),
  ('00000000-0000-4000-9000-000000000215', 'DEPRESSIVE_SYMPTOMS', 'ADJUNCT', 'PHQ_9', ARRAY['MINIMAL','MILD','MODERATE','MODERATELY_SEVERE','SEVERE'], ARRAY['SELF_GUIDED_SUPPORT','PROFESSIONAL_SUPPORT_RECOMMENDED','SAFETY_FOLLOW_UP_RECOMMENDED'])
ON CONFLICT (publication_id, target_domain, instrument) DO NOTHING;

DO $$
BEGIN
  IF (SELECT count(*) FROM resource_eligibility_publication
      WHERE resource_id BETWEEN '00000000-0000-4000-8000-000000000201'::uuid
                            AND '00000000-0000-4000-8000-000000000215'::uuid
        AND content_version = 0
        AND policy_version = 'content-eligibility-v1') <> 15 THEN
    RAISE EXCEPTION 'MB-556 eligibility publications differ from the reviewed draft';
  END IF;

  IF (SELECT count(*) FROM resource_eligibility_declaration
      WHERE publication_id BETWEEN '00000000-0000-4000-9000-000000000201'::uuid
                               AND '00000000-0000-4000-9000-000000000215'::uuid) <> 27 THEN
    RAISE EXCEPTION 'MB-556 eligibility declarations differ from the reviewed draft';
  END IF;

  IF (SELECT count(*)
      FROM resource_eligibility_publication p
      JOIN resource_eligibility_declaration d ON d.publication_id = p.id
      WHERE p.resource_id IN (
        '00000000-0000-4000-8000-000000000201'::uuid,
        '00000000-0000-4000-8000-000000000205'::uuid,
        '00000000-0000-4000-8000-000000000206'::uuid,
        '00000000-0000-4000-8000-000000000208'::uuid
      )
        AND d.eligibility_role = 'PRIMARY'
        AND d.screening_levels @> ARRAY['MODERATE']::text[]
        AND d.support_tiers @> ARRAY['PROFESSIONAL_SUPPORT_RECOMMENDED']::text[]) <> 4 THEN
    RAISE EXCEPTION 'MB-556 SupportGuide replacements must preserve moderate PRIMARY coverage';
  END IF;
END;
$$;
