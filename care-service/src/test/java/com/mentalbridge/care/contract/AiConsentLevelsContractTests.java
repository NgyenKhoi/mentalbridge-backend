package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.consent.AiContextProcessingDisclosureService;
import com.mentalbridge.care.consent.ConsentService;

import io.swagger.v3.core.util.Yaml;

class AiConsentLevelsContractTests {
	private final ObjectMapper json = new ObjectMapper().findAndRegisterModules()
			.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
	private final JsonNode contract;

	AiConsentLevelsContractTests() throws Exception {
		contract = Yaml.mapper().readTree(Files.readString(Path.of("..", "contracts", "openapi", "care-service-v1.yaml")));
	}

	@Test
	void namedNoticeDecisionSnapshotAndProblemExamplesValidateAgainstTheirActualSchemas() {
		assertExamples("/api/v1/consents/ai-authorizations", "get", "200", "application/json", "AiConsentAuthorizations");
		assertExamples("/api/v1/consent-decisions", "post", "201", "application/json", "ConsentDecision");
		assertExamples("/api/v1/ai-context-processing-disclosures/current", "get", "200", "application/json", "AiContextProcessingDisclosure");
		var requestExamples = contract.path("paths").path("/api/v1/consent-decisions").path("post")
				.path("requestBody").path("content").path("application/json").path("examples");
		assertThat(requestExamples.properties()).hasSize(4);
		requestExamples.forEach(example -> assertValid("ConsentDecisionRequest", example.path("value")));
		var errors = contract.path("components").path("responses").path("ConsentConflictProblem")
				.path("content").path("application/problem+json").path("examples");
		assertThat(errors.has("l2RequiresL1")).isTrue();
		errors.forEach(example -> assertValid("Problem", example.path("value")));
	}

	@Test
	void javaSnapshotAndNoticeBindToTheSameClosedContractAsTheNodeConsumer() {
		var now = Instant.parse("2026-11-06T00:01:00Z");
		var snapshot = new ConsentService.AiConsentAuthorizations(
				new ConsentService.AiAuthorizationView(true, "GRANTED", "AI_PROCESSING", "ai-processing-capstone-v2", now),
				new ConsentService.AiAuthorizationView(false, "MISSING", "AI_CONTEXT_PROCESSING", null, null), now);
		assertValid("AiConsentAuthorizations", json.valueToTree(snapshot));
		var notice = json.<JsonNode>valueToTree(new AiContextProcessingDisclosureService().current("vi-VN"));
		assertValid("AiContextProcessingDisclosure", notice);
		var example = contract.path("paths").path("/api/v1/ai-context-processing-disclosures/current").path("get")
				.path("responses").path("200").path("content").path("application/json").path("examples")
				.path("currentVietnameseNotice").path("value");
		assertThat(notice).isEqualTo(example);
		var schema = contract.path("components").path("schemas");
		assertThat(schema.path("ConsentType").path("enum")).isEqualTo(json.valueToTree(java.util.List.of("PRIVACY_POLICY", "AI_PROCESSING", "AI_CONTEXT_PROCESSING")));
		assertThat(schema.path("ConsentCollection").path("properties").path("decisions").path("maxItems").asInt()).isEqualTo(3);
		assertThat(schema.path("AiProcessingAuthorization").path("properties").path("consentType").path("const").asText()).isEqualTo("AI_PROCESSING");
		assertThat(schema.path("AiProcessingDisclosure").path("properties").path("version").path("const").asText()).isEqualTo("ai-processing-capstone-v2");
		assertThat(schema.path("AiContextAuthorization").path("properties").path("reason").path("enum").toString()).contains("DEPENDENCY_REQUIRED");
		assertValid("ConsentCollection", json.valueToTree(Map.of("decisions", java.util.List.of())));
	}

	private void assertExamples(String path, String method, String status, String mediaType, String schema) {
		var examples = contract.path("paths").path(path).path(method).path("responses").path(status)
				.path("content").path(mediaType).path("examples");
		assertThat(examples.isEmpty()).isFalse();
		examples.forEach(example -> assertValid(schema, example.path("value")));
	}

	private void assertValid(String name, JsonNode value) {
		var schema = json.createObjectNode().put("$ref", "#/components/schemas/" + name);
		schema.set("components", contract.path("components"));
		var validator = com.networknt.schema.SchemaRegistry.withDialect(com.networknt.schema.dialect.Dialects.getDraft202012())
				.getSchema(schema.toString(), com.networknt.schema.InputFormat.JSON);
		assertThat(validator.validate(value)).as("%s example %s", name, value).isEmpty();
	}
}
