package com.mentalbridge.community.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.community.notification.CommunityInteractionFact;
import com.mentalbridge.community.notification.CommunityInteractionFact.InteractionKind;
import com.mentalbridge.community.notification.CommunityInteractionFact.TargetType;
import org.junit.jupiter.api.Test;

class CommunityInteractionEventContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

	@Test
	void interactionSchemaFreezesTheMinimizedVersionOneBoundary() throws Exception {
		var path = Path.of("..", "contracts", "events", "community",
				"community-interaction-v1.schema.json").toAbsolutePath();
		var schema = objectMapper.readTree(path.toFile());
		var properties = schema.path("properties");

		assertThat(schema.path("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
		assertThat(schema.path("additionalProperties").asBoolean()).isFalse();
		assertThat(schema.path("required")).hasSize(9);
		assertThat(properties.propertyStream().map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
				"eventId", "schemaVersion", "actorCommunityProfileId", "targetOwnerRoutingReference",
				"targetType", "targetId", "interactionKind", "occurredAt", "deepLink");
		assertThat(properties.path("schemaVersion").path("const").asText()).isEqualTo("1.0");
		assertThat(properties.path("targetType").path("enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("POST", "COMMENT");
		assertThat(properties.path("interactionKind").path("enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("COMMENT", "REPLY", "SUPPORT", "RELATE", "THANK_YOU");
		assertThat(properties.path("deepLink").path("additionalProperties").asBoolean()).isFalse();
		assertThat(properties.path("deepLink").path("properties").propertyStream()
				.map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()))
				.isEqualTo(Set.of("kind", "postId"));
		assertThat(schema.toString()).doesNotContain("content", "media", "email", "displayName", "assessment",
				"journal", "supportPlan", "aiAnalysis");
	}

	@Test
	void runtimeFactSerializesWithExactlyTheFrozenContractFields() {
		var actorProfileId = UUID.fromString("00000000-0000-0000-0000-000000000101");
		var ownerRoutingReference = UUID.fromString("00000000-0000-0000-0000-000000000102");
		var postId = UUID.fromString("00000000-0000-0000-0000-000000000103");
		var fact = CommunityInteractionFact.create(actorProfileId, ownerRoutingReference, TargetType.POST, postId,
				InteractionKind.SUPPORT, postId, Instant.parse("2026-10-03T10:15:30Z"));

		var payload = objectMapper.valueToTree(fact);

		assertThat(payload.propertyStream().map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrder(
				"eventId", "schemaVersion", "actorCommunityProfileId", "targetOwnerRoutingReference",
				"targetType", "targetId", "interactionKind", "occurredAt", "deepLink");
		assertThat(payload.path("schemaVersion").asText()).isEqualTo("1.0");
		assertThat(payload.path("deepLink").propertyStream().map(java.util.Map.Entry::getKey).toList())
				.containsExactlyInAnyOrder("kind", "postId");
		assertThat(payload.toString()).doesNotContain("content", "media", "email", "displayName", "assessment",
				"journal", "supportPlan", "aiAnalysis");
	}
}
