package com.mentalbridge.care.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class Gad7AssessmentIntegrationTests extends CareTestProperties {

	private static final UUID DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private ObjectMapper objectMapper;

	@ParameterizedTest
	@CsvSource({
			"0,MINIMAL",
			"4,MINIMAL",
			"5,MILD",
			"9,MILD",
			"10,MODERATE",
			"14,MODERATE",
			"15,SEVERE",
			"21,SEVERE"
	})
	void scoresEveryGad7BoundaryWithExplicitNotApplicableSafety(int total, String level) throws Exception {
		var userId = insertProfile();
		var response = mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "gad7-boundary-" + total + "-000000")
				.contentType(MediaType.APPLICATION_JSON).content(body(valuesFor(total))))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.instrument").value("GAD7"))
				.andExpect(jsonPath("$.questionnaireVersion").value("gad7-vi-vn-adult-v1"))
				.andExpect(jsonPath("$.privacyPolicyVersion").value("privacy-capstone-v3"))
				.andExpect(jsonPath("$.result.totalScore").value(total))
				.andExpect(jsonPath("$.result.screeningLevel").value(level))
				.andExpect(jsonPath("$.result.safetyStatus").value("NOT_APPLICABLE"))
				.andReturn();

		assertThat(objectMapper.readTree(response.getResponse().getContentAsString())
				.at("/result/safetyPolicyVersion").isNull()).isTrue();
	}

	@Test
	void persistsSevenAnswersAndOneVersionedEventOnIdempotentRetry() throws Exception {
		var userId = insertProfile();
		var key = "gad7-idempotent-000001";
		var content = body(valuesFor(10));
		var first = mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(content))
				.andExpect(status().isCreated()).andReturn();
		var assessmentId = objectMapper.readTree(first.getResponse().getContentAsString()).get("assessmentId").asText();

		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(content))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.assessmentId").value(assessmentId));

		assertThat(jdbc.sql("select count(*) from assessment_answer where submission_id = :id")
				.param("id", UUID.fromString(assessmentId)).query(Long.class).single()).isEqualTo(7);
		var safety = jdbc.sql("""
				select safety_item_positive, safety_status, safety_policy_version
				from assessment_result where submission_id = :id
				""").param("id", UUID.fromString(assessmentId))
				.query((resultSet, row) -> new Object[] {
						resultSet.getObject("safety_item_positive"),
						resultSet.getString("safety_status"),
						resultSet.getString("safety_policy_version")
				}).single();
		assertThat(safety).containsExactly(null, "NOT_APPLICABLE", null);
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = :id")
				.param("id", UUID.fromString(assessmentId)).query(Long.class).single()).isEqualTo(1);
		var event = jdbc.sql("""
				select schema_version || '|' || (payload ->> 'instrument') || '|' ||
				       (payload ->> 'safetyStatus') || '|' || (payload ->> 'totalScore')
				from outbox_event where aggregate_id = :id
				""").param("id", UUID.fromString(assessmentId)).query(String.class).single();
		assertThat(event).isEqualTo("2.0|GAD7|NOT_APPLICABLE|10");
	}

	@Test
	void rejectsIncompleteDuplicateAndNonScoreAnswersWithoutPersistence() throws Exception {
		var userId = insertProfile();

		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "gad7-incomplete-000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(new int[] { 0, 0, 0, 0, 0, 0 })))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "gad7-refusal-00000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(new int[] { -1, 0, 0, 0, 0, 0, 0 })))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "gad7-above-max-000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(new int[] { 4, 0, 0, 0, 0, 0, 0 })))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "gad7-refusal-00000002")
				.contentType(MediaType.APPLICATION_JSON).content(body(new int[] { 88, 0, 0, 0, 0, 0, 0 })))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "gad7-unknown-00000001")
				.contentType(MediaType.APPLICATION_JSON).content(body(new int[] { 99, 0, 0, 0, 0, 0, 0 })))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "gad7-duplicate-000001")
				.contentType(MediaType.APPLICATION_JSON).content(duplicateBody()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		assertThat(jdbc.sql("select count(*) from assessment_submission where user_id = :userId")
				.param("userId", userId).query(Long.class).single()).isZero();
	}

	@Test
	void historicalDefinitionEndpointRejectsDraftDefinitions() throws Exception {
		var draftId = jdbc.sql("""
				insert into questionnaire_definition (
				    instrument, version, locale, title, reference_period_days,
				    expected_question_count, scoring_version, response_options, source_reference
				) values (
				    'GAD7', :version, 'en-US', 'Draft only', 14, 7, 'draft-scoring-v1',
				    '[{"value":0,"label":"0"},{"value":1,"label":"1"},{"value":2,"label":"2"},{"value":3,"label":"3"}]'::jsonb,
				    'test-only'
				) returning id
				""").param("version", "draft-" + UUID.randomUUID().toString().substring(0, 8))
				.query(UUID.class).single();

		mvc.perform(get("/api/v1/questionnaires/definitions/{definitionId}", draftId))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUESTIONNAIRE_NOT_FOUND"));
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id, display_name) values (:id, 'GAD-7 test user')")
				.param("id", userId).update();
		jdbc.sql("""
				insert into consent_decision
				(id, user_id, consent_type, policy_version, granted, idempotency_key, request_hash, decided_at)
				values (:id, :userId, 'PRIVACY_POLICY', 'privacy-capstone-v3', true,
				        :key, :requestHash, now())
				""").param("id", UUID.randomUUID()).param("userId", userId)
				.param("key", "gad7-consent-" + UUID.randomUUID())
				.param("requestHash", "0".repeat(64)).update();
		return userId;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private int[] valuesFor(int total) {
		var values = new int[7];
		var remaining = total;
		for (var index = 0; index < values.length; index++) {
			values[index] = Math.min(3, remaining);
			remaining -= values[index];
		}
		return values;
	}

	private String body(int[] values) {
		var answers = new StringBuilder();
		for (var index = 0; index < values.length; index++) {
			if (index > 0) answers.append(',');
			answers.append("{\"questionId\":\"13000000-0000-0000-0000-")
					.append(String.format("%012d", index + 1)).append("\",\"value\":")
					.append(values[index]).append('}');
		}
		return envelope(answers.toString());
	}

	private String duplicateBody() {
		return envelope("""
				{"questionId":"13000000-0000-0000-0000-000000000001","value":0},
				{"questionId":"13000000-0000-0000-0000-000000000001","value":0},
				{"questionId":"13000000-0000-0000-0000-000000000003","value":0},
				{"questionId":"13000000-0000-0000-0000-000000000004","value":0},
				{"questionId":"13000000-0000-0000-0000-000000000005","value":0},
				{"questionId":"13000000-0000-0000-0000-000000000006","value":0},
				{"questionId":"13000000-0000-0000-0000-000000000007","value":0}
				""".replace(System.lineSeparator(), ""));
	}

	private String envelope(String answers) {
		return "{\"questionnaireDefinitionId\":\"" + DEFINITION_ID
				+ "\",\"privacyPolicyVersion\":\"privacy-capstone-v3\","
				+ "\"privacyDisclosureAcknowledged\":true,\"answers\":[" + answers + "]}";
	}
}
