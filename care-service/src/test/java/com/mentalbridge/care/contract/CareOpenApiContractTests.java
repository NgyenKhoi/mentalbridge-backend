package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class CareOpenApiContractTests {

	private static final Set<String> ALL_OPERATIONS = Set.of(
			"GET /api/v1/profile",
			"PUT /api/v1/profile",
			"GET /api/v1/consents",
			"POST /api/v1/consent-decisions",
			"GET /api/v1/privacy-disclosures/current",
			"GET /api/v1/questionnaires/{instrument}/current",
			"GET /api/v1/questionnaires/definitions/{definitionId}",
			"GET /api/v1/assessments",
			"POST /api/v1/assessments",
			"GET /api/v1/assessments/{assessmentId}",
			"GET /api/v1/assessments/{assessmentId}/progress",
			"POST /api/v1/support-evaluations",
			"GET /api/v1/support-evaluations/{supportEvaluationId}",
			"POST /api/v1/anonymous-assessment-sessions",
			"POST /api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
			"GET /api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}");

	private static final Set<String> IMPLEMENTED_PATHS = Set.of(
			"/api/v1/profile",
			"/api/v1/consents",
			"/api/v1/consent-decisions",
			"/api/v1/privacy-disclosures/current",
			"/api/v1/questionnaires/{instrument}/current",
			"/api/v1/questionnaires/definitions/{definitionId}",
			"/api/v1/assessments",
			"/api/v1/assessments/{assessmentId}",
			"/api/v1/assessments/{assessmentId}/progress",
			"/api/v1/support-evaluations",
			"/api/v1/support-evaluations/{supportEvaluationId}",
			"/api/v1/anonymous-assessment-sessions",
			"/api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
			"/api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}");

	private static final Set<String> BEARER_OPERATIONS = Set.of(
			"GET /api/v1/profile",
			"PUT /api/v1/profile",
			"GET /api/v1/consents",
			"POST /api/v1/consent-decisions",
			"GET /api/v1/assessments",
			"POST /api/v1/assessments",
			"GET /api/v1/assessments/{assessmentId}",
			"GET /api/v1/assessments/{assessmentId}/progress",
			"POST /api/v1/support-evaluations",
			"GET /api/v1/support-evaluations/{supportEvaluationId}");

	private static final Set<String> ANONYMOUS_TOKEN_OPERATIONS = Set.of(
			"POST /api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
			"GET /api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}");

	private static final Set<String> IDEMPOTENT_OPERATIONS = Set.of(
			"POST /api/v1/consent-decisions",
			"POST /api/v1/assessments",
			"POST /api/v1/support-evaluations",
			"POST /api/v1/anonymous-assessment-sessions/{sessionId}/assessments");

	@Test
	void careContractIsValidAndStatusesMatchRuntimeScope() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);

		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI()).isNotNull();
		var operations = new HashSet<String>();
		result.getOpenAPI().getPaths().forEach((path, pathItem) -> {
			assertThat(pathItem.getExtensions())
					.as("contract status for %s", path)
					.containsEntry("x-mentalbridge-status", IMPLEMENTED_PATHS.contains(path) ? "implemented" : "planned");
			pathItem.readOperationsMap().forEach((method, operation) -> {
				var key = method.name() + " " + path;
				operations.add(key);
				assertSecurity(key, operation);
				if (IDEMPOTENT_OPERATIONS.contains(key)) {
					assertThat(operation.getParameters())
							.as("idempotency requirement for %s", key)
							.anySatisfy(parameter -> assertThat(parameter.getName()).isEqualTo("Idempotency-Key"));
				}
			});
		});

		assertThat(operations).isEqualTo(ALL_OPERATIONS);
	}

	@Test
	void assessmentRequestAcceptsAnswersButNoClientOwnedResult() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().read(contract.toString());
		var request = openApi.getComponents().getSchemas().get("AssessmentSubmissionRequest");
		var result = openApi.getComponents().getSchemas().get("AssessmentResult");

		assertThat(request.getProperties()).containsKeys("questionnaireDefinitionId", "privacyPolicyVersion",
				"privacyDisclosureAcknowledged", "answers");
		assertThat(request.getProperties()).doesNotContainKeys(
				"totalScore", "screeningLevel", "scoringVersion", "safetyStatus", "safetyPolicyVersion");
		assertThat(result.getProperties()).containsKeys(
				"totalScore", "screeningLevel", "scoringVersion", "safetyStatus", "safetyPolicyVersion",
				"disclaimerCode");
		assertThat(result.getRequired()).contains("safetyStatus", "safetyPolicyVersion");
		var questionnaire = openApi.getComponents().getSchemas().get("Questionnaire");
		assertThat(questionnaire.getProperties()).containsKeys("scoringVersion", "scoreBands");
		assertThat(questionnaire.getRequired()).contains("scoringVersion", "scoreBands");
	}

	@Test
	void progressResponseIsAdditiveMinimizedAndDocumentsEnumFallback() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().read(contract.toString());
		var progress = openApi.getComponents().getSchemas().get("AssessmentProgress");
		var point = openApi.getComponents().getSchemas().get("AssessmentProgressPoint");
		var transition = openApi.getComponents().getSchemas().get("BandTransition");
		var direction = openApi.getComponents().getSchemas().get("ScoreDirection");

		assertThat(progress.getAdditionalProperties()).isEqualTo(Boolean.TRUE);
		assertThat(point.getAdditionalProperties()).isEqualTo(Boolean.TRUE);
		assertThat(transition.getAdditionalProperties()).isEqualTo(Boolean.TRUE);
		assertThat(progress.getProperties()).containsKeys("instrument", "scoringVersion", "previous", "current",
				"rawDelta", "scoreDirection", "bandTransition", "elapsedDuration")
				.doesNotContainKeys("answers", "safetyStatus", "consent", "profile");
		assertThat(direction.getDescription()).contains("unavailable fallback", "must not infer clinical meaning");
	}

	@Test
	void supportContractProvidesReviewedExamplesForEveryTierWithoutACompositeScore() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().read(contract.toString());
		var response = openApi.getComponents().getSchemas().get("SupportEvaluation");
		var examples = openApi.getComponents().getExamples();

		assertThat(response.getProperties()).containsKeys("supportTier", "reasonCodes", "evidence", "nextStep",
				"safetyGuidance", "disclaimer").doesNotContainKeys("totalScore", "compositeScore", "overallSeverity");
		assertThat(examples).containsKeys("SelfGuidedSupportEvaluation", "ProfessionalSupportEvaluation",
				"SafetyFollowUpSupportEvaluation");
		assertThat(examples.values()).extracting(
				example -> String.valueOf(((java.util.Map<?, ?>) example.getValue()).get("supportTier")))
				.containsExactlyInAnyOrder("SELF_GUIDED_SUPPORT", "PROFESSIONAL_SUPPORT_RECOMMENDED",
						"SAFETY_FOLLOW_UP_RECOMMENDED");
	}

	private void assertSecurity(String key, Operation operation) {
		if (BEARER_OPERATIONS.contains(key)) {
			assertThat(operation.getSecurity())
					.as("bearer security for %s", key)
					.anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
		}
		else if (ANONYMOUS_TOKEN_OPERATIONS.contains(key)) {
			assertThat(operation.getSecurity())
					.as("anonymous token security for %s", key)
					.anySatisfy(requirement -> assertThat(requirement).containsKey("anonymousSessionToken"));
		}
		else {
			assertThat(operation.getSecurity()).as("public operation %s", key).isNullOrEmpty();
		}
	}

}
