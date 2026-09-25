package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class CareOpenApiContractTests {

	private static final Set<String> ALL_OPERATIONS = Set.of(
			"GET /api/v1/profile",
			"PUT /api/v1/profile",
			"GET /api/v1/consents",
			"GET /api/v1/consents/ai-processing/authorization",
			"POST /api/v1/consent-decisions",
			"GET /api/v1/privacy-disclosures/current",
			"GET /api/v1/ai-processing-disclosures/current",
			"GET /api/v1/questionnaires/{instrument}/current",
			"GET /api/v1/questionnaires/definitions/{definitionId}",
			"GET /api/v1/assessments",
			"POST /api/v1/assessments",
			"GET /api/v1/assessments/{assessmentId}",
			"GET /api/v1/assessments/{assessmentId}/progress",
			"POST /api/v1/screening-episodes",
			"GET /api/v1/screening-episodes/current",
			"POST /api/v1/screening-episodes/{episodeId}/assessments/{instrument}",
			"POST /api/v1/screening-episodes/{episodeId}/support-evaluation",
			"POST /api/v1/reassessment-self-reports",
			"GET /api/v1/reassessment-self-reports/current",
			"PUT /api/v1/reassessment-self-reports/{selfReportId}",
			"DELETE /api/v1/reassessment-self-reports/{selfReportId}",
			"GET /api/v1/reassessment-summaries",
			"POST /api/v1/reassessment-summaries",
			"GET /api/v1/reassessment-summaries/context",
			"GET /api/v1/reassessment-summaries/current",
			"GET /api/v1/reassessment-summaries/{summaryId}",
			"GET /api/v1/support-evaluations",
			"POST /api/v1/support-evaluations",
			"GET /api/v1/support-evaluations/{supportEvaluationId}",
			"POST /api/v1/support-plans",
			"GET /api/v1/support-plans/current-draft",
			"GET /api/v1/support-plans/current",
			"GET /api/v1/support-plans/history",
			"GET /api/v1/support-plans/{supportPlanId}",
			"PUT /api/v1/support-plans/{supportPlanId}/choices",
			"POST /api/v1/support-plans/{supportPlanId}/activate",
			"PUT /api/v1/support-plans/{supportPlanId}/status",
			"POST /api/v1/support-plans/{supportPlanId}/replace",
			"POST /api/v1/support-plans/{supportPlanId}/replacement-review",
			"GET /api/v1/support-plan-occurrences",
			"GET /api/v1/support-plan-occurrences/{occurrenceId}",
			"PUT /api/v1/support-plan-occurrences/{occurrenceId}/state",
			"PUT /api/v1/support-plan-occurrences/{occurrenceId}/engagement",
			"DELETE /api/v1/support-plan-occurrences/{occurrenceId}/engagement",
			"POST /api/v1/anonymous-assessment-sessions",
			"POST /api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
			"GET /api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}",
			"POST /api/v1/safety-directory-lookups");

	private static final Set<String> IMPLEMENTED_PATHS = Set.of(
			"/api/v1/profile",
			"/api/v1/consents",
			"/api/v1/consents/ai-processing/authorization",
			"/api/v1/consent-decisions",
			"/api/v1/privacy-disclosures/current",
			"/api/v1/ai-processing-disclosures/current",
			"/api/v1/questionnaires/{instrument}/current",
			"/api/v1/questionnaires/definitions/{definitionId}",
			"/api/v1/assessments",
			"/api/v1/assessments/{assessmentId}",
			"/api/v1/assessments/{assessmentId}/progress",
			"/api/v1/screening-episodes",
			"/api/v1/screening-episodes/current",
			"/api/v1/screening-episodes/{episodeId}/assessments/{instrument}",
			"/api/v1/screening-episodes/{episodeId}/support-evaluation",
			"/api/v1/reassessment-self-reports",
			"/api/v1/reassessment-self-reports/current",
			"/api/v1/reassessment-self-reports/{selfReportId}",
			"/api/v1/reassessment-summaries",
			"/api/v1/reassessment-summaries/context",
			"/api/v1/reassessment-summaries/current",
			"/api/v1/reassessment-summaries/{summaryId}",
			"/api/v1/support-evaluations",
			"/api/v1/support-evaluations/{supportEvaluationId}",
			"/api/v1/support-plans",
			"/api/v1/support-plans/current-draft",
			"/api/v1/support-plans/current",
			"/api/v1/support-plans/history",
			"/api/v1/support-plans/{supportPlanId}",
			"/api/v1/support-plans/{supportPlanId}/choices",
			"/api/v1/support-plans/{supportPlanId}/activate",
			"/api/v1/support-plans/{supportPlanId}/status",
			"/api/v1/support-plans/{supportPlanId}/replace",
			"/api/v1/support-plans/{supportPlanId}/replacement-review",
			"/api/v1/support-plan-occurrences",
			"/api/v1/support-plan-occurrences/{occurrenceId}",
			"/api/v1/support-plan-occurrences/{occurrenceId}/state",
			"/api/v1/support-plan-occurrences/{occurrenceId}/engagement",
			"/api/v1/anonymous-assessment-sessions",
			"/api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
			"/api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}",
			"/api/v1/safety-directory-lookups");

	private static final Set<String> BEARER_OPERATIONS = Set.of(
			"GET /api/v1/profile",
			"PUT /api/v1/profile",
			"GET /api/v1/consents",
			"GET /api/v1/consents/ai-processing/authorization",
			"POST /api/v1/consent-decisions",
			"GET /api/v1/assessments",
			"POST /api/v1/assessments",
			"GET /api/v1/assessments/{assessmentId}",
			"GET /api/v1/assessments/{assessmentId}/progress",
			"POST /api/v1/screening-episodes",
			"GET /api/v1/screening-episodes/current",
			"POST /api/v1/screening-episodes/{episodeId}/assessments/{instrument}",
			"POST /api/v1/screening-episodes/{episodeId}/support-evaluation",
			"POST /api/v1/reassessment-self-reports",
			"GET /api/v1/reassessment-self-reports/current",
			"PUT /api/v1/reassessment-self-reports/{selfReportId}",
			"DELETE /api/v1/reassessment-self-reports/{selfReportId}",
			"GET /api/v1/reassessment-summaries",
			"POST /api/v1/reassessment-summaries",
			"GET /api/v1/reassessment-summaries/context",
			"GET /api/v1/reassessment-summaries/current",
			"GET /api/v1/reassessment-summaries/{summaryId}",
			"GET /api/v1/support-evaluations",
			"POST /api/v1/support-evaluations",
			"GET /api/v1/support-evaluations/{supportEvaluationId}",
			"POST /api/v1/support-plans",
			"GET /api/v1/support-plans/current-draft",
			"GET /api/v1/support-plans/current",
			"GET /api/v1/support-plans/history",
			"GET /api/v1/support-plans/{supportPlanId}",
			"PUT /api/v1/support-plans/{supportPlanId}/choices",
			"POST /api/v1/support-plans/{supportPlanId}/activate",
			"PUT /api/v1/support-plans/{supportPlanId}/status",
			"POST /api/v1/support-plans/{supportPlanId}/replace",
			"POST /api/v1/support-plans/{supportPlanId}/replacement-review",
			"GET /api/v1/support-plan-occurrences",
			"GET /api/v1/support-plan-occurrences/{occurrenceId}",
			"PUT /api/v1/support-plan-occurrences/{occurrenceId}/state",
			"PUT /api/v1/support-plan-occurrences/{occurrenceId}/engagement",
			"DELETE /api/v1/support-plan-occurrences/{occurrenceId}/engagement");

	private static final Set<String> ANONYMOUS_TOKEN_OPERATIONS = Set.of(
			"POST /api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
			"GET /api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}");

	private static final Set<String> IDEMPOTENT_OPERATIONS = Set.of(
			"POST /api/v1/consent-decisions",
			"POST /api/v1/assessments",
			"POST /api/v1/screening-episodes/{episodeId}/assessments/{instrument}",
			"POST /api/v1/reassessment-self-reports",
			"POST /api/v1/reassessment-summaries",
			"POST /api/v1/support-evaluations",
			"POST /api/v1/support-plans",
			"POST /api/v1/support-plans/{supportPlanId}/activate",
			"POST /api/v1/support-plans/{supportPlanId}/replace",
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
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
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
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
		var progress = openApi.getComponents().getSchemas().get("AssessmentProgress");
		var point = openApi.getComponents().getSchemas().get("AssessmentProgressPoint");
		var transition = openApi.getComponents().getSchemas().get("BandTransition");
		var direction = openApi.getComponents().getSchemas().get("ScoreDirection");

		assertThat(allowsAdditionalProperties(progress)).isTrue();
		assertThat(allowsAdditionalProperties(point)).isTrue();
		assertThat(allowsAdditionalProperties(transition)).isTrue();
		assertThat(progress.getProperties()).containsKeys("instrument", "scoringVersion", "previous", "current",
				"rawDelta", "scoreDirection", "bandTransition", "elapsedDuration")
				.doesNotContainKeys("answers", "safetyStatus", "consent", "profile");
		assertThat(direction.getDescription()).contains("unavailable fallback", "must not infer clinical meaning");
	}

	@Test
	void supportContractProvidesReviewedExamplesForEveryTierWithoutACompositeScore() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
		var response = openApi.getComponents().getSchemas().get("SupportEvaluation");
		var examples = openApi.getComponents().getExamples();

		assertThat(response.getProperties()).containsKeys("supportTier", "reasonCodes", "evidence", "nextStep",
				"safetyGuidance", "disclaimer").doesNotContainKeys("totalScore", "compositeScore", "overallSeverity");
		assertThat(examples).containsKeys("SelfGuidedSupportEvaluation", "ProfessionalSupportEvaluation",
				"SafetyFollowUpSupportEvaluation");
		assertThat(Set.of("SelfGuidedSupportEvaluation", "ProfessionalSupportEvaluation",
				"SafetyFollowUpSupportEvaluation").stream().map(examples::get))
				.extracting(example -> supportTier(example.getValue()))
				.containsExactlyInAnyOrder("SELF_GUIDED_SUPPORT", "PROFESSIONAL_SUPPORT_RECOMMENDED",
						"SAFETY_FOLLOW_UP_RECOMMENDED");
	}

	@Test
	void reassessmentContractKeepsFourSourcedDimensionsAndAContradictoryExample() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
		var request = openApi.getComponents().getSchemas().get("ReassessmentSummaryCreateRequest");
		var response = openApi.getComponents().getSchemas().get("ReassessmentSummary");
		var journal = openApi.getComponents().getSchemas().get("ReassessmentJournalDimension");
		var example = openApi.getComponents().getExamples().get("ContradictoryReassessmentSummary");

		assertThat(request.getRequired()).containsExactlyInAnyOrder("phq9AssessmentId", "gad7AssessmentId",
				"previousPeriod", "currentPeriod");
		assertThat(request.getOneOf()).hasSize(2);
		assertThat(request.getProperties()).containsKeys("journalAnalysisId", "journalJobId", "selfReportId");
		assertThat(response.getProperties()).containsKeys("screening", "journalContext", "supportPlanEngagement",
				"selfReportedExperience", "activityReflection", "userReflection", "disclaimerCode")
				.doesNotContainKeys("combinedScore", "improvementScore",
						"recoveryPercentage", "overallDirection");
		assertThat(journal.getProperties()).containsKeys("state", "unavailableReason", "jobId", "analysisId", "dataCoverage",
				"sourceJournalRevisions", "provenance").doesNotContainKeys("journalText", "rawResponse");
		assertThat(String.valueOf(example.getValue())).contains("DECREASED", "MORE_FREQUENT", "MORE_DIFFICULT",
				"FOUR_DIMENSIONS_NOT_COMBINED");
	}

	@Test
	void supportPlanDraftAcceptsOnlyEvaluationReferenceAndReturnsPersistedGovernedEvidence() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
		var request = openApi.getComponents().getSchemas().get("ProposeSupportPlanDraftRequest");
		var draft = openApi.getComponents().getSchemas().get("SupportPlan");

		assertThat(request.getProperties()).containsOnlyKeys("sourceSupportEvaluationId")
				.doesNotContainKeys("packageCode", "resourceIds", "templateFamily", "safetyStatus", "aiOutput");
		assertThat(draft.getProperties()).containsKeys("source", "entitlement", "rationale", "safety",
				"templateFamilies", "slots", "selectedResourceCount", "disclaimer")
				.doesNotContainKeys("assessmentAnswers", "journalContent", "diagnosis", "treatment");
	}

	@Test
	void supportPlanEngagementIsAnExactReplacementWithoutClinicalClaims() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();
		var request = openApi.getComponents().getSchemas().get("ReplaceSupportPlanOccurrenceEngagementRequest");
		var occurrence = openApi.getComponents().getSchemas().get("SupportPlanOccurrence");

		assertThat(request.getRequired()).containsExactlyInAnyOrder(
				"state", "hidden", "helpfulness", "barrierCode", "reflection", "summaryReuseApproved");
		assertThat(request.getProperties()).containsOnlyKeys(
				"state", "hidden", "helpfulness", "barrierCode", "reflection", "summaryReuseApproved")
				.doesNotContainKeys("adherence", "clinicalOutcome", "recoveryScore", "specialistObserved");
		assertThat(occurrence.getProperties()).containsKeys("source", "version", "engagementUpdatedAt")
				.doesNotContainKeys("adherence", "clinicalOutcome", "recoveryScore");
	}

	private boolean allowsAdditionalProperties(Schema<?> schema) {
		var additionalProperties = schema.getAdditionalProperties();
		return Boolean.TRUE.equals(additionalProperties)
				|| additionalProperties instanceof Schema<?> additionalSchema
						&& Boolean.TRUE.equals(additionalSchema.getBooleanSchemaValue());
	}

	private String supportTier(Object example) {
		if (example instanceof Map<?, ?> map) {
			return String.valueOf(map.get("supportTier"));
		}
		if (example instanceof JsonNode node) {
			return node.path("supportTier").asText();
		}
		throw new IllegalArgumentException("Unsupported OpenAPI example representation");
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
