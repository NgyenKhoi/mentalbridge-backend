package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;

class SupportPlanCycleContractTests {

	@Test
	void cycleIsOptionalNullableAndCarriesExplicitContinuationNumber() {
		var api = contract();
		Schema<?> plan = api.getComponents().getSchemas().get("SupportPlanV3");
		assertThat(plan.getRequired()).doesNotContain("cycle");
		assertThat(plan.getProperties().get("cycle").getOneOf()).hasSize(2);
		Schema<?> cycle = api.getComponents().getSchemas().get("SupportPlanCycle");
		assertThat(cycle.getRequired()).containsExactlyInAnyOrder("policyVersion", "lengthDays", "timezone",
				"cycleNumber", "startedOn", "reviewDueOn", "currentDay", "pausedDays", "state");
		assertThat(cycle.getProperties().get("policyVersion").getConst()).isEqualTo("support-plan-cycle-v1");
		assertThat(String.valueOf(cycle.getProperties().get("lengthDays").getConst())).isEqualTo("14");
		assertThat(cycle.getProperties().get("cycleNumber").getMinimum()).isEqualByComparingTo("1");
		assertThat(cycle.getProperties().get("pausedDays").getMinimum()).isEqualByComparingTo("0");
	}

	@Test
	void continuationRequiresOwnerAuthenticationConcurrencyAndRetryIdentity() {
		var operation = contract().getPaths().get("/api/v2/support-plans/{supportPlanId}/cycle-continuation").getPost();
		assertThat(operation.getSecurity()).anySatisfy(value -> assertThat(value).containsKey("bearerAuth"));
		assertThat(operation.getParameters()).extracting(value -> value.get$ref())
				.contains("#/components/parameters/SupportPlanId", "#/components/parameters/RequiredIfMatch",
						"#/components/parameters/IdempotencyKey");
		assertThat(operation.getRequestBody()).isNull();
		assertThat(operation.getResponses()).containsKeys("200", "400", "401", "403", "404", "409", "412");
		assertThat(operation.getResponses().get("200").getHeaders()).containsKey("ETag");
	}

	@Test
	void nativeHelpfulnessKeepsAuthoritativeCompletionAndLegacyRequestShape() {
		var api = contract();
		var path = api.getPaths().get("/api/v2/support-plan-occurrences/{occurrenceId}/engagement");
		assertThat(path.getPut().getRequestBody().getContent().get("application/json").getSchema().get$ref())
				.isEqualTo("#/components/schemas/ReplaceSupportPlanOccurrenceEngagementRequest");
		assertThat(path.getDelete().getRequestBody()).isNull();
		assertThat(api.getComponents().getSchemas().get("ActionOccurrence").getRequired())
				.doesNotContain("hidden", "helpfulness", "barrierCode", "reflection", "summaryReuseApproved", "engagementUpdatedAt");
		assertThat(api.getComponents().getSchemas().get("ActionOccurrence").getProperties())
				.containsKeys("hidden", "helpfulness", "barrierCode", "reflection", "summaryReuseApproved", "engagementUpdatedAt");
		assertThat(api.getComponents().getResponses().get("SupportPlanOccurrenceConflictProblem").getDescription())
				.contains("AUTHORITATIVE_COMPLETION_REQUIRED");
	}

	private OpenAPI contract() {
		var path = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var parsed = new OpenAPIV3Parser().readLocation(path.toUri().toString(), null, null);
		assertThat(parsed.getMessages()).isEmpty();
		return parsed.getOpenAPI();
	}
}
