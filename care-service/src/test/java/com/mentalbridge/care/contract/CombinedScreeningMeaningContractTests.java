package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class CombinedScreeningMeaningContractTests {

	@Test
	void additiveOwnerReadHasNoRequestMutationOrNewGlobalClassification() {
		var path = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var parsed = new OpenAPIV3Parser().readLocation(path.toUri().toString(), null, options);
		assertThat(parsed.getMessages()).isEmpty();
		var api = parsed.getOpenAPI();
		var operation = api.getPaths().get("/api/v1/support-evaluations/{supportEvaluationId}/combined-meaning").getGet();
		assertThat(operation.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
		assertThat(operation.getRequestBody()).isNull();
		assertThat(operation.getParameters()).extracting(parameter -> parameter.getName())
				.containsExactly("supportEvaluationId", "X-Correlation-Id");
		assertThat(operation.getResponses()).containsOnlyKeys("200", "400", "401", "403", "404", "503");
		assertThat(operation.getDescription()).contains("worktree draft", "not approved", "Historical source facts");
		var schema = api.getComponents().getSchemas().get("CombinedScreeningMeaning");
		assertThat(schema.getProperties()).containsKeys("sourcePolicyVersion", "evaluatedAt", "presentationPolicyVersion",
				"contentVersion", "evidence", "supportTier", "baseMeaning", "presentationPriority", "nextStep", "safetyGuidance")
				.doesNotContainKeys("totalScore", "compositeScore", "overallSeverity", "mentalHealthLevel", "answers", "aiOutput");
		assertThat(((Schema<?>) schema.getProperties().get("contentVersion")).getConst())
				.isEqualTo("mb-combined-screening-meaning-vi-vn-v1-draft.3");
		assertThat(api.getComponents().getSchemas().get("SupportEvaluation").getProperties())
				.doesNotContainKeys("combinedMeaning", "baseMeaning", "presentationPriority", "presentationPolicyVersion");
		var v2 = new OpenAPIV3Parser().readLocation(Path.of("..", "contracts", "openapi",
				"care-support-evaluation-v2.yaml").toAbsolutePath().toUri().toString(), null, null).getOpenAPI();
		assertThat(v2.getComponents().getSchemas().get("SupportEvaluationV2").getProperties())
				.doesNotContainKeys("supportTier", "combinedMeaning", "overallSeverity", "compositeScore");
	}
}
