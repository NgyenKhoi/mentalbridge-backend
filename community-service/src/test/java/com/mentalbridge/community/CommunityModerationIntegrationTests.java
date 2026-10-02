package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityModerationIntegrationTests extends CommunityTestProperties {

	private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000041");
	private static final UUID REPORTER = UUID.fromString("00000000-0000-0000-0000-000000000042");
	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000043");

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcClient jdbc;

	@BeforeEach
	void cleanBusinessData() {
		jdbc.sql("delete from community_access_restriction").update();
		jdbc.sql("delete from community_moderation_action").update();
		jdbc.sql("delete from community_report").update();
		jdbc.sql("delete from community_moderation_case").update();
		jdbc.sql("delete from community_content_hide").update();
		jdbc.sql("delete from community_comment_revision").update();
		jdbc.sql("delete from community_comment").update();
		jdbc.sql("delete from community_block").update();
		jdbc.sql("delete from community_media").update();
		jdbc.sql("delete from community_post_topic").update();
		jdbc.sql("delete from community_post").update();
		jdbc.sql("delete from community_profile").update();
	}

	@AfterEach
	void cleanModerationDataAfterEach() {
		cleanBusinessData();
	}

	@Test
	void reportRetryCreatesOneHighPriorityCaseAndRejectsConflictingReuse() throws Exception {
		var postId = createPost();
		var report = Map.of("targetType", "POST", "targetId", postId, "reason",
				"SELF_HARM_OR_CRISIS_CONCERN", "details", "Nội dung cần được người kiểm duyệt xem xét.");
		for (var attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post("/api/v1/community/reports").with(user(REPORTER))
					.header("Idempotency-Key", "moderation-report-retry-0001")
					.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(report)))
					.andExpect(status().isAccepted());
		}
		assertThat(jdbc.sql("select count(*) from community_report").query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select priority from community_moderation_case").query(String.class).single()).isEqualTo("HIGH");

		mvc.perform(post("/api/v1/community/reports").with(user(REPORTER))
				.header("Idempotency-Key", "moderation-report-retry-0001")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
						"targetType", "POST", "targetId", postId, "reason", "SPAM", "details", "Khác"))))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void hideAndBlockAreOwnerScopedAndFailClosed() throws Exception {
		var postId = createPost();
		var authorProfile = profileId(AUTHOR);
		mvc.perform(put("/api/v1/community/hidden-content/POST/{id}", postId).with(user(REPORTER)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/community/posts/{id}", postId).with(user(REPORTER)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/community/posts/{id}", postId).with(user(AUTHOR))).andExpect(status().isOk());
		mvc.perform(delete("/api/v1/community/hidden-content/POST/{id}", postId).with(user(REPORTER)))
				.andExpect(status().isNoContent());
		mvc.perform(put("/api/v1/community/blocks/{id}", authorProfile).with(user(REPORTER)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/community/posts/{id}", postId).with(user(REPORTER)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/community/blocks/{id}", authorProfile).with(user(REPORTER)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/community/posts/{id}", postId).with(user(REPORTER))).andExpect(status().isOk());
	}

	@Test
	void adminModerationRecordsAuditAndRestrictsCommunityWithoutClinicalSideEffects() throws Exception {
		var postId = createPost();
		report(postId, "SPAM", "moderation-report-action-0001");
		report(postId, "SELF_HARM_OR_CRISIS_CONCERN", "moderation-report-action-0002");
		var caseId = jdbc.sql("select id from community_moderation_case").query(UUID.class).single();
		assertThat(jdbc.sql("select count(*) from community_report").query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select priority from community_moderation_case").query(String.class).single())
				.isEqualTo("NORMAL");
		mvc.perform(get("/api/v1/community/admin/moderation-cases").with(user(REPORTER)))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/community/admin/moderation-cases").param("state", "OPEN").with(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].caseId").value(caseId.toString()))
				.andExpect(jsonPath("$[0].reportContexts[0]").value("Xem xét"))
				.andExpect(jsonPath("$[0].evidence.content").value("Một câu chuyện cần không gian an toàn."));
		mvc.perform(post("/api/v1/community/admin/moderation-cases/{id}/actions", caseId).with(admin())
				.header("Idempotency-Key", "moderation-action-hide-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"HIDE\",\"reasonCode\":\"CONFIRMED_SPAM\"}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.actions[0].priorState").value("ACTIVE"))
				.andExpect(jsonPath("$.actions[0].resultingState").value("MODERATION_HIDDEN"));
		mvc.perform(get("/api/v1/community/posts/{id}", postId).with(user(REPORTER))).andExpect(status().isNotFound());
		assertThat(jdbc.sql("select count(*) from community_moderation_action").query(Long.class).single()).isOne();

		mvc.perform(post("/api/v1/community/admin/moderation-cases/{id}/actions", caseId).with(admin())
				.header("Idempotency-Key", "moderation-action-restrict-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"action\":\"RESTRICT_COMMUNITY_ACCESS\",\"reasonCode\":\"REPEATED_ABUSE\"}"))
				.andExpect(status().isCreated());
		mvc.perform(get("/api/v1/community/feed").with(user(AUTHOR))).andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COMMUNITY_ACCESS_UNAVAILABLE"));
		act(caseId, "moderation-action-restore-0001", "RESTORE", "CONTENT_APPEAL_ACCEPTED");
		assertThat(jdbc.sql("select lifted_at is null from community_access_restriction where profile_id = :profile")
				.param("profile", profileId(AUTHOR)).query(Boolean.class).single()).isTrue();
		mvc.perform(get("/api/v1/community/feed").with(user(AUTHOR))).andExpect(status().isForbidden());
	}

	@Test
	void hidingAndRestoringACommentKeepsTheActiveCountAndRevisionAuditConsistent() throws Exception {
		var postId = createPost();
		var commentId = createComment(postId);
		reportTarget("COMMENT", commentId, "HARASSMENT", "moderation-comment-report-0001");
		var caseId = jdbc.sql("select id from community_moderation_case where target_type = 'COMMENT'")
				.query(UUID.class).single();
		var initialPostVersion = jdbc.sql("select version from community_post where id = :id").param("id", postId)
				.query(Long.class).single();

		act(caseId, "moderation-comment-hide-0001", "HIDE", "CONFIRMED_HARASSMENT");
		assertThat(jdbc.sql("select comment_count from community_post where id = :id").param("id", postId)
				.query(Integer.class).single()).isZero();
		assertThat(jdbc.sql("select version from community_post where id = :id").param("id", postId)
				.query(Long.class).single()).isEqualTo(initialPostVersion + 1);
		act(caseId, "moderation-comment-restore-0001", "RESTORE", "APPEAL_ACCEPTED");
		assertThat(jdbc.sql("select comment_count from community_post where id = :id").param("id", postId)
				.query(Integer.class).single()).isOne();
		assertThat(jdbc.sql("select version from community_post where id = :id").param("id", postId)
				.query(Long.class).single()).isEqualTo(initialPostVersion + 2);
		assertThat(jdbc.sql("select count(*) from community_comment_revision where comment_id = :id and change_type = 'MODERATED'")
				.param("id", commentId).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from community_moderation_action where case_id = :id")
				.param("id", caseId).query(Long.class).single()).isEqualTo(2);
	}

	private UUID createPost() throws Exception {
		var response = mvc.perform(post("/api/v1/community/posts").with(user(AUTHOR))
				.header("Idempotency-Key", "moderation-post-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
						"content", "Một câu chuyện cần không gian an toàn.", "topics", List.of("MY_STORY"),
						"mediaIds", List.of())))).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("postId").asText());
	}

	private void report(UUID targetId, String reason, String key) throws Exception {
		reportTarget("POST", targetId, reason, key);
	}

	private void reportTarget(String targetType, UUID targetId, String reason, String key) throws Exception {
		mvc.perform(post("/api/v1/community/reports").with(user(REPORTER)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
						"targetType", targetType, "targetId", targetId, "reason", reason, "details", "Xem xét"))))
				.andExpect(status().isAccepted());
	}

	private UUID createComment(UUID postId) throws Exception {
		var body = objectMapper.createObjectNode().put("content", "Mình đang lắng nghe bạn.").putNull("parentCommentId");
		var response = mvc.perform(post("/api/v1/community/posts/{postId}/comments", postId).with(user(REPORTER))
				.header("Idempotency-Key", "moderation-comment-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("commentId").asText());
	}

	private void act(UUID caseId, String key, String action, String reasonCode) throws Exception {
		mvc.perform(post("/api/v1/community/admin/moderation-cases/{id}/actions", caseId).with(admin())
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("action", action, "reasonCode", reasonCode))))
				.andExpect(status().isCreated());
	}

	private UUID profileId(UUID subject) {
		return jdbc.sql("select id from community_profile where account_subject = :subject")
				.param("subject", subject).query(UUID.class).single();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
		return jwt().jwt(token -> token.subject(ADMIN.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}
}
