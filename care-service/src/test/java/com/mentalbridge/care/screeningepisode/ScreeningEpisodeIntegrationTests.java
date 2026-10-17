package com.mentalbridge.care.screeningepisode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
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
class ScreeningEpisodeIntegrationTests extends CareTestProperties {

	private static final UUID PHQ9_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID GAD7_DEFINITION = UUID.fromString("10000000-0000-0000-0000-000000000003");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcClient jdbc;

	@Test
	void persistsExactGuidedPairAndResumesCompletedEpisodeWithoutCookies() throws Exception {
		var userId = insertProfile();
		var started = mvc.perform(post("/api/v1/screening-episodes").with(user(userId))
				.contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL_CHECK\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("IN_PROGRESS"))
				.andReturn();
		var episodeId = UUID.fromString(json(started, "episodeId"));

		var phq9 = mvc.perform(post("/api/v1/screening-episodes/{episodeId}/assessments/PHQ9", episodeId)
				.with(user(userId)).header("Idempotency-Key", "episode-phq9-assessment-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(PHQ9_DEFINITION, "14000000", 9)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.instrument").value("PHQ9"))
				.andReturn();
		var phq9Id = json(phq9, "assessmentId");

		mvc.perform(get("/api/v1/screening-episodes/history").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.items[0].startedAt").isNotEmpty())
				.andExpect(jsonPath("$.items[0].updatedAt").isNotEmpty())
				.andExpect(jsonPath("$.items[0].completedAt").doesNotExist())
				.andExpect(jsonPath("$.items[0].meaningState").value("INCOMPLETE"))
				.andExpect(jsonPath("$.items[0].evidence[0].instrument").value("PHQ9"))
				.andExpect(jsonPath("$.items[0].evidence[0].state").value("AVAILABLE"))
				.andExpect(jsonPath("$.items[0].evidence[0].assessmentId").value(phq9Id))
				.andExpect(jsonPath("$.items[0].evidence[0].totalScore").value(0))
				.andExpect(jsonPath("$.items[0].evidence[1].instrument").value("GAD7"))
				.andExpect(jsonPath("$.items[0].evidence[1].state").value("MISSING"))
				.andExpect(jsonPath("$.items[0].meaning").doesNotExist());

		var gad7 = mvc.perform(post("/api/v1/screening-episodes/{episodeId}/assessments/GAD7", episodeId)
				.with(user(userId)).header("Idempotency-Key", "episode-gad7-assessment-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body(GAD7_DEFINITION, "13000000", 7)))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.instrument").value("GAD7"))
				.andReturn();
		var gad7Id = json(gad7, "assessmentId");

		mvc.perform(get("/api/v1/screening-episodes/history").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].status").value("READY"))
				.andExpect(jsonPath("$.items[0].meaningState").value("INCOMPLETE"))
				.andExpect(jsonPath("$.items[0].evidence[0].state").value("AVAILABLE"))
				.andExpect(jsonPath("$.items[0].evidence[0].assessmentId").value(phq9Id))
				.andExpect(jsonPath("$.items[0].evidence[1].state").value("AVAILABLE"))
				.andExpect(jsonPath("$.items[0].evidence[1].assessmentId").value(gad7Id))
				.andExpect(jsonPath("$.items[0].meaning").doesNotExist());

		var evaluation = mvc.perform(post("/api/v1/screening-episodes/{episodeId}/support-evaluation", episodeId)
				.with(user(userId)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.episode.status").value("COMPLETED"))
				.andExpect(jsonPath("$.episode.phq9AssessmentId").value(phq9Id))
				.andExpect(jsonPath("$.episode.gad7AssessmentId").value(gad7Id))
				.andExpect(jsonPath("$.episode.supportEvaluationId").isNotEmpty())
				.andExpect(jsonPath("$.episode.presentationEvaluationId").isNotEmpty())
				.andReturn();
		var presentationEvaluationId = json(evaluation, "episode", "presentationEvaluationId");

		mvc.perform(get("/api/v1/screening-episodes/current").with(user(userId))
				.queryParam("purpose", "INITIAL_CHECK"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.episodeId").value(episodeId.toString()))
				.andExpect(jsonPath("$.status").value("COMPLETED"))
				.andExpect(jsonPath("$.phq9AssessmentId").value(phq9Id))
				.andExpect(jsonPath("$.gad7AssessmentId").value(gad7Id));

		mvc.perform(get("/api/v1/screening-episodes/history").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].episodeId").value(episodeId.toString()))
				.andExpect(jsonPath("$.items[0].purpose").value("INITIAL_CHECK"))
				.andExpect(jsonPath("$.items[0].status").value("COMPLETED"))
				.andExpect(jsonPath("$.items[0].startedAt").isNotEmpty())
				.andExpect(jsonPath("$.items[0].updatedAt").isNotEmpty())
				.andExpect(jsonPath("$.items[0].presentationEvaluationId").value(presentationEvaluationId))
				.andExpect(jsonPath("$.items[0].meaningState").value("AVAILABLE"))
				.andExpect(jsonPath("$.items[0].evidence[0].assessmentId").value(phq9Id))
				.andExpect(jsonPath("$.items[0].evidence[1].assessmentId").value(gad7Id))
				.andExpect(jsonPath("$.items[0].meaning.evidence[0].assessmentId").value(phq9Id))
				.andExpect(jsonPath("$.items[0].meaning.evidence[0].totalScore").value(0))
				.andExpect(jsonPath("$.items[0].meaning.evidence[1].assessmentId").value(gad7Id))
				.andExpect(jsonPath("$.items[0].meaning.evidence[1].totalScore").value(0))
				.andExpect(jsonPath("$.items[0].meaning.supportGuideId").doesNotExist());

		mvc.perform(get("/api/v1/screening-episodes/history").with(user(UUID.randomUUID())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items").isEmpty());

		mvc.perform(get("/api/v1/screening-episodes/history")
				.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden());

		assertThat(jdbc.sql("select count(*) from screening_episode where user_id = :userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select count(*) from support_guide where user_id = :userId")
				.param("userId", userId).query(Long.class).single()).isZero();
	}

	@Test
	void standaloneHistoryNeverBecomesGuidedEpisodeEvidence() throws Exception {
		var userId = insertProfile();
		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "standalone-phq9-assessment-01")
				.contentType(MediaType.APPLICATION_JSON).content(body(PHQ9_DEFINITION, "14000000", 9)))
				.andExpect(status().isCreated());

		var started = mvc.perform(post("/api/v1/screening-episodes").with(user(userId))
				.contentType(MediaType.APPLICATION_JSON).content("{\"purpose\":\"INITIAL_CHECK\"}"))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.status").value("IN_PROGRESS"))
				.andExpect(jsonPath("$.phq9AssessmentId").doesNotExist())
				.andExpect(jsonPath("$.gad7AssessmentId").doesNotExist())
				.andReturn();

		mvc.perform(get("/api/v1/screening-episodes/history").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].episodeId").value(json(started, "episodeId")))
				.andExpect(jsonPath("$.items[0].meaningState").value("INCOMPLETE"))
				.andExpect(jsonPath("$.items[0].evidence[0].state").value("MISSING"))
				.andExpect(jsonPath("$.items[0].evidence[1].state").value("MISSING"));
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id, display_name) values (:id, 'Episode test user')")
				.param("id", userId).update();
		jdbc.sql("""
				insert into consent_decision
				(id, user_id, consent_type, policy_version, granted, idempotency_key, request_hash, decided_at)
				values (:id, :userId, 'PRIVACY_POLICY', 'privacy-capstone-v3', true,
				        :key, :requestHash, now())
				""").param("id", UUID.randomUUID()).param("userId", userId)
				.param("key", "episode-consent-" + UUID.randomUUID())
				.param("requestHash", "0".repeat(64)).update();
		return userId;
	}

	private String body(UUID definitionId, String questionPrefix, int count) {
		var answers = new StringBuilder();
		for (int index = 1; index <= count; index++) {
			if (index > 1) answers.append(',');
			answers.append("{\"questionId\":\"").append(questionPrefix)
					.append("-0000-0000-0000-").append(String.format("%012d", index))
					.append("\",\"value\":0}");
		}
		return "{\"questionnaireDefinitionId\":\"" + definitionId
				+ "\",\"privacyPolicyVersion\":\"privacy-capstone-v3\","
				+ "\"privacyDisclosureAcknowledged\":true,\"answers\":[" + answers + "]}";
	}

	private String json(org.springframework.test.web.servlet.MvcResult result, String field) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get(field).asText();
	}

	private String json(org.springframework.test.web.servlet.MvcResult result, String parent, String field) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString()).get(parent).get(field).asText();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
