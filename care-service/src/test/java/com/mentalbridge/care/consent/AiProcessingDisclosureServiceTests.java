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
}
