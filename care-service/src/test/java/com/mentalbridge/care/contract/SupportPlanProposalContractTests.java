package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

class SupportPlanProposalContractTests {

	private static final Set<String> REQUIRED_OPERATIONS = Set.of(
			"POST /api/v1/support-plans",
			"GET /api/v1/support-plans/{supportPlanId}",
			"DELETE /api/v1/support-plans/{supportPlanId}",
			"PUT /api/v1/support-plans/{supportPlanId}/choices",
			"POST /api/v1/support-plans/{supportPlanId}/activate",
			"POST /api/v1/support-plans/{supportPlanId}/pause",
			"POST /api/v1/support-plans/{supportPlanId}/resume",
			"POST /api/v1/support-plans/{supportPlanId}/complete",
			"POST /api/v1/support-plans/{supportPlanId}/replace");

	@Test
	void proposalIsValidPlannedAuthenticatedAndExplicitAboutRetryBoundaries() {
		SwaggerParseResult result = parseProposal();
		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI()).isNotNull();

		Set<String> operations = new HashSet<>();
		result.getOpenAPI().getPaths().forEach((path, pathItem) -> {
			assertThat(pathItem.getExtensions())
					.as("proposal status for %s", path)
					.containsEntry("x-mentalbridge-status", "planned");
			pathItem.readOperationsMap().forEach((method, operation) -> {
				String key = method.name() + " " + path;
				operations.add(key);
				assertBearerSecurity(key, operation);
				assertThat(operation.getResponses()).as("boundary errors for %s", key)
						.containsKeys("400", "401", "403", "404");
				if (!key.startsWith("GET ")) {
					assertParameter(key, operation, "Idempotency-Key");
				}
			});
		});

		assertThat(operations).isEqualTo(REQUIRED_OPERATIONS);
	}

	@Test
	void initialProposalRequestContainsOnlyTheOwnedEvaluationReference() {
		OpenAPI openApi = parseProposal().getOpenAPI();
		Schema<?> request = openApi.getComponents().getSchemas().get("ProposeSupportPlanRequest");

		assertThat(request.getRequired()).containsExactly("sourceSupportEvaluationId");
		assertThat(request.getProperties()).containsOnlyKeys("sourceSupportEvaluationId")
				.doesNotContainKeys("userId", "resourceVersions", "supportTier", "screeningLevel",
						"targetDomain", "templateFamily", "eligibility", "safetyAcknowledgement");
	}

	@Test
	void responseFreezesPolicyFamiliesSlotsRolesAndResourceBounds() {
		OpenAPI openApi = parseProposal().getOpenAPI();
		Schema<?> plan = openApi.getComponents().getSchemas().get("SupportPlan");
		Schema<?> family = openApi.getComponents().getSchemas().get("TemplateFamilyEvidence");
		Schema<?> slot = openApi.getComponents().getSchemas().get("SupportPlanSlot");
		Schema<?> admittedResource = openApi.getComponents().getSchemas().get("AdmittedResourceVersion");
		Schema<?> slots = (Schema<?>) plan.getProperties().get("slots");
		Schema<?> selectedResourceCount = (Schema<?>) plan.getProperties().get("selectedResourceCount");

		assertThat(plan.getProperties()).containsKeys("sourceSupportEvaluation", "selectionPolicyVersion",
				"templateFamilies", "slots", "selectedResourceCount")
				.doesNotContainKeys("userId", "assessmentAnswers", "diagnosis", "treatment", "recovered",
						"safetyAcknowledgement");
		assertThat(plan.getProperties().get("selectionPolicyVersion").getConst())
				.isEqualTo("mb-support-plan-selection-v1");
		assertThat(slots.getMinItems()).isEqualTo(1);
		assertThat(slots.getMaxItems()).isEqualTo(5);
		assertThat(selectedResourceCount.getMinimum()).isEqualByComparingTo("1");
		assertThat(selectedResourceCount.getMaximum()).isEqualByComparingTo("5");

		assertThat(family.getProperties().get("family").getEnum()).containsExactly(
				"DEPRESSIVE_MAINTENANCE",
				"DEPRESSIVE_SELF_GUIDED",
				"DEPRESSIVE_PROFESSIONAL_ADJUNCT",
				"ANXIETY_MAINTENANCE",
				"ANXIETY_SELF_GUIDED",
				"ANXIETY_PROFESSIONAL_ADJUNCT")
				.doesNotContain("SAFETY_OVERLAY");
		assertThat(slot.getProperties().get("kind").getEnum()).containsExactly("CORE", "OPTIONAL");
		assertThat(admittedResource.getProperties().get("role").getEnum()).containsExactly("PRIMARY", "ADJUNCT");
	}

	@Test
	void lifecycleUsesOneNormalActivationAndAtomicExplicitReplacement() {
		OpenAPI openApi = parseProposal().getOpenAPI();
		Schema<?> statuses = openApi.getComponents().getSchemas().get("SupportPlanStatus");
		Operation activation = openApi.getPaths().get("/api/v1/support-plans/{supportPlanId}/activate").getPost();
		Operation replacement = openApi.getPaths().get("/api/v1/support-plans/{supportPlanId}/replace").getPost();

		assertThat(statuses.getEnum().stream().map(String::valueOf).toList())
				.containsExactly("DRAFT", "ACTIVE", "PAUSED", "COMPLETED", "SUPERSEDED");
		assertThat(activation.getRequestBody()).isNull();
		assertThat(activation.getDescription()).contains("no safety acknowledgement field");
		assertThat(replacement.getDescription()).contains("atomically changes the source")
				.contains("new evaluation never");
		assertThat(replacement.getResponses()).containsKeys("409", "412", "503");
	}

	private SwaggerParseResult parseProposal() {
		Path contract = Path.of("..", "contracts", "proposals", "care-support-plan-v1.yaml").toAbsolutePath();
		ParseOptions options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		return new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);
	}

	private void assertBearerSecurity(String key, Operation operation) {
		assertThat(operation.getSecurity())
				.as("bearer security for %s", key)
				.anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
	}

	private void assertParameter(String key, Operation operation, String name) {
		assertThat(operation.getParameters())
				.as("%s requirement for %s", name, key)
				.anySatisfy(parameter -> assertThat(parameter.getName()).isEqualTo(name));
	}
}
