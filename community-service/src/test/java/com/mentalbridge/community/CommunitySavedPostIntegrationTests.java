package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunitySavedPostIntegrationTests extends CommunityTestProperties {

	private static final Instant NOW = Instant.parse("2026-10-04T12:00:00Z");
	private static final UUID VIEWER_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000161");
	private static final UUID OTHER_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000162");
	private static final UUID UNKNOWN_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000163");
	private static final UUID VIEWER_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000161");
	private static final UUID OTHER_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000162");
	private static final UUID AUTHOR_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000163");
	private static final UUID SECOND_AUTHOR_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000164");
	private static final UUID FIRST_POST = UUID.fromString("20000000-0000-0000-0000-000000000161");
	private static final UUID SECOND_POST = UUID.fromString("20000000-0000-0000-0000-000000000162");
	private static final UUID THIRD_POST = UUID.fromString("20000000-0000-0000-0000-000000000163");

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;
	@Autowired JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		cleanBusinessData();
		profile(VIEWER_PROFILE, VIEWER_SUBJECT, "Người đọc");
		profile(OTHER_PROFILE, OTHER_SUBJECT, "Người đọc khác");
		profile(AUTHOR_PROFILE, UUID.randomUUID(), "Minh An");
		profile(SECOND_AUTHOR_PROFILE, UUID.randomUUID(), "Lan");
	}

	@AfterEach
	void cleanAfterEach() {
		cleanBusinessData();
	}

	@Test
	void savedPostsAreNewestSavedCursorPagedAndPrivateToTheOwner() throws Exception {
		post(FIRST_POST, AUTHOR_PROFILE, "Bài đăng mới nhưng được lưu trước", "ACTIVE", NOW);
		post(SECOND_POST, AUTHOR_PROFILE, "Bài được lưu cùng thời điểm", "ACTIVE", NOW.minusSeconds(120));
		post(THIRD_POST, AUTHOR_PROFILE, "Bài có khóa lớn hơn", "ACTIVE", NOW.minusSeconds(240));
		bookmark(VIEWER_PROFILE, FIRST_POST, NOW.minusSeconds(60));
		bookmark(VIEWER_PROFILE, SECOND_POST, NOW);
		bookmark(VIEWER_PROFILE, THIRD_POST, NOW);
		bookmark(OTHER_PROFILE, FIRST_POST, NOW.plusSeconds(60));

		var first = mvc.perform(get("/api/v1/community/saved-posts").param("limit", "1")
				.with(user(VIEWER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].postId").value(THIRD_POST.toString()))
				.andExpect(jsonPath("$.items[0].viewerState.bookmarked").value(true))
				.andExpect(jsonPath("$.hasMore").value(true))
				.andReturn().getResponse().getContentAsString();
		var cursor = objectMapper.readTree(first).path("nextCursor").asText();

		mvc.perform(get("/api/v1/community/saved-posts").param("limit", "1").param("cursor", cursor)
				.with(user(VIEWER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].postId").value(SECOND_POST.toString()))
				.andExpect(jsonPath("$.hasMore").value(true));
		mvc.perform(get("/api/v1/community/saved-posts").with(user(OTHER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].postId").value(FIRST_POST.toString()));
	}

	@Test
	void savedPostsFailClosedForEveryUnavailableVisibilityState() throws Exception {
		var visible = UUID.fromString("20000000-0000-0000-0000-000000000170");
		var hidden = UUID.fromString("20000000-0000-0000-0000-000000000171");
		var removed = UUID.fromString("20000000-0000-0000-0000-000000000172");
		var deleted = UUID.fromString("20000000-0000-0000-0000-000000000173");
		var personallyHidden = UUID.fromString("20000000-0000-0000-0000-000000000174");
		var blocked = UUID.fromString("20000000-0000-0000-0000-000000000175");
		var reverseBlocked = UUID.fromString("20000000-0000-0000-0000-000000000176");
		post(visible, AUTHOR_PROFILE, "Vẫn có thể đọc", "ACTIVE", NOW);
		post(hidden, AUTHOR_PROFILE, "Không được lộ", "MODERATION_HIDDEN", NOW);
		post(removed, AUTHOR_PROFILE, "Không được lộ", "MODERATION_REMOVED", NOW);
		post(deleted, AUTHOR_PROFILE, "Không được lộ", "OWNER_DELETED", NOW);
		post(personallyHidden, AUTHOR_PROFILE, "Không được lộ", "ACTIVE", NOW);
		post(blocked, SECOND_AUTHOR_PROFILE, "Không được lộ", "ACTIVE", NOW);
		var reverseAuthor = UUID.fromString("10000000-0000-0000-0000-000000000165");
		profile(reverseAuthor, UUID.randomUUID(), "Người đã chặn");
		post(reverseBlocked, reverseAuthor, "Không được lộ", "ACTIVE", NOW);
		for (var postId : new UUID[] { visible, hidden, removed, deleted, personallyHidden, blocked, reverseBlocked }) {
			bookmark(VIEWER_PROFILE, postId, NOW);
		}
		jdbc.sql("insert into community_content_hide (hider_profile_id, target_type, target_id, created_at) values (:viewer, 'POST', :postId, :now)")
				.param("viewer", VIEWER_PROFILE).param("postId", personallyHidden).param("now", dbTime(NOW)).update();
		block(VIEWER_PROFILE, SECOND_AUTHOR_PROFILE);
		block(reverseAuthor, VIEWER_PROFILE);

		mvc.perform(get("/api/v1/community/saved-posts").with(user(VIEWER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].postId").value(visible.toString()));
	}

	@Test
	void unbookmarkReloadEmptyOwnerAndInvalidCursorRemainTruthful() throws Exception {
		post(FIRST_POST, AUTHOR_PROFILE, "Bài đã lưu", "ACTIVE", NOW);
		bookmark(VIEWER_PROFILE, FIRST_POST, NOW);

		mvc.perform(delete("/api/v1/community/posts/{postId}/bookmark", FIRST_POST).with(user(VIEWER_SUBJECT)))
				.andExpect(status().isNoContent());
		mvc.perform(delete("/api/v1/community/posts/{postId}/bookmark", FIRST_POST).with(user(VIEWER_SUBJECT)))
				.andExpect(status().isNoContent());
		mvc.perform(get("/api/v1/community/saved-posts").with(user(VIEWER_SUBJECT)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
				.andExpect(jsonPath("$.nextCursor").isEmpty()).andExpect(jsonPath("$.hasMore").value(false));

		var profileCount = jdbc.sql("select count(*) from community_profile").query(Long.class).single();
		mvc.perform(get("/api/v1/community/saved-posts").with(user(UNKNOWN_SUBJECT)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		assertThat(jdbc.sql("select count(*) from community_profile").query(Long.class).single()).isEqualTo(profileCount);

		mvc.perform(get("/api/v1/community/saved-posts").param("cursor", "not-a-saved-cursor")
				.with(user(VIEWER_SUBJECT)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		mvc.perform(get("/api/v1/community/saved-posts").with(jwt()
				.jwt(token -> token.subject(VIEWER_SUBJECT.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden());
	}

	private void cleanBusinessData() {
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

	private void profile(UUID id, UUID subject, String displayName) {
		jdbc.sql("""
				insert into community_profile
				(id, account_subject, display_name, status, created_at, updated_at, version)
				values (:id, :subject, :displayName, 'ACTIVE', :now, :now, 0)
				""").param("id", id).param("subject", subject).param("displayName", displayName)
				.param("now", dbTime(NOW)).update();
	}

	private void post(UUID id, UUID authorId, String content, String state, Instant publishedAt) {
		jdbc.sql("""
				insert into community_post
				(id, author_profile_id, content, state, comment_count, reaction_count, published_at, updated_at, version)
				values (:id, :authorId, :content, :state, 0, 0, :publishedAt, :publishedAt, 0)
				""").param("id", id).param("authorId", authorId).param("content", content)
				.param("state", state).param("publishedAt", dbTime(publishedAt)).update();
		jdbc.sql("insert into community_post_topic (post_id, topic_code) values (:postId, 'MY_STORY')")
				.param("postId", id).update();
	}

	private void bookmark(UUID profileId, UUID postId, Instant createdAt) {
		jdbc.sql("insert into community_post_bookmark (post_id, profile_id, created_at) values (:postId, :profileId, :createdAt)")
				.param("postId", postId).param("profileId", profileId).param("createdAt", dbTime(createdAt)).update();
	}

	private void block(UUID blocker, UUID blocked) {
		jdbc.sql("insert into community_block (blocker_profile_id, blocked_profile_id, created_at) values (:blocker, :blocked, :now)")
				.param("blocker", blocker).param("blocked", blocked).param("now", dbTime(NOW)).update();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private OffsetDateTime dbTime(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
