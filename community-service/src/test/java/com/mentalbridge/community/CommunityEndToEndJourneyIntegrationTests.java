package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityEndToEndJourneyIntegrationTests extends CommunityTestProperties {

	private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000618");
	private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000619");
	private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000620");
	private static final UUID RESOURCE = UUID.fromString("30000000-0000-4000-8000-000000000618");
	private static final String PRIVATE_POST_BODY = "PRIVATE_MB618_POST_BODY";
	private static final String PRIVATE_COMMENT_BODY = "PRIVATE_MB618_COMMENT_BODY";

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcClient jdbc;

	@BeforeEach
	void cleanBusinessData() {
		jdbc.sql("update community_topic set active = true").update();
		jdbc.sql("delete from community_interaction_outbox").update();
		jdbc.sql("delete from community_post_bookmark").update();
		jdbc.sql("delete from community_post_reaction").update();
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
	void cleanAfterEach() {
		cleanBusinessData();
	}

	@Test
	void fullPeerSupportJourneyPreservesPrivacyIdempotencyAndFailClosedModeration() throws Exception {
		createProfile(AUTHOR, "Mầm Xanh", "LEAF");
		createProfile(VIEWER, "Ánh Ban Mai", "SUNRISE");

		var postRequest = new LinkedHashMap<String, Object>();
		postRequest.put("content", PRIVATE_POST_BODY);
		postRequest.put("topics", List.of("HELPFUL_RESOURCE"));
		postRequest.put("mediaIds", List.of());
		postRequest.put("authorMode", "ANONYMOUS");
		postRequest.put("resourceId", RESOURCE);
		postRequest.put("sensitiveContentWarning", "SENSITIVE_CONTENT");
		var postId = createPost(postRequest, "mb618-post-create-0001");
		assertThat(createPost(postRequest, "mb618-post-create-0001")).isEqualTo(postId);
		assertThat(jdbc.sql("select count(*) from community_post").query(Long.class).single()).isOne();

		mvc.perform(get("/api/v1/community/feed").with(user(VIEWER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].postId").value(postId.toString()))
				.andExpect(jsonPath("$.items[0].author.state").value("ANONYMOUS"))
				.andExpect(jsonPath("$.items[0].author.communityProfileId").isEmpty())
				.andExpect(jsonPath("$.items[0].resourceAttachment.resourceId").value(RESOURCE.toString()))
				.andExpect(jsonPath("$.items[0].sensitiveContentWarning").value("SENSITIVE_CONTENT"));

		var authorProfileId = profileId(AUTHOR);
		mvc.perform(put("/api/v1/community/blocks/{profileId}", authorProfileId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(VIEWER)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/community/blocks/{profileId}", authorProfileId).with(user(VIEWER)))
				.andExpect(status().isNoContent());

		mvc.perform(put("/api/v1/community/hidden-content/POST/{postId}", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(VIEWER)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/community/hidden-content/POST/{postId}", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());

		var commentId = createComment(postId, VIEWER, "mb618-comment-create-0001", PRIVATE_COMMENT_BODY, null);
		assertThat(createComment(postId, VIEWER, "mb618-comment-create-0001", PRIVATE_COMMENT_BODY, null))
				.isEqualTo(commentId);
		createComment(postId, AUTHOR, "mb618-reply-create-0001", "Mình cảm ơn bạn đã đồng hành.", commentId);
		for (var attempt = 0; attempt < 2; attempt++) {
			mvc.perform(put("/api/v1/community/posts/{postId}/reaction", postId).with(user(VIEWER))
					.contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"SUPPORT\"}"))
					.andExpect(status().isOk());
			mvc.perform(put("/api/v1/community/posts/{postId}/bookmark", postId).with(user(VIEWER)))
					.andExpect(status().isNoContent());
		}
		mvc.perform(get("/api/v1/community/saved-posts").with(user(VIEWER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].postId").value(postId.toString()));
		mvc.perform(get("/api/v1/community/saved-posts").with(user(AUTHOR)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());

		var report = Map.of("targetType", "POST", "targetId", postId, "reason", "HARASSMENT", "details",
				"Nội dung cần được đội ngũ kiểm duyệt xem xét.");
		for (var attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post("/api/v1/community/reports").with(user(VIEWER))
					.header("Idempotency-Key", "mb618-report-create-0001")
					.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(report)))
					.andExpect(status().isAccepted());
		}
		var caseId = jdbc.sql("select id from community_moderation_case where target_id = :postId")
				.param("postId", postId).query(UUID.class).single();
		mvc.perform(get("/api/v1/community/admin/moderation-cases").param("state", "OPEN").with(admin()))
				.andExpect(status().isOk()).andExpect(jsonPath("$[0].caseId").value(caseId.toString()));
		for (var attempt = 0; attempt < 2; attempt++) {
			mvc.perform(post("/api/v1/community/admin/moderation-cases/{caseId}/actions", caseId).with(admin())
					.header("Idempotency-Key", "mb618-moderation-hide-0001")
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"action\":\"HIDE\",\"reasonCode\":\"CONFIRMED_HARASSMENT\"}"))
					.andExpect(status().isCreated());
		}

		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(VIEWER)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/community/feed").with(user(VIEWER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(get("/api/v1/community/saved-posts").with(user(VIEWER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		mvc.perform(put("/api/v1/community/posts/{postId}/reaction", postId).with(user(VIEWER))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reaction\":\"RELATE\"}"))
				.andExpect(status().isNotFound());

		assertThat(jdbc.sql("select comment_count from community_post where id = :postId")
				.param("postId", postId).query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select reaction_count from community_post where id = :postId")
				.param("postId", postId).query(Integer.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from community_post_bookmark").query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from community_report").query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from community_moderation_action").query(Long.class).single()).isOne();

		var eventPayloads = jdbc.sql("select event_payload::text from community_interaction_outbox order by occurred_at, id")
				.query(String.class).list().stream().map(this::readTree).toList();
		assertThat(eventPayloads).hasSize(3);
		assertThat(eventPayloads).extracting(event -> event.path("interactionKind").asText())
				.containsExactlyInAnyOrder("COMMENT", "REPLY", "SUPPORT");
		assertThat(eventPayloads.toString()).doesNotContain(PRIVATE_POST_BODY, PRIVATE_COMMENT_BODY, "displayName",
				"email", "assessment", "journal", "supportPlan", "aiAnalysis");
	}

	private void createProfile(UUID subject, String displayName, String avatarPreset) throws Exception {
		mvc.perform(put("/api/v1/community/profile").with(user(subject)).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("displayName", displayName, "avatarPreset", avatarPreset))))
				.andExpect(status().isCreated());
	}

	private UUID createPost(Map<String, Object> request, String idempotencyKey) throws Exception {
		var response = mvc.perform(post("/api/v1/community/posts").with(user(AUTHOR))
				.header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(request))).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("postId").asText());
	}

	private UUID createComment(UUID postId, UUID subject, String idempotencyKey, String content, UUID parentCommentId)
			throws Exception {
		var body = objectMapper.createObjectNode().put("content", content);
		if (parentCommentId == null) {
			body.putNull("parentCommentId");
		}
		else {
			body.put("parentCommentId", parentCommentId.toString());
		}
		var response = mvc.perform(post("/api/v1/community/posts/{postId}/comments", postId).with(user(subject))
				.header("Idempotency-Key", idempotencyKey).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body))).andExpect(status().isCreated()).andReturn()
				.getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("commentId").asText());
	}

	private UUID profileId(UUID subject) {
		return jdbc.sql("select id from community_profile where account_subject = :subject").param("subject", subject)
				.query(UUID.class).single();
	}

	private JsonNode readTree(String value) {
		try {
			return objectMapper.readTree(value);
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private RequestPostProcessor admin() {
		return jwt().jwt(token -> token.subject(ADMIN.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}
}
