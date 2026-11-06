package com.mentalbridge.care.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.mentalbridge.care.shared.ApiException;

class AiProcessingDisclosureServiceTests {

	private final AiProcessingDisclosureService disclosures = new AiProcessingDisclosureService();

	@Test
	void publishesTheNarrowApprovedAiProcessingPurpose() {
		var disclosure = disclosures.current("vi-VN");

		assertThat(disclosure.consentType()).isEqualTo("AI_PROCESSING");
		assertThat(disclosure.version()).isEqualTo("ai-processing-capstone-v2");
		assertThat(disclosure.content()).contains("bản tóm tắt trước buổi tư vấn đã lưu",
				"AI chỉ tạo gợi ý để bạn xem lại và chỉnh sửa", "không tự phê duyệt hoặc chia sẻ với chuyên gia",
				"Nội dung nhật ký, câu trả lời sàng lọc, chat và ghi chú riêng không được dùng");
	}

	@Test
	void rejectsAnotherPolicyVersionAndLocale() {
		assertThatThrownBy(() -> disclosures.requireCurrent("privacy-capstone-v3"))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("AI_PROCESSING_DISCLOSURE_REQUIRED"));
		assertThatThrownBy(() -> disclosures.current("en-US"))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("AI_PROCESSING_DISCLOSURE_NOT_FOUND"));
	}

	@Test
	void contextNoticeIsSeparatelyVersionedAndStatesItsDependencyAndWithdrawal() {
		var context = new AiContextProcessingDisclosureService();
		var notice = context.current("vi-VN");
		assertThat(notice.consentType()).isEqualTo("AI_CONTEXT_PROCESSING");
		assertThat(notice.version()).isEqualTo("ai-context-processing-capstone-v1");
		assertThat(notice.title()).isEqualTo("AI dùng bối cảnh");
		assertThat(notice.content()).contains("các nguồn dữ liệu cá nhân đã được cho phép và bạn chọn",
				"chỉ có hiệu lực khi bạn đang đồng ý AI trò chuyện", "tạm dừng AI dùng bối cảnh",
				"có hiệu lực trở lại khi bạn đồng ý AI trò chuyện lại");
		assertThatThrownBy(() -> context.requireCurrent("ai-processing-capstone-v2"))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo("AI_CONTEXT_DISCLOSURE_REQUIRED"));
		assertThatThrownBy(() -> context.current("en-US"))
				.isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo("AI_CONTEXT_DISCLOSURE_NOT_FOUND"));
	}
}
