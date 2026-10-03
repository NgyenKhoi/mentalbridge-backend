package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
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
class CommunityCommentIntegrationTests extends CommunityTestProperties {

	private static final UUID POST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000021");
	private static final UUID COMMENTER = UUID.fromString("00000000-0000-0000-0000-000000000022");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000023");

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void cleanBusinessData() {
		jdbc.sql("delete from community_comment_revision").update();
		jdbc.sql("delete from community_comment").update();
		jdbc.sql("delete from community_block").update();
		jdbc.sql("delete from community_media").update();
		jdbc.sql("delete from community_post_topic").update();
		jdbc.sql("delete from community_post").update();
		jdbc.sql("delete from community_profile").update();
	}

	@Test
	void createsOneLevelRepliesIdempotentlyAndKeepsThePostCountConsistent() throws Exception {
		var postId = createPost();
		var root = createComment(postId, COMMENTER, "comment-create-root-0001", "Mình đang lắng nghe bạn.", null)
				.andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
				.andReturn().getResponse().getContentAsString();
		var rootId = UUID.fromString(objectMapper.readTree(root).path("commentId").asText());

		createComment(postId, COMMENTER, "comment-create-root-0001", "Mình đang lắng nghe bạn.", null)
				.andExpect(status().isCreated()).andExpect(jsonPath("$.commentId").value(rootId.toString()));
		var reply = createComment(postId, OTHER, "comment-create-reply-0001", "Cảm ơn bạn đã chia sẻ.", rootId)
				.andExpect(status().isCreated()).andExpect(jsonPath("$.parentCommentId").value(rootId.toString()))
				.andReturn().getResponse().getContentAsString();
		var replyId = UUID.fromString(objectMapper.readTree(reply).path("commentId").asText());

		createComment(postId, POST_OWNER, "comment-nested-reply-0001", "Không được lồng thêm.", replyId)
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_NOT_FOUND"));

		assertThat(jdbc.sql("select comment_count from community_post where id = :id")
				.param("id", postId).query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from community_comment_revision")
				.query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void listsChronologicallyWithAnOpaqueCursorAndOwnerDeletedTombstones() throws Exception {
		var postId = createPost();
		var first = createComment(postId, COMMENTER, "comment-page-first-0001", "Bình luận đầu tiên", null)
				.andReturn().getResponse().getContentAsString();
		var firstId = UUID.fromString(objectMapper.readTree(first).path("commentId").asText());
		createComment(postId, OTHER, "comment-page-second-0001", "Bình luận thứ hai", firstId);
		createComment(postId, POST_OWNER, "comment-page-third-0001", "Bình luận thứ ba", null);

		var page = mvc.perform(get("/api/v1/community/posts/{postId}/comments", postId)
				.param("limit", "2").with(user(COMMENTER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[0].commentId").value(firstId.toString()))
				.andExpect(jsonPath("$.hasMore").value(true))
				.andReturn().getResponse().getContentAsString();
		var cursor = objectMapper.readTree(page).path("nextCursor").asText();
		mvc.perform(get("/api/v1/community/posts/{postId}/comments", postId)
				.param("limit", "2").param("cursor", cursor).with(user(COMMENTER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.hasMore").value(false));

		mvc.perform(delete("/api/v1/community/comments/{commentId}", firstId)
				.header("If-Match", "\"0\"").with(user(COMMENTER))).andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/community/posts/{postId}/comments", postId).with(user(COMMENTER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].state").value("OWNER_DELETED"))
				.andExpect(jsonPath("$.items[0].content").value("Bình luận đã được người viết xóa."));
		assertThat(jdbc.sql("select comment_count from community_post where id = :id")
				.param("id", postId).query(Integer.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select count(*) from community_comment_revision where comment_id = :id")
				.param("id", firstId).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void editRequiresOwnershipAndTheExactVersionWhileRetainingRevisionHistory() throws Exception {
		var postId = createPost();
		var created = createComment(postId, COMMENTER, "comment-edit-owner-0001", "Nội dung ban đầu", null)
				.andReturn().getResponse().getContentAsString();
		var commentId = UUID.fromString(objectMapper.readTree(created).path("commentId").asText());

		mvc.perform(patch("/api/v1/community/comments/{commentId}", commentId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(commentBody("Không thể sửa"))
				.with(user(OTHER))).andExpect(status().isNotFound());
		mvc.perform(patch("/api/v1/community/comments/{commentId}", commentId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(commentBody("Nội dung đã sửa"))
				.with(user(COMMENTER))).andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.content").value("Nội dung đã sửa"));
		mvc.perform(patch("/api/v1/community/comments/{commentId}", commentId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(commentBody("Ghi đè cũ"))
				.with(user(COMMENTER))).andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("COMMUNITY_COMMENT_VERSION_MISMATCH"));

		assertThat(jdbc.sql("select content_snapshot from community_comment_revision where comment_id = :id order by comment_version")
				.param("id", commentId).query(String.class).list())
				.containsExactly("Nội dung ban đầu", "Nội dung đã sửa");
	}

	@Test
	void blockedRelationshipsAndHiddenPostsFailClosedForReadsAndCommands() throws Exception {
		var postId = createPost();
		createComment(postId, COMMENTER, "comment-blocked-owner-0001", "Nội dung hỗ trợ", null)
				.andExpect(status().isCreated());
		var postOwnerProfile = profileId(POST_OWNER);
		var commenterProfile = profileId(COMMENTER);
		jdbc.sql("insert into community_block (blocker_profile_id, blocked_profile_id, created_at) values (:a, :b, now())")
				.param("a", postOwnerProfile).param("b", commenterProfile).update();

		mvc.perform(get("/api/v1/community/posts/{postId}/comments", postId).with(user(COMMENTER)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMUNITY_POST_NOT_FOUND"));
		createComment(postId, COMMENTER, "comment-blocked-create-0001", "Không được đăng", null)
				.andExpect(status().isNotFound());

		jdbc.sql("delete from community_block").update();
		jdbc.sql("update community_post set state = 'MODERATION_HIDDEN' where id = :id")
				.param("id", postId).update();
		mvc.perform(get("/api/v1/community/posts/{postId}/comments", postId).with(user(OTHER)))
				.andExpect(status().isNotFound());
	}

	private UUID createPost() throws Exception {
		var body = objectMapper.writeValueAsString(Map.of(
				"content", "Một câu chuyện cần sự đồng hành.",
				"topics", List.of("MY_STORY"),
				"mediaIds", List.of()));
		var response = mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "comment-test-post-0001")
				.contentType(MediaType.APPLICATION_JSON).content(body).with(user(POST_OWNER)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("postId").asText());
	}

	private org.springframework.test.web.servlet.ResultActions createComment(UUID postId, UUID subject, String key,
			String content, UUID parentCommentId) throws Exception {
		var body = objectMapper.createObjectNode().put("content", content);
		if (parentCommentId == null) {
			body.putNull("parentCommentId");
		}
		else {
			body.put("parentCommentId", parentCommentId.toString());
		}
		return mvc.perform(post("/api/v1/community/posts/{postId}/comments", postId)
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(body))
				.with(user(subject)));
	}

	private String commentBody(String content) throws Exception {
		return objectMapper.writeValueAsString(Map.of("content", content));
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
