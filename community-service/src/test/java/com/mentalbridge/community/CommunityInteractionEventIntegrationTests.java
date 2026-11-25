package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Set;
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

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityInteractionEventIntegrationTests extends CommunityTestProperties {

	private static final UUID POST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000111");
	private static final UUID COMMENTER = UUID.fromString("00000000-0000-0000-0000-000000000112");
	private static final UUID REPLIER = UUID.fromString("00000000-0000-0000-0000-000000000113");
	private static final Set<String> EVENT_FIELDS = Set.of("eventId", "schemaVersion", "actorCommunityProfileId",
			"targetOwnerRoutingReference", "targetType", "targetId", "interactionKind", "occurredAt", "deepLink");

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcClient jdbc;

	@BeforeEach
	void cleanBusinessData() {
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
	void commentsRepliesAndReactionsCommitOneMinimizedFactPerLogicalInteraction() throws Exception {
		var postId = createPost();
		var rootCommentId = createComment(postId, COMMENTER, "event-root-comment-0001",
				"PRIVATE_ROOT_BODY_SHOULD_NEVER_LEAVE_COMMUNITY", null);
		createComment(postId, COMMENTER, "event-root-comment-0001",
				"PRIVATE_ROOT_BODY_SHOULD_NEVER_LEAVE_COMMUNITY", null);
		createComment(postId, REPLIER, "event-reply-comment-0001",
				"PRIVATE_REPLY_BODY_SHOULD_NEVER_LEAVE_COMMUNITY", rootCommentId);

		putReaction(postId, COMMENTER, "SUPPORT");
		putReaction(postId, COMMENTER, "SUPPORT");
		putReaction(postId, COMMENTER, "RELATE");
		mvc.perform(delete("/api/v1/community/posts/{postId}/reaction", postId).with(user(COMMENTER)))
				.andExpect(status().isNoContent());
		putReaction(postId, COMMENTER, "THANK_YOU");
		putReaction(postId, POST_OWNER, "SUPPORT");
		mvc.perform(put("/api/v1/community/posts/{postId}/bookmark", postId).with(user(REPLIER)))
				.andExpect(status().isNoContent());

		var events = eventPayloads();
		assertThat(events).hasSize(3);
		var root = event(events, "COMMENT");
		var reply = event(events, "REPLY");
		var reaction = event(events, "SUPPORT");
		assertCommonBoundary(root, postId);
		assertCommonBoundary(reply, postId);
		assertCommonBoundary(reaction, postId);
		assertThat(root.path("actorCommunityProfileId").asText()).isEqualTo(profileId(COMMENTER).toString());
		assertThat(root.path("targetOwnerRoutingReference").asText()).isEqualTo(POST_OWNER.toString());
		assertThat(root.path("targetType").asText()).isEqualTo("POST");
		assertThat(root.path("targetId").asText()).isEqualTo(postId.toString());
		assertThat(reply.path("actorCommunityProfileId").asText()).isEqualTo(profileId(REPLIER).toString());
		assertThat(reply.path("targetOwnerRoutingReference").asText()).isEqualTo(COMMENTER.toString());
		assertThat(reply.path("targetType").asText()).isEqualTo("COMMENT");
		assertThat(reply.path("targetId").asText()).isEqualTo(rootCommentId.toString());
		assertThat(reaction.path("actorCommunityProfileId").asText()).isEqualTo(profileId(COMMENTER).toString());
		assertThat(reaction.path("targetOwnerRoutingReference").asText()).isEqualTo(POST_OWNER.toString());
		assertThat(reaction.path("targetType").asText()).isEqualTo("POST");
		assertThat(reaction.path("targetId").asText()).isEqualTo(postId.toString());
		assertThat(events.toString()).doesNotContain("PRIVATE_ROOT_BODY", "PRIVATE_REPLY_BODY", "content", "media",
				"email", "displayName", "assessment", "journal", "supportPlan", "aiAnalysis");
	}

	private void assertCommonBoundary(JsonNode event, UUID postId) {
		assertThat(event.propertyStream().map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet()))
				.isEqualTo(EVENT_FIELDS);
		assertThat(event.path("eventId").asText()).isNotBlank();
		assertThat(event.path("schemaVersion").asText()).isEqualTo("1.0");
		assertThat(event.path("occurredAt").asText()).isNotBlank();
		assertThat(event.path("deepLink").propertyStream().map(Map.Entry::getKey).toList())
				.containsExactlyInAnyOrder("kind", "postId");
		assertThat(event.path("deepLink").path("kind").asText()).isEqualTo("COMMUNITY_POST");
		assertThat(event.path("deepLink").path("postId").asText()).isEqualTo(postId.toString());
	}

	private JsonNode event(List<JsonNode> events, String interactionKind) {
		return events.stream().filter(event -> interactionKind.equals(event.path("interactionKind").asText()))
				.findFirst().orElseThrow();
	}

	private List<JsonNode> eventPayloads() {
		return jdbc.sql("select event_payload::text from community_interaction_outbox order by occurred_at, id")
				.query(String.class).list().stream().map(this::readTree).toList();
	}

	private JsonNode readTree(String value) {
		try {
			return objectMapper.readTree(value);
		}
		catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private UUID createPost() throws Exception {
		var response = mvc.perform(post("/api/v1/community/posts").with(user(POST_OWNER))
				.header("Idempotency-Key", "event-post-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
						"content", "PRIVATE_POST_BODY_SHOULD_NEVER_LEAVE_COMMUNITY", "topics", List.of("MY_STORY"),
						"mediaIds", List.of())))).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("postId").asText());
	}

	private UUID createComment(UUID postId, UUID subject, String idempotencyKey, String content,
			UUID parentCommentId) throws Exception {
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

	private void putReaction(UUID postId, UUID subject, String reaction) throws Exception {
		mvc.perform(put("/api/v1/community/posts/{postId}/reaction", postId).with(user(subject))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reaction", reaction))))
				.andExpect(status().isOk());
	}

	private UUID profileId(UUID subject) {
		return jdbc.sql("select id from community_profile where account_subject = :subject")
				.param("subject", subject).query(UUID.class).single();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
