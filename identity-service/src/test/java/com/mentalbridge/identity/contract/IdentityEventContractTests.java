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

	@Test
	void administrationAuditEventSchemaIsStrictAndMinimizesPayload() throws Exception {
		var path = Path.of("..", "contracts", "events", "identity", "administration-audit-event-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));

		assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();

		assertThat(schema.get("required")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder(
						"eventId", "eventType", "occurredAt", "producer", "schemaVersion",
						"sourceService", "domain", "actorType", "action", "result", "correlationId"
				);

		assertThat(schema.get("properties").fieldNames()).toIterable()
				.containsExactlyInAnyOrder(
						"eventId", "eventType", "occurredAt", "producer", "schemaVersion",
						"sourceService", "domain", "actorId", "actorType", "action",
						"result", "reasonCode", "correlationId", "targetAccountId", "targetIdentifier"
				)
				.doesNotContain("rawJournal", "chatBody", "assessmentAnswers", "credentials", "providerPayload", "password", "token", "profile", "health");

		assertThat(schema.at("/properties/result/enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("SUCCEEDED", "DENIED", "FAILED");

		assertThat(schema.at("/properties/sourceService/enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("IDENTITY", "CONSULTATION", "CONTENT", "COMMUNITY");

		assertThat(schema.at("/properties/domain/enum")).extracting(node -> node.asText())
				.containsExactlyInAnyOrder("ACCOUNT_ADMINISTRATION", "SPECIALIST_REVIEW", "RESOURCE_MANAGEMENT", "COMMUNITY_MODERATION");

		assertThat(schema.at("/properties/eventType/enum")).extracting(node -> node.asText())
				.contains("identity.account.disabled", "identity.account.restored",
						"consultation.specialist.approved", "consultation.specialist.rejected",
						"consultation.specialist.suspended", "consultation.specialist.restored",
						"content.resource.archived",
						"content.safety-directory.reviewed", "content.safety-directory.deactivated",
						"community.moderation.action-applied", "community.moderation.case-resolved");
	}

	@Test
	void administrationAuditEventPayloadsConformToContract() throws Exception {
		var path = Path.of("..", "contracts", "events", "identity", "administration-audit-event-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var requiredFields = schema.get("required");
		var allowedFields = new java.util.HashSet<String>();
		schema.get("properties").fieldNames().forEachRemaining(allowedFields::add);

		String samplePayloadJson = """
				{
				  "eventId": "%s",
				  "eventType": "consultation.specialist.suspended",
				  "occurredAt": "%s",
				  "producer": "consultation-service",
				  "schemaVersion": "1.0",
				  "sourceService": "CONSULTATION",
				  "domain": "SPECIALIST_REVIEW",
				  "actorId": "%s",
				  "actorType": "ADMIN",
				  "action": "SPECIALIST_SUSPENDED",
				  "result": "SUCCEEDED",
				  "reasonCode": "POLICY_VIOLATION",
				  "correlationId": "%s",
				  "targetAccountId": "%s"
				}
				""".formatted(UUID.randomUUID(), Instant.now(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

		var sampleNode = objectMapper.readTree(samplePayloadJson);

		for (var req : requiredFields) {
			assertThat(sampleNode.hasNonNull(req.asText())).as("Required field %s is present", req.asText()).isTrue();
		}

		sampleNode.fieldNames().forEachRemaining(field -> {
			assertThat(allowedFields).as("Field %s is defined in schema properties", field).contains(field);
		});

		var allowedResults = new java.util.HashSet<String>();
		schema.at("/properties/result/enum").forEach(n -> allowedResults.add(n.asText()));
		assertThat(allowedResults).contains(sampleNode.get("result").asText());

		var allowedSources = new java.util.HashSet<String>();
		schema.at("/properties/sourceService/enum").forEach(n -> allowedSources.add(n.asText()));
		assertThat(allowedSources).contains(sampleNode.get("sourceService").asText());

		var allowedDomains = new java.util.HashSet<String>();
		schema.at("/properties/domain/enum").forEach(n -> allowedDomains.add(n.asText()));
		assertThat(allowedDomains).contains(sampleNode.get("domain").asText());
	}

}
