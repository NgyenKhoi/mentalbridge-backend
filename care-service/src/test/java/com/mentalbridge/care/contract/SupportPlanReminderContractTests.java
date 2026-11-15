package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;

class SupportPlanReminderContractTests {
	@Test
	void reminderEligibilityIsAServiceOnlyBooleanReadWithAStrictQuery() {
		var options = new io.swagger.v3.parser.core.models.ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var api = new OpenAPIV3Parser().readLocation(Path.of("..", "contracts", "openapi", "care-service-v1.yaml")
				.toAbsolutePath().toUri().toString(), null, options).getOpenAPI();
		var item = api.getPaths().get("/internal/v1/support-plan-reminder-eligibility");
		assertThat(item.getExtensions()).containsEntry("x-mentalbridge-status", "implemented")
				.containsEntry("x-mentalbridge-owner", "care-service");
		assertThat(item.readOperations()).hasSize(1);
		var operation = item.getPost();
		assertThat(operation.getOperationId()).isEqualTo("checkSupportPlanReminderEligibility");
		assertThat(operation.getSecurity()).singleElement()
				.satisfies(requirement -> assertThat(requirement).containsOnlyKeys("supportPlanReminderServiceToken"));
		var scheme = api.getComponents().getSecuritySchemes().get("supportPlanReminderServiceToken");
		assertThat(scheme.getName()).isEqualTo("X-MentalBridge-Service-Token");
		assertThat(operation.getResponses()).containsOnlyKeys("200", "400", "401");
		assertThat(operation.getResponses().get("200").getHeaders()).containsKey("Cache-Control");
		assertThat(operation.getResponses().get("401").getHeaders()).containsKey("Cache-Control");
		assertThat(operation.getResponses().get("401").getContent()).isNullOrEmpty();

		var query = (Schema<?>) api.getComponents().getSchemas().get("SupportPlanReminderEligibilityQuery");
		assertThat(query.getAdditionalProperties()).isEqualTo(false);
		assertThat(query.getRequired()).containsExactlyInAnyOrder("ownerAccountId", "asOf");
		assertThat(query.getProperties()).containsOnlyKeys("ownerAccountId", "asOf");
		var answer = (Schema<?>) api.getComponents().getSchemas().get("SupportPlanReminderEligibility");
		assertThat(answer.getAdditionalProperties()).isEqualTo(false);
		assertThat(answer.getRequired()).isEqualTo(List.of("eligible"));
		assertThat(answer.getProperties()).containsOnlyKeys("eligible");
	}
}
