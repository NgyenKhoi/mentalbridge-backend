package com.mentalbridge.care.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AssessmentFlowIntegrationTests extends CareTestProperties {

	private static final UUID DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID VI_DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final Set<String> IMPLEMENTED_OPERATIONS = Set.of(
			"GET /api/v1/privacy-disclosures/current",
			"GET /api/v1/profile",
			"PUT /api/v1/profile",
			"GET /api/v1/consents",
			"POST /api/v1/consent-decisions",
			"GET /api/v1/questionnaires/{instrument}/current",
			"POST /api/v1/anonymous-assessment-sessions",
			"GET /api/v1/assessments",
			"POST /api/v1/assessments",
			"GET /api/v1/assessments/{assessmentId}",
			"GET /api/v1/assessments/{assessmentId}/progress",
			"POST /api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
			"GET /api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	private RequestMappingHandlerMapping handlerMapping;

	@Test
	void publishedQuestionnaireDefaultsToTheCapstoneVietnameseVersion() throws Exception {
		mvc.perform(get("/api/v1/questionnaires/PHQ9/current"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.definitionId").value(VI_DEFINITION_ID.toString()))
				.andExpect(jsonPath("$.version").value("phq9-vi-vn-capstone-v1"))
				.andExpect(jsonPath("$.locale").value("vi-VN"))
				.andExpect(jsonPath("$.questions.length()").value(9))
				.andExpect(jsonPath("$.questions[8].itemNumber").value(9))
				.andExpect(jsonPath("$.responseOptions[0].label").value("Không có gì"));

		mvc.perform(get("/api/v1/questionnaires/PHQ9/current").queryParam("locale", "en-US"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.definitionId").value(DEFINITION_ID.toString()))
				.andExpect(jsonPath("$.questions.length()").value(9))
				.andExpect(jsonPath("$.responseOptions.length()").value(4));
	}

	@Test
	void authenticatedSubmissionScoresPersistsOutboxAndReplaysIdempotently() throws Exception {
		var userId = insertProfile();
		var body = completeBody(1);
		var first = mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER")))
				.header("Idempotency-Key", "assessment-auth-flow-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.result.totalScore").value(8))
				.andExpect(jsonPath("$.result.screeningLevel").value("MILD"))
				.andExpect(jsonPath("$.result.safetyStatus").value("POSITIVE_SAFETY_SCREEN"))
				.andExpect(jsonPath("$.result.safetyPolicyVersion").value("MB-SAFETY-PHQ9-001-test-v1"))
				.andReturn();
		var assessmentId = json(first).get("assessmentId").asText();

		mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER")))
				.header("Idempotency-Key", "assessment-auth-flow-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.assessmentId").value(assessmentId));

		assertThat(jdbc.sql("select count(*) from assessment_answer where submission_id = :id")
				.param("id", UUID.fromString(assessmentId)).query(Long.class).single()).isEqualTo(9);
		assertThat(jdbc.sql("select count(*) from outbox_event where aggregate_id = :id")
				.param("id", UUID.fromString(assessmentId)).query(Long.class).single()).isEqualTo(1);
		var requestHash = jdbc.sql("select request_hash from assessment_submission where id = :id")
				.param("id", UUID.fromString(assessmentId)).query(String.class).single();
		assertThat(requestHash).hasSize(64).isNotEqualTo(unkeyedRequestHash(1));
		var payload = jdbc.sql("select payload::text from outbox_event where aggregate_id = :id")
				.param("id", UUID.fromString(assessmentId)).query(String.class).single();
		assertThat(payload).contains("POSITIVE_SAFETY_SCREEN", userId.toString())
				.doesNotContain("questionId", "answerValue", "answers");

		mvc.perform(get("/api/v1/assessments/{assessmentId}", assessmentId)
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.assessmentId").value(assessmentId));
	}

	@Test
	void idempotencyConflictAndIncompleteAnswersPersistNoSecondOutcome() throws Exception {
		var userId = insertProfile();
		var key = "assessment-conflict-0001";
		mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER")))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(completeBody(0)))
				.andExpect(status().isCreated());

		mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER")))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(completeBody(1)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		var before = jdbc.sql("select count(*) from assessment_submission where user_id = :userId")
				.param("userId", userId).query(Long.class).single();
		mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER")))
				.header("Idempotency-Key", "assessment-incomplete-001")
				.contentType(MediaType.APPLICATION_JSON).content(incompleteBody()))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].code").value("INCOMPLETE_QUESTIONNAIRE"));
		assertThat(jdbc.sql("select count(*) from assessment_submission where user_id = :userId")
				.param("userId", userId).query(Long.class).single()).isEqualTo(before);
	}

	@Test
	void anonymousSessionTokenIsRequiredAndExpiryFailsClosed() throws Exception {
		var sessionResult = mvc.perform(post("/api/v1/anonymous-assessment-sessions"))
				.andExpect(status().isCreated()).andReturn();
		var session = json(sessionResult);
		var sessionId = session.get("sessionId").asText();
		var sessionToken = session.get("sessionToken").asText();
		assertThat(objectMapper.treeToValue(session, AssessmentController.AnonymousSessionResponse.class).toString())
				.doesNotContain(sessionToken).contains("[REDACTED]");
		assertThat(objectMapper.readValue(completeBody(1), AssessmentController.AssessmentSubmissionRequest.class)
				.toString()).doesNotContain("11000000-0000-0000-0000-000000000001").contains("[REDACTED]");

		var assessmentResult = mvc.perform(post("/api/v1/anonymous-assessment-sessions/{sessionId}/assessments",
				sessionId).header("X-Anonymous-Session-Token", sessionToken)
				.header("Idempotency-Key", "assessment-anonymous-001")
				.contentType(MediaType.APPLICATION_JSON).content(completeBody(1)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.result.screeningLevel").value("MILD"))
				.andExpect(jsonPath("$.result.safetyStatus").value("POSITIVE_SAFETY_SCREEN"))
				.andReturn();
		var assessmentId = json(assessmentResult).get("assessmentId").asText();

		mvc.perform(get("/api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}",
				sessionId, assessmentId))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_ANONYMOUS_SESSION"));

		mvc.perform(get("/api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}",
				sessionId, assessmentId).header("X-Anonymous-Session-Token", "x".repeat(43)))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_ANONYMOUS_SESSION"));

		jdbc.sql("update anonymous_assessment_session set created_at = :createdAt, expires_at = :expiresAt where id = :id")
				.param("createdAt", OffsetDateTime.now().minusHours(2))
				.param("expiresAt", OffsetDateTime.now().minusHours(1))
				.param("id", UUID.fromString(sessionId)).update();
		mvc.perform(get("/api/v1/anonymous-assessment-sessions/{sessionId}/assessments/{assessmentId}",
				sessionId, assessmentId).header("X-Anonymous-Session-Token", sessionToken))
				.andExpect(status().isGone())
				.andExpect(jsonPath("$.code").value("ANONYMOUS_SESSION_EXPIRED"));
	}

	@Test
	void assessmentAuthorizationRequiresUserRoleAndExistingProfile() throws Exception {
		mvc.perform(post("/api/v1/assessments").header("Idempotency-Key", "assessment-no-auth-001")
				.contentType(MediaType.APPLICATION_JSON).content(completeBody(0)))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))
				.header("Idempotency-Key", "assessment-specialist-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeBody(0)))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CARE_USER_REQUIRED"));

		mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER")))
				.header("Idempotency-Key", "assessment-no-profile-01")
				.contentType(MediaType.APPLICATION_JSON).content(completeBody(0)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
	}

	@Test
	void progressSelectsTheImmediatelyPrecedingCompatibleOwnedResult() throws Exception {
		var userId = insertProfile();
		var previousId = submit(userId, "assessment-progress-previous", new int[9]);
		var incompatibleId = submit(userId, "assessment-progress-incompatible", new int[] { 1, 1, 1, 1, 1, 1, 1, 1, 1 });
		var voidedId = submit(userId, "assessment-progress-voided", new int[] { 1, 1, 1, 1, 1, 1, 1, 1, 1 });
		var currentId = submit(userId, "assessment-progress-current", new int[] { 2, 1, 1, 1, 1, 1, 1, 1, 1 });

		setSubmittedAt(previousId, "2026-09-04T09:00:00Z");
		setSubmittedAt(incompatibleId, "2026-09-04T10:00:00Z");
		setSubmittedAt(voidedId, "2026-09-04T11:00:00Z");
		setSubmittedAt(currentId, "2026-09-04T12:00:00Z");
		jdbc.sql("update assessment_result set scoring_version = 'phq9-other-bands-v1' where submission_id = :id")
				.param("id", incompatibleId).update();
		jdbc.sql("update assessment_submission set voided_at = :voidedAt, void_reason_code = 'TEST_VOID' where id = :id")
				.param("voidedAt", OffsetDateTime.parse("2026-09-04T11:30:00Z")).param("id", voidedId).update();

		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", currentId).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.instrument").value("PHQ9"))
				.andExpect(jsonPath("$.scoringVersion").value("phq9-standard-bands-v1"))
				.andExpect(jsonPath("$.previous.assessmentId").value(previousId.toString()))
				.andExpect(jsonPath("$.previous.questionnaireVersion").value("phq9-en-us-v1"))
				.andExpect(jsonPath("$.previous.totalScore").value(0))
				.andExpect(jsonPath("$.previous.screeningLevel").value("MINIMAL"))
				.andExpect(jsonPath("$.current.assessmentId").value(currentId.toString()))
				.andExpect(jsonPath("$.current.totalScore").value(10))
				.andExpect(jsonPath("$.current.screeningLevel").value("MODERATE"))
				.andExpect(jsonPath("$.rawDelta").value(10))
				.andExpect(jsonPath("$.scoreDirection").value("INCREASED"))
				.andExpect(jsonPath("$.bandTransition.previous").value("MINIMAL"))
				.andExpect(jsonPath("$.bandTransition.current").value("MODERATE"))
				.andExpect(jsonPath("$.elapsedDuration").value("PT3H"))
				.andExpect(jsonPath("$.previous.safetyStatus").doesNotExist())
				.andExpect(jsonPath("$.current.safetyStatus").doesNotExist());

		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", incompatibleId).with(user(userId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_COMPARABLE_DATA"));
		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", voidedId).with(user(userId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_COMPARABLE_DATA"));

		jdbc.sql("delete from assessment_result where submission_id = :id").param("id", previousId).update();
		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", currentId).with(user(userId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_COMPARABLE_DATA"));
	}

	@Test
	void progressFailsClosedForMissingCrossOwnerUnauthorizedAndMalformedRequests() throws Exception {
		var ownerId = insertProfile();
		var assessmentId = submit(ownerId, "assessment-progress-owner", new int[9]);

		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", assessmentId))
				.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", assessmentId)
				.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("CARE_USER_REQUIRED"));
		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", assessmentId).with(user(UUID.randomUUID())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"));
		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", UUID.randomUUID()).with(user(ownerId)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ASSESSMENT_NOT_FOUND"));
		mvc.perform(get("/api/v1/assessments/not-a-uuid/progress").with(user(ownerId)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", assessmentId).with(user(ownerId)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_COMPARABLE_DATA"));
	}

	@Test
	void progressUsesAssessmentIdToBreakEqualSubmissionTimeTies() throws Exception {
		var userId = insertProfile();
		var firstId = submit(userId, "assessment-progress-tie-first", new int[9]);
		var secondId = submit(userId, "assessment-progress-tie-second", new int[] { 1, 1, 1, 1, 1, 1, 1, 1, 1 });
		setSubmittedAt(firstId, "2026-09-04T09:00:00Z");
		setSubmittedAt(secondId, "2026-09-04T09:00:00Z");
		var orderedIds = jdbc.sql("""
				select id from assessment_submission
				where user_id = :userId order by submitted_at desc, id desc
				""").param("userId", userId).query(UUID.class).list();
		var currentId = orderedIds.getFirst();
		var previousId = orderedIds.get(1);

		mvc.perform(get("/api/v1/assessments/{assessmentId}/progress", currentId).with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.previous.assessmentId").value(previousId.toString()))
				.andExpect(jsonPath("$.current.assessmentId").value(currentId.toString()))
				.andExpect(jsonPath("$.elapsedDuration").value("PT0S"));
	}

	@Test
	void runtimeHandlersExactlyMatchImplementedCareContractPaths() {
		var operations = handlerMapping.getHandlerMethods().keySet().stream()
				.flatMap(mapping -> mapping.getPatternValues().stream()
						.flatMap(path -> mapping.getMethodsCondition().getMethods().stream()
								.map(method -> method.name() + " " + path)))
				.filter(operation -> operation.contains(" /api/v1/"))
				.collect(java.util.stream.Collectors.toSet());

		assertThat(operations).isEqualTo(IMPLEMENTED_OPERATIONS);
	}

	@Test
	void concurrentAuthenticatedRetriesProduceOneSubmissionAndOneOutboxFact() throws Exception {
		var userId = insertProfile();
		var ready = new CountDownLatch(2);
		var start = new CountDownLatch(1);
		var executor = Executors.newFixedThreadPool(2);
		try {
			var first = executor.submit(() -> submitAfterSignal(userId, ready, start));
			var second = executor.submit(() -> submitAfterSignal(userId, ready, start));
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			var responses = List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

			assertThat(responses).extracting(response -> response.getResponse().getStatus()).containsOnly(201);
			var firstAssessmentId = json(responses.getFirst()).get("assessmentId").asText();
			var secondAssessmentId = json(responses.get(1)).get("assessmentId").asText();
			assertThat(secondAssessmentId).isEqualTo(firstAssessmentId);
			assertThat(jdbc.sql("select count(*) from assessment_submission where user_id = :userId")
					.param("userId", userId).query(Long.class).single()).isEqualTo(1);
			assertThat(jdbc.sql("select count(*) from outbox_event where payload ->> 'userId' = :userId")
					.param("userId", userId.toString()).query(Long.class).single()).isEqualTo(1);
		}
		finally {
			executor.shutdownNow();
		}
	}

	private UUID insertProfile() {
		var userId = UUID.randomUUID();
		jdbc.sql("insert into user_profile (account_id, display_name) values (:id, 'Assessment test user')")
				.param("id", userId).update();
		jdbc.sql("""
				insert into consent_decision
				(id, user_id, consent_type, policy_version, granted, idempotency_key, request_hash, decided_at)
				values (:id, :userId, 'PRIVACY_POLICY', 'privacy-capstone-v1', true,
				        'assessment-test-consent-0001', :requestHash, now())
				""").param("id", UUID.randomUUID()).param("userId", userId)
				.param("requestHash", "0".repeat(64)).update();
		return userId;
	}

	private String completeBody(int itemNine) {
		var values = new int[] { 1, 1, 1, 1, 1, 1, 1, 0, itemNine };
		return completeBody(values);
	}

	private String completeBody(int[] values) {
		var answerJson = new StringBuilder();
		for (var index = 0; index < values.length; index++) {
			if (index > 0) {
				answerJson.append(',');
			}
			answerJson.append("{\"questionId\":\"11000000-0000-0000-0000-")
					.append(String.format("%012d", index + 1)).append("\",\"value\":").append(values[index]).append('}');
		}
		return "{\"questionnaireDefinitionId\":\"" + DEFINITION_ID
				+ "\",\"privacyPolicyVersion\":\"privacy-capstone-v1\",\"privacyDisclosureAcknowledged\":true,\"answers\":["
				+ answerJson + "]}";
	}

	private UUID submit(UUID userId, String key, int[] values) throws Exception {
		var submitted = mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(completeBody(values)))
				.andExpect(status().isCreated()).andReturn();
		return UUID.fromString(json(submitted).get("assessmentId").asText());
	}

	private void setSubmittedAt(UUID assessmentId, String submittedAt) {
		jdbc.sql("update assessment_submission set submitted_at = :submittedAt where id = :id")
				.param("submittedAt", OffsetDateTime.parse(submittedAt)).param("id", assessmentId).update();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID userId) {
		return jwt().jwt(token -> token.subject(userId.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private String incompleteBody() {
		return "{\"questionnaireDefinitionId\":\"" + DEFINITION_ID
				+ "\",\"privacyPolicyVersion\":\"privacy-capstone-v1\",\"privacyDisclosureAcknowledged\":true,"
				+ "\"answers\":[{\"questionId\":\"11000000-0000-0000-0000-000000000001\",\"value\":0}]}";
	}

	private String unkeyedRequestHash(int itemNine) throws Exception {
		var values = new int[] { 1, 1, 1, 1, 1, 1, 1, 0, itemNine };
		var canonical = new StringBuilder(DEFINITION_ID.toString()).append("|privacy-capstone-v1|true");
		for (var index = 0; index < values.length; index++) {
			canonical.append('|').append("11000000-0000-0000-0000-")
					.append(String.format("%012d", index + 1)).append(':').append(values[index]);
		}
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
	}

	private JsonNode json(org.springframework.test.web.servlet.MvcResult result) throws Exception {
		return objectMapper.readTree(result.getResponse().getContentAsString());
	}

	private MvcResult submitAfterSignal(UUID userId, CountDownLatch ready, CountDownLatch start) throws Exception {
		ready.countDown();
		if (!start.await(10, TimeUnit.SECONDS)) {
			throw new IllegalStateException("Concurrent assessment start signal timed out");
		}
		return mvc.perform(post("/api/v1/assessments")
				.with(jwt().jwt(token -> token.subject(userId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER")))
				.header("Idempotency-Key", "assessment-concurrent-001")
				.contentType(MediaType.APPLICATION_JSON).content(completeBody(1))).andReturn();
	}
}
