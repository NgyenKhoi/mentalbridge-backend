package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SupportPlanV3ControllerCompanionTests {

	@Test
	void forwardsVerifiedSubjectBearerAndExactPathIdentity() {
		var plans = mock(SupportPlanV3Service.class);
		var schedules = mock(SupportPlanScheduleService.class);
		var companion = mock(SupportPlanContextualCompanionService.class);
		var controller = new SupportPlanV3Controller(plans, schedules, companion);
		var userId = UUID.randomUUID();
		var planId = UUID.randomUUID();
		var actionId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var jwt = Jwt.withTokenValue("end-user-token")
				.header("alg", "RS256")
				.subject(userId.toString())
				.issuedAt(Instant.parse("2026-10-14T00:00:00Z"))
				.expiresAt(Instant.parse("2026-10-14T01:00:00Z"))
				.build();
		var response = new SupportPlanContextualCompanionService.ContextualCompanionMessage(
				"support-plan-companion-message-v1", planId, actionId, "EXPLAIN_ACTION", "PLAN", "Giải thích.",
				"TEMPLATE", "support-plan-companion-policy-v1", "support-plan-companion-prompt-v1",
				"support-plan-companion-template-v1", null, null, "PROVIDER_DISABLED");
		when(companion.explain(eq(userId), eq(planId), eq(actionId), eq("PLAN"), eq("end-user-token"),
				eq(correlationId))).thenReturn(response);

		var result = controller.explainAction(jwt, planId, actionId, correlationId,
				new SupportPlanV3Controller.CompanionExplanationRequest("PLAN"));

		assertThat(result).isEqualTo(response);
		verify(companion).explain(userId, planId, actionId, "PLAN", "end-user-token", correlationId);
	}
}
