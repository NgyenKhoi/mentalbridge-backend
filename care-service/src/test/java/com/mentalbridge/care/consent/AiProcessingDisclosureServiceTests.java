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
		assertThat(disclosure.version()).isEqualTo("ai-processing-capstone-v1");
		assertThat(disclosure.content()).contains("một phiên bản nhật ký cụ thể", "không bao gồm nghiên cứu",
				"không tự động xóa");
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
