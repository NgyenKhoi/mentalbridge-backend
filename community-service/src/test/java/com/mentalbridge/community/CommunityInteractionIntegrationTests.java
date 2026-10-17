package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

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
class CommunityInteractionIntegrationTests extends CommunityTestProperties {

	private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000051");
	private static final UUID VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000052");
	private static final UUID OTHER_VIEWER = UUID.fromString("00000000-0000-0000-0000-000000000053");

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcClient jdbc;

	@BeforeEach
	void cleanBusinessData() {
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
	void reactionReplacementAndRemovalAreIdempotentAndCountExactlyOnce() throws Exception {
		var postId = createPost();

		putReaction(postId, VIEWER, "SUPPORT").andExpect(status().isOk())
				.andExpect(jsonPath("$.reaction").value("SUPPORT"));
		putReaction(postId, VIEWER, "SUPPORT").andExpect(status().isOk());
		putReaction(postId, VIEWER, "RELATE").andExpect(status().isOk())
				.andExpect(jsonPath("$.reaction").value("RELATE"));

		assertThat(reactionCount(postId)).isOne();
		assertThat(jdbc.sql("select count(*) from community_post_reaction where post_id = :postId")
				.param("postId", postId).query(Long.class).single()).isOne();
		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(VIEWER)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.counts.reactions").value(1))
				.andExpect(jsonPath("$.viewerState.reaction").value("RELATE"));

		mvc.perform(delete("/api/v1/community/posts/{postId}/reaction", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/community/posts/{postId}/reaction", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
		assertThat(reactionCount(postId)).isZero();
	}

	@Test
	void unsupportedReactionIsRejectedWithoutChangingThePost() throws Exception {
		var postId = createPost();

		putReaction(postId, VIEWER, "LIKE").andExpect(status().isBadRequest());

		assertThat(reactionCount(postId)).isZero();
		assertThat(jdbc.sql("select count(*) from community_post_reaction").query(Long.class).single()).isZero();
	}

	@Test
	void bookmarkAndViewerStateRemainPrivateToTheOwner() throws Exception {
		var postId = createPost();
		mvc.perform(put("/api/v1/community/posts/{postId}/bookmark", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
		mvc.perform(put("/api/v1/community/posts/{postId}/bookmark", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/community/feed").with(user(VIEWER))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].viewerState.bookmarked").value(true));
		mvc.perform(get("/api/v1/community/feed").with(user(OTHER_VIEWER))).andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].viewerState.bookmarked").value(false))
				.andExpect(jsonPath("$.items[0].viewerState.reaction").isEmpty());
		assertThat(jdbc.sql("select count(*) from community_post_bookmark where post_id = :postId")
				.param("postId", postId).query(Long.class).single()).isOne();

		mvc.perform(delete("/api/v1/community/posts/{postId}/bookmark", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/community/posts/{postId}/bookmark", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
	}

	@Test
	void concurrentRetryKeepsOneLogicalReactionAndOneCountIncrement() throws Exception {
		var postId = createPost();
		mvc.perform(put("/api/v1/community/posts/{postId}/bookmark", postId).with(user(VIEWER)))
				.andExpect(status().isNoContent());
		var commands = new ArrayList<Callable<Integer>>();
		for (var index = 0; index < 12; index++) {
			commands.add(() -> putReaction(postId, VIEWER, "THANK_YOU").andReturn().getResponse().getStatus());
		}
		try (var executor = Executors.newFixedThreadPool(6)) {
			var results = executor.invokeAll(commands);
			for (var result : results) {
				assertThat(result.get()).isEqualTo(200);
			}
		}
		assertThat(reactionCount(postId)).isOne();
		assertThat(jdbc.sql("select count(*) from community_post_reaction where post_id = :postId")
				.param("postId", postId).query(Long.class).single()).isOne();
	}

	@Test
	void hiddenOrRemovedPostsCannotReceiveNewInteraction() throws Exception {
		var postId = createPost();
		jdbc.sql("update community_post set state = 'MODERATION_HIDDEN' where id = :postId")
				.param("postId", postId).update();

		putReaction(postId, VIEWER, "SUPPORT").andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMUNITY_POST_NOT_FOUND"));
		mvc.perform(put("/api/v1/community/posts/{postId}/bookmark", postId).with(user(VIEWER)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMUNITY_POST_NOT_FOUND"));
		assertThat(jdbc.sql("select count(*) from community_post_reaction").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from community_post_bookmark").query(Long.class).single()).isZero();
	}

	private org.springframework.test.web.servlet.ResultActions putReaction(UUID postId, UUID subject, String reaction)
			throws Exception {
		return mvc.perform(put("/api/v1/community/posts/{postId}/reaction", postId).with(user(subject))
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("reaction", reaction))));
	}

	private UUID createPost() throws Exception {
		var response = mvc.perform(post("/api/v1/community/posts").with(user(AUTHOR))
				.header("Idempotency-Key", "interaction-post-create-0001")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
						"content", "Một câu chuyện cần những lời đồng hành.", "topics", List.of("MY_STORY"),
						"mediaIds", List.of())))).andExpect(status().isCreated()).andReturn().getResponse()
				.getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("postId").asText());
	}

	private int reactionCount(UUID postId) {
		return jdbc.sql("select reaction_count from community_post where id = :postId").param("postId", postId)
				.query(Integer.class).single();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
