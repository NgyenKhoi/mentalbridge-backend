package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.swagger.v3.parser.OpenAPIV3Parser;

class SupportGuideContractTests {

	@Test
	void contractExposesImmutableGuideHistoryWithoutPlanLifecycleOrRawAnswers() {
		var contract = Path.of("..", "contracts", "openapi", "care-support-guide-v1.yaml").toAbsolutePath();
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null);

		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI().getPaths().keySet()).containsExactlyInAnyOrder(
				"/api/v1/support-guides", "/api/v1/support-guides/{supportGuideId}",
				"/api/v1/screening-episodes/{episodeId}/support-guide");
		var guide = result.getOpenAPI().getComponents().getSchemas().get("SupportGuide");
		assertThat(guide.getProperties()).containsKeys("guideType", "explanation", "safety", "resourceResolution",
				"resources", "provenance", "phrasing")
				.doesNotContainKeys("status", "activatedAt", "activities", "answers", "totalScore", "diagnosis");
		var create = result.getOpenAPI().getPaths().get("/api/v1/support-guides").getPost();
		assertThat(create.getSecurity()).isNotEmpty();
		assertThat(create.getParameters()).extracting(parameter -> parameter.get$ref())
				.contains("#/components/parameters/IdempotencyKey");
		assertThat(result.getOpenAPI().getComponents().getParameters().get("IdempotencyKey").getName())
				.isEqualTo("Idempotency-Key");
		var ensure = result.getOpenAPI().getPaths().get("/api/v1/screening-episodes/{episodeId}/support-guide")
				.getPost();
		assertThat(ensure.getSecurity()).isNotEmpty();
		assertThat(ensure.getRequestBody()).isNull();
		assertThat(ensure.getParameters()).extracting(parameter -> parameter.getName())
				.containsExactly("episodeId", "X-Correlation-Id");
		assertThat(ensure.getResponses().get("200").getContent().get("application/json").getSchema().get$ref())
				.isEqualTo("#/components/schemas/SupportGuide");
	}
}
