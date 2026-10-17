package com.mentalbridge.community.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.community.CommunityTestProperties;
import com.mentalbridge.community.TestcontainersConfiguration;
import com.mentalbridge.community.notification.CommunityInteractionEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityInteractionEventAtomicityIntegrationTests extends CommunityTestProperties {

	private static final UUID POST_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000131");
	private static final UUID COMMENTER = UUID.fromString("00000000-0000-0000-0000-000000000132");

	@Autowired CommunityCommentService comments;
	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcClient jdbc;
	@MockitoBean CommunityInteractionEventPublisher publisher;

	@BeforeEach
	void cleanBusinessData() {
		jdbc.sql("delete from community_interaction_outbox").update();
		jdbc.sql("delete from community_post_bookmark").update();
		jdbc.sql("delete from community_post_reaction").update();
		jdbc.sql("delete from community_comment_revision").update();
		jdbc.sql("delete from community_comment").update();
		jdbc.sql("delete from community_block").update();
		jdbc.sql("delete from community_media").update();
		jdbc.sql("delete from community_post_topic").update();
		jdbc.sql("delete from community_post").update();
		jdbc.sql("delete from community_profile").update();
	}

	@Test
	void requiredOutboxFailureRollsBackTheInteractionTransaction() throws Exception {
		var postId = createPost();
		doThrow(new IllegalStateException("outbox unavailable")).when(publisher).publish(anyString(), any());

		assertThatThrownBy(() -> comments.create(COMMENTER, postId, "event-atomic-comment-0001",
				new CommunityCommentRequests.CreateCommentRequest("This command must roll back", null)))
				.isInstanceOf(IllegalStateException.class);

		assertThat(jdbc.sql("select count(*) from community_comment").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from community_comment_revision").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select comment_count from community_post where id = :postId")
				.param("postId", postId).query(Integer.class).single()).isZero();
	}

	private UUID createPost() throws Exception {
		var response = mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "event-atomic-post-0001")
				.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of(
						"content", "A post used to verify transaction atomicity", "topics", List.of("MY_STORY"),
						"mediaIds", List.of())))
				.with(jwt().jwt(token -> token.subject(POST_OWNER.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return UUID.fromString(objectMapper.readTree(response).path("postId").asText());
	}
}
