package com.mentalbridge.identity.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.registration.OutboxEventEntity;

class IdentityEventContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void accountLifecycleSchemasAreValidJsonSchemaDocuments() throws Exception {
		var directory = Path.of("..", "contracts", "events", "identity").toAbsolutePath();
		for (var name : new String[] { "account-registered-v1.schema.json",
				"account-email-verified-v1.schema.json", "account-state-changed-v1.schema.json" }) {
			var schema = objectMapper.readTree(Files.readString(directory.resolve(name)));
			assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
			assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
			assertThat(schema.at("/properties/payload").get("additionalProperties").asBoolean()).isFalse();
		}
	}

	@Test
	void accountStateChangedSchemaIsStrictAndMinimizesPayload() throws Exception {
		var path = Path.of("..", "contracts", "events", "identity", "account-state-changed-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.at("/properties/messageType/const").asText()).isEqualTo("identity.account.state-changed");
		assertThat(schema.at("/properties/producer/const").asText()).isEqualTo("identity-service");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("1.0");
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();

		assertThat(payload.get("required")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("accountId", "status", "role", "reasonCode");

		assertThat(payload.get("properties").fieldNames()).toIterable()
				.containsExactlyInAnyOrder("accountId", "status", "role", "reasonCode")
				.doesNotContain("email", "password", "passwordHash", "deviceLabel", "token", "profile", "health");

		assertThat(payload.at("/properties/status/enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("PENDING_EMAIL_VERIFICATION", "ACTIVE", "DISABLED");
		assertThat(payload.at("/properties/role/enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("USER", "SPECIALIST");
		assertThat(payload.at("/properties/reasonCode/enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("SAFETY_CONCERN", "POLICY_VIOLATION", "ACCOUNT_REVIEW_REQUIRED", "REVIEW_COMPLETED");
	}

	@Test
	void specialistLifecycleFactsConformToContractRequirements() {
		UUID specialistId = UUID.randomUUID();
		UUID correlationId = UUID.randomUUID();
		Instant now = Instant.parse("2026-10-02T00:00:00Z");

		// SPECIALIST DISABLED
		OutboxEventEntity suspendEvent = new OutboxEventEntity(
				"identity.account.state-changed", specialistId, 1L, correlationId,
				Map.of("accountId", specialistId.toString(), "status", "DISABLED", "role", "SPECIALIST",
						"reasonCode", "ACCOUNT_REVIEW_REQUIRED"),
				now);

		assertThat(suspendEvent.messageType()).isEqualTo("identity.account.state-changed");
		assertThat(suspendEvent.schemaVersion()).isEqualTo("1.0");
		assertThat(suspendEvent.aggregateId()).isEqualTo(specialistId);
		assertThat(suspendEvent.correlationId()).isEqualTo(correlationId);
		assertThat(suspendEvent.payload())
				.containsOnlyKeys("accountId", "status", "role", "reasonCode")
				.containsEntry("accountId", specialistId.toString())
				.containsEntry("status", "DISABLED")
				.containsEntry("role", "SPECIALIST")
				.containsEntry("reasonCode", "ACCOUNT_REVIEW_REQUIRED");

		// SPECIALIST ACTIVE (Restored)
		OutboxEventEntity restoreEvent = new OutboxEventEntity(
				"identity.account.state-changed", specialistId, 2L, correlationId,
				Map.of("accountId", specialistId.toString(), "status", "ACTIVE", "role", "SPECIALIST",
						"reasonCode", "REVIEW_COMPLETED"),
				now);

		assertThat(restoreEvent.messageType()).isEqualTo("identity.account.state-changed");
		assertThat(restoreEvent.schemaVersion()).isEqualTo("1.0");
		assertThat(restoreEvent.aggregateId()).isEqualTo(specialistId);
		assertThat(restoreEvent.payload())
				.containsOnlyKeys("accountId", "status", "role", "reasonCode")
				.containsEntry("accountId", specialistId.toString())
				.containsEntry("status", "ACTIVE")
				.containsEntry("role", "SPECIALIST")
				.containsEntry("reasonCode", "REVIEW_COMPLETED");
	}

}
