package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.mentalbridge.care.configuration.SupportPlanCompanionClientProperties;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.ActionContext;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionContext;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionRequest;
import com.mentalbridge.care.supportplan.SupportPlanCompanionClient.CompanionResponse;

@ExtendWith(MockitoExtension.class)
class SupportPlanCompanionClientTests {

	@Mock private JournalSupportPlanCompanionHttpClient http;

	@Test
	void missingServerCredentialUsesTheDeterministicTemplateWithoutCallingJournal() {
		var client = new SupportPlanCompanionClient(http, properties(""));
		var response = client.message(request(), "jwt", UUID.randomUUID());

		assertThat(response.delivery()).isEqualTo("TEMPLATE");
		assertThat(response.fallbackReason()).isEqualTo("PROVIDER_DISABLED");
		assertThat(response.text()).contains("Bài thở 4–6");
		verifyNoInteractions(http);
	}

	@Test
	void unavailableActionUsesTheApprovedUnavailableTemplate() {
		var client = new SupportPlanCompanionClient(http, properties(""));

		var response = client.message(request("UNAVAILABLE"), "jwt", UUID.randomUUID());

		assertThat(response.text()).isEqualTo("Thông tin cho “Bài thở 4–6” hiện chưa sẵn sàng. Bạn có thể thử lại sau.");
		verifyNoInteractions(http);
	}

	@Test
	void malformedOrWrongSurfaceResponseFailsClosedToTheTemplate() {
		var client = new SupportPlanCompanionClient(http, properties("12345678901234567890123456789012"));
		when(http.message(eq("Bearer jwt"), any(), any(), any())).thenReturn(new CompanionResponse(
				"support-plan-companion-message-v1", "EXPLAIN_ACTION", "PLAN", "Sai surface", "GENERATED", null,
				"support-plan-companion-policy-v1", "support-plan-companion-prompt-v1",
				"support-plan-companion-template-v1", "OPENAI", "model", null));

		var response = client.message(request(), "jwt", UUID.randomUUID());

		assertThat(response.delivery()).isEqualTo("TEMPLATE");
		assertThat(response.fallbackReason()).isEqualTo("MALFORMED_OUTPUT");
	}

	private CompanionRequest request() {
		return request("AVAILABLE");
	}

	private CompanionRequest request(String availability) {
		return new CompanionRequest("support-plan-companion-intent-v1", "support-plan-companion-policy-v1",
				"USER_INITIATED", "TODAY", "EXPLAIN_ACTION",
				new CompanionContext("support-plan-companion-context-v1", "vi-VN", "ACTION",
						new ActionContext("RESOURCE", "Bài thở 4–6", "Một bước nhỏ.", availability)));
	}

	private SupportPlanCompanionClientProperties properties(String token) {
		return new SupportPlanCompanionClientProperties("http://journal", token, Duration.ofMillis(500),
				Duration.ofSeconds(8), 10, 5, 50, Duration.ofSeconds(10), 2);
	}
}
