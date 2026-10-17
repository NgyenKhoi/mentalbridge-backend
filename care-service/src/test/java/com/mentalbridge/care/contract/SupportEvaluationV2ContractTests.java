package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class SupportEvaluationV2ContractTests {

	@Test
	void v2ContractIsValidOwnerScopedIdempotentAndDomainAware() {
		var contract = Path.of("..", "contracts", "openapi", "care-support-evaluation-v2.yaml").toAbsolutePath();
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);

		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI()).isNotNull();
		assertThat(result.getOpenAPI().getPaths().keySet()).containsExactlyInAnyOrder(
				"/api/v2/support-evaluations", "/api/v2/support-evaluations/{supportEvaluationId}");
		var create = result.getOpenAPI().getPaths().get("/api/v2/support-evaluations").getPost();
		assertThat(create.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
		assertThat(create.getParameters()).extracting(parameter -> parameter.getName())
				.contains("Idempotency-Key", "X-Correlation-Id");
		assertThat(create.getResponses().keySet()).contains("201", "400", "401", "403", "404", "409", "503");
	}

	@Test
	void responseKeepsDomainsAndSafetyIndependentWithoutGlobalSeverity() {
		var contract = Path.of("..", "contracts", "openapi", "care-support-evaluation-v2.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
		var evaluation = openApi.getComponents().getSchemas().get("SupportEvaluationV2");
		var contribution = openApi.getComponents().getSchemas().get("DomainContribution");
		var safety = openApi.getComponents().getSchemas().get("SafetyEvidence");

		assertThat(evaluation.getProperties()).containsKeys("evaluationVersion", "policyVersion", "evaluatedAt",
				"contributingDomains", "safetyEvidence")
				.doesNotContainKeys("supportTier", "overallSeverity", "mentalHealthLevel", "compositeScore", "totalScore");
		assertThat(contribution.getProperties()).containsKeys("assessmentId", "questionnaireDefinitionId", "instrument",
				"domain", "questionnaireVersion", "scoringVersion", "screeningLevel", "supportPathway", "reasonCodes")
				.doesNotContainKeys("answers", "totalScore", "safetyStatus");
		assertThat(safety.getProperties()).containsKeys("sourceAssessmentId", "instrument", "trigger", "status",
				"policyVersion", "reasonCode").doesNotContainKeys("answerValue", "item9Answer", "totalScore");
	}
}
