package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class CareEventContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void userNotificationFactIsStrictVersionedAndExcludesSensitiveAssessmentAndGuideData() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "user-notification-fact-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.at("/properties/messageType/const").asText())
				.isEqualTo("care.user-notification.fact");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("1.0");
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.containsExactlyInAnyOrder("recipientId", "factType", "targetId", "targetVersion", "availableAt")
				.doesNotContain("answers", "totalScore", "screeningLevel", "safetyStatus", "explanationText",
						"resourceTitles", "providerPayload");
	}

	@Test
	void assessmentSubmittedSchemaIsStrictAndExcludesRawAnswers() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "assessment-submitted-v2.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.doesNotContain("answers", "answerValues", "questionAnswers", "sessionToken");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("2.0");
		assertThat(payload.get("required")).extracting(node -> node.asText()).contains(
				"definitionId", "instrument", "totalScore", "safetyStatus", "safetyPolicyVersion", "completedAt");
		assertThat(payload.at("/properties/instrument/enum")).extracting(node -> node.asText())
				.containsExactly("PHQ9", "GAD7");
		assertThat(payload.at("/properties/safetyStatus/enum")).extracting(node -> node.asText())
				.contains("NOT_APPLICABLE");
	}

	@Test
	void assessmentSubmittedV1RemainsPhq9Only() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "assessment-submitted-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));

		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("1.0");
		assertThat(schema.at("/properties/payload/properties/instrument/const").asText()).isEqualTo("PHQ9");
		assertThat(schema.at("/properties/payload/properties/safetyStatus/enum"))
				.extracting(node -> node.asText())
				.containsExactly("NEGATIVE_SAFETY_SCREEN", "POSITIVE_SAFETY_SCREEN");
	}

	@Test
	void supportTierResolvedSchemaIsStrictAndExcludesScoresAndAnswers() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "support-tier-resolved-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.at("/properties/messageType/const").asText()).isEqualTo("care.support-tier.resolved");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("1.0");
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.doesNotContain("answers", "totalScore", "safetyItemPositive");
	}

	@Test
	void supportEvaluationV2EventIsDomainAwareMinimizedAndVersionedIndependently() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "support-evaluation-created-v2.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");
		var domain = schema.at("/$defs/domainContribution/properties");
		var safety = schema.at("/$defs/safetyEvidence/properties");

		assertThat(schema.at("/properties/messageType/const").asText())
				.isEqualTo("care.support-evaluation.created");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("2.0");
		assertThat(payload.at("/properties/evaluationVersion/const").asInt()).isEqualTo(2);
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.contains("contributingDomains", "safetyEvidence")
				.doesNotContain("supportTier", "overallSeverity", "answers", "totalScore");
		assertThat(domain.fieldNames()).toIterable().contains("assessmentId", "questionnaireDefinitionId", "domain",
				"screeningLevel", "supportPathway", "reasonCodes")
				.doesNotContain("answers", "totalScore", "safetyStatus");
		assertThat(safety.fieldNames()).toIterable().doesNotContain("answerValue", "item9Answer");
	}

	@Test
	void supportPlanActivatedSchemaIsStrictPaidAndCarriesPolicyVersions() throws Exception {
		var path = Path.of("..", "contracts", "events", "care", "support-plan-activated-v1.schema.json")
				.toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.at("/properties/messageType/const").asText())
				.isEqualTo("care.support-plan.activated");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("1.0");
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.at("/properties/packageCode/enum")).extracting(node -> node.asText())
				.containsExactly("PLUS", "PREMIUM");
		assertThat(payload.get("required")).extracting(node -> node.asText()).contains(
				"supportPlanId", "userId", "planVersion", "activatedAt",
				"evaluationPolicyVersion", "resourceEligibilityPolicyVersion", "packageCode");
	}

	@Test
	void supportPlanEngagementEventIsMinimizedAndExcludesReflection() throws Exception {
		var path = Path.of("..", "contracts", "events", "care",
				"support-plan-engagement-changed-v1.schema.json").toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");

		assertThat(schema.at("/properties/messageType/const").asText())
				.isEqualTo("care.support-plan.engagement-changed");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("1.0");
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.contains("state", "helpfulness", "barrierCode", "summaryReuseApproved",
						"sourcePlanVersion", "sourceContentVersion")
				.doesNotContain("reflection", "reflectionText", "adherence", "recoveryScore");
	}

	@Test
	void supportPlanActionOccurrenceV2SeparatesCareOrchestrationFromBoundedOwnerFact() throws Exception {
		var path = Path.of("..", "contracts", "events", "care",
				"support-plan-action-occurrence-changed-v2.schema.json").toAbsolutePath();
		var schema = objectMapper.readTree(Files.readString(path));
		var payload = schema.at("/properties/payload");
		var orchestration = schema.at("/$defs/orchestration");
		var ownerFact = schema.at("/$defs/boundedOwnerFact");
		var rules = payload.get("allOf");

		assertThat(schema.at("/properties/messageType/const").asText())
				.isEqualTo("care.support-plan.action-occurrence-changed");
		assertThat(schema.at("/properties/schemaVersion/const").asText()).isEqualTo("2.0");
		assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("additionalProperties").asBoolean()).isFalse();
		assertThat(payload.get("properties").fieldNames()).toIterable()
				.contains("actionId", "actionType", "actionVersion", "scheduleId", "scheduleVersion",
						"representationMode", "orchestration", "ownerFact")
				.doesNotContain("sourcePayload", "journalText", "emotionValue", "assessmentAnswers",
						"appointmentNotes", "rawScore");
		assertThat(payload.get("required")).extracting(node -> node.asText())
				.contains("representationMode", "actionType", "orchestration", "ownerFact");
		assertThat(orchestration.get("properties").fieldNames()).toIterable()
				.containsExactlyInAnyOrder("state", "displayState", "stateReason", "outcomeRecordedAt",
						"interpretationCode");
		assertThat(ownerFact.get("additionalProperties").asBoolean()).isFalse();
		assertThat(ownerFact.get("properties").fieldNames()).toIterable()
				.containsExactlyInAnyOrder("ownerDomain", "kind", "factId", "factRevision", "occurredAt",
						"verifiedAt");

		var adapterRule = ruleWithConstant(rules, "/if/properties/representationMode/const",
				"V2_RESOURCE_ADAPTER");
		assertThat(adapterRule.at("/then/properties/ownerFact/type").asText()).isEqualTo("null");

		var nativeCompletedRule = ruleWithConstant(rules,
				"/if/properties/orchestration/properties/state/const", "COMPLETED");
		assertThat(nativeCompletedRule.at("/if/properties/representationMode/const").asText())
				.isEqualTo("NATIVE_V3");
		assertThat(nativeCompletedRule.at("/then/properties/ownerFact/$ref").asText())
				.isEqualTo("#/$defs/boundedOwnerFact");
		assertThat(nativeCompletedRule.at(
				"/then/properties/orchestration/properties/outcomeRecordedAt/type").asText()).isEqualTo("string");

		var nonCompletedRule = ruleWithEnumValue(rules,
				"/if/properties/orchestration/properties/state/enum", "SCHEDULED");
		assertThat(nonCompletedRule.at("/if/properties/orchestration/properties/state/enum"))
				.extracting(node -> node.asText())
				.containsExactly("SCHEDULED", "SKIPPED", "CANCELLED");
		assertThat(nonCompletedRule.at("/then/properties/ownerFact/type").asText()).isEqualTo("null");
		assertThat(nonCompletedRule.at(
				"/then/properties/orchestration/properties/outcomeRecordedAt/type").asText()).isEqualTo("null");

		var evidenceAttachedRule = ruleWithConstant(rules, "/if/properties/changeType/const",
				"EVIDENCE_ATTACHED");
		assertThat(evidenceAttachedRule.at("/then/properties/representationMode/const").asText())
				.isEqualTo("NATIVE_V3");
		assertThat(evidenceAttachedRule.at(
				"/then/properties/orchestration/properties/state/const").asText()).isEqualTo("COMPLETED");
		assertThat(evidenceAttachedRule.at("/then/properties/ownerFact/$ref").asText())
				.isEqualTo("#/$defs/boundedOwnerFact");

		var resourceRule = ruleWithActionType(rules, "RESOURCE");
		assertThat(resourceRule.at("/then/properties/ownerFact/oneOf/0/type").asText()).isEqualTo("null");
		assertThat(resourceRule.at("/then/properties/ownerFact/oneOf/1/allOf/1/properties/kind/enum"))
				.extracting(JsonNode::asText)
				.containsExactly("USER_ACKNOWLEDGEMENT", "RESOURCE_PRACTICE_SESSION", "RESOURCE_COMPLETION");
		assertOwnerFactMapping(rules, "ROUTINE_SELF_MANAGEMENT", "CARE", "USER_ACKNOWLEDGEMENT");
		assertOwnerFactMapping(rules, "SELF_REPORT_REVIEW", "CARE", "SELF_REPORT_REVIEW");
		assertOwnerFactMapping(rules, "JOURNAL", "JOURNAL_AI", "JOURNAL_ENTRY");
		assertOwnerFactMapping(rules, "EMOTION_CHECK_IN", "JOURNAL_AI", "EMOTION_CHECK_IN");
		assertOwnerFactMapping(rules, "PROFESSIONAL_SUPPORT", "CONSULTATION", "APPOINTMENT_FULFILMENT");
		assertOwnerFactMapping(rules, "REASSESSMENT_SCREENING", "CARE", "ASSESSMENT_HISTORY");
	}

	@Test
	void resourceFactsKeepContentAuthorityAndExactRevisionWhileAcknowledgementStaysCareOwned() throws Exception {
		var path = Path.of("..", "contracts", "events", "care",
				"support-plan-action-occurrence-changed-v2.schema.json").toAbsolutePath();
		var contract = objectMapper.readTree(Files.readString(path));
		var schema = objectMapper.createObjectNode().put("$ref", "#/$defs/boundedOwnerFact");
		schema.set("$defs", contract.path("$defs"));
		var validator = com.networknt.schema.SchemaRegistry.withDialect(com.networknt.schema.dialect.Dialects.getDraft202012())
				.getSchema(schema.toString(), com.networknt.schema.InputFormat.JSON);
		var fact = objectMapper.createObjectNode().put("factId", "resource:2026-10-21")
				.put("factRevision", "7").put("occurredAt", "2026-10-21T07:59:00Z")
				.put("verifiedAt", "2026-10-21T08:00:00Z");
		for (String kind : java.util.List.of("RESOURCE_PRACTICE_SESSION", "RESOURCE_COMPLETION")) {
			fact.put("kind", kind).put("ownerDomain", "CONTENT").put("factRevision", "7");
			assertThat(validator.validate(fact)).isEmpty();
			fact.put("ownerDomain", "CARE");
			assertThat(validator.validate(fact)).isNotEmpty();
			fact.put("ownerDomain", "CONTENT").putNull("factRevision");
			assertThat(validator.validate(fact)).isNotEmpty();
		}
		fact.put("kind", "USER_ACKNOWLEDGEMENT").put("ownerDomain", "CARE").putNull("factRevision");
		assertThat(validator.validate(fact)).isEmpty();
		fact.put("ownerDomain", "CONTENT");
		assertThat(validator.validate(fact)).isNotEmpty();
	}

	private void assertOwnerFactMapping(JsonNode rules, String actionType, String ownerDomain, String kind) {
		var rule = ruleWithActionType(rules, actionType);
		var constraint = rule.at("/then/properties/ownerFact/oneOf/1/allOf/1/properties");

		assertThat(rule.at("/then/properties/ownerFact/oneOf/0/type").asText()).isEqualTo("null");
		assertThat(constraint.at("/ownerDomain/const").asText()).isEqualTo(ownerDomain);
		assertThat(constraint.at("/kind/const").asText()).isEqualTo(kind);
	}

	private JsonNode ruleWithActionType(JsonNode rules, String actionType) {
		for (var rule : rules) {
			var condition = rule.at("/if/properties/actionType");
			if (actionType.equals(condition.path("const").asText()) || containsText(condition.path("enum"), actionType)) {
				return rule;
			}
		}
		throw new IllegalStateException("Missing action type rule for " + actionType);
	}

	private JsonNode ruleWithConstant(JsonNode rules, String pointer, String expected) {
		for (var rule : rules) {
			if (expected.equals(rule.at(pointer).asText())) return rule;
		}
		throw new IllegalStateException("Missing contract rule for " + expected);
	}

	private JsonNode ruleWithEnumValue(JsonNode rules, String pointer, String expected) {
		for (var rule : rules) {
			if (containsText(rule.at(pointer), expected)) return rule;
		}
		throw new IllegalStateException("Missing contract enum rule for " + expected);
	}

	private boolean containsText(JsonNode values, String expected) {
		if (!values.isArray()) return false;
		for (var value : values) {
			if (expected.equals(value.asText())) return true;
		}
		return false;
	}
}
