package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.swagger.v3.parser.OpenAPIV3Parser;

class VoiceConsentContractTests {
	@Test
	void promotesOnlyTheFourCarePrerequisitesWithoutChangingExistingConsentShape() {
		var options = new io.swagger.v3.parser.core.models.ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var api = new OpenAPIV3Parser().readLocation(Path.of("..", "contracts", "openapi", "care-service-v1.yaml")
				.toAbsolutePath().toUri().toString(), null, options).getOpenAPI();
		var schemas = api.getComponents().getSchemas();
		assertThat(schemas.get("ConsentType").getEnum()).containsExactly("PRIVACY_POLICY", "AI_PROCESSING", "AI_CONTEXT_PROCESSING");
		assertThat(((io.swagger.v3.oas.models.media.Schema<?>) schemas.get("ConsentCollection").getProperties().get("decisions")).getMaxItems()).isEqualTo(3);
		for (var path : List.of("/api/v1/ai-voice-processing-disclosure", "/api/v1/consents/ai-voice-processing",
				"/internal/v1/ai-companion/voice-consent-authorizations")) {
			var item = api.getPaths().get(path);
			assertThat(item.getExtensions()).containsEntry("x-mentalbridge-status", "implemented").containsEntry("x-mentalbridge-owner", "care-service");
			for (var operation : item.readOperations()) {
				assertThat(operation.getSecurity()).hasSize(1);
				for (var response : operation.getResponses().values()) assertThat(response.getHeaders()).containsKey("Cache-Control");
			}
		}
		assertThat(schemas.get("VoiceOwnerCheck").getProperties()).containsOnlyKeys("ownerId", "voiceSessionId", "purpose");
		assertThat(((io.swagger.v3.oas.models.media.Schema<?>) schemas.get("VoiceDisclosure").getProperties().get("consentVersion")).getConst())
				.isEqualTo("ai-voice-processing-capstone-v2");
		assertThat(((io.swagger.v3.oas.models.media.Schema<?>) schemas.get("VoiceDualConsentDecision").getProperties().get("voiceConsentVersion")).getConst())
				.isEqualTo("ai-voice-processing-capstone-v2");
		for (var schema : List.of("VoiceConsent", "VoiceConsentCommand")) {
			assertThat(((io.swagger.v3.oas.models.media.Schema<?>) schemas.get(schema).getProperties().get("consentVersion")).getEnum())
					.isEqualTo(List.of("ai-voice-processing-capstone-v1", "ai-voice-processing-capstone-v2"));
		}
		assertThat(schemas.get("VoiceProblem").getAdditionalProperties()).isEqualTo(false);
		assertThat(schemas.get("VoiceProblem").getProperties()).containsOnlyKeys("type", "title", "status", "code", "correlationId");
		assertThat(api.getPaths()).doesNotContainKeys("/api/v1/ai-companion/voice-sessions", "/api/v1/ai-companion/voice-eligibility");
	}
}
