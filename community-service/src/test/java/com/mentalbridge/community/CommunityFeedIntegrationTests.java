package com.mentalbridge.community;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityFeedIntegrationTests extends CommunityTestProperties {

	private static final UUID VIEWER_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000001");
	private static final UUID VIEWER_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000001");
	private static final UUID AUTHOR_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000002");
	private static final UUID BLOCKED_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000003");
	private static final UUID REVERSE_BLOCK_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000004");
	private static final UUID DELETED_PROFILE = UUID.fromString("10000000-0000-0000-0000-000000000005");
	private static final UUID NEWEST_POST = UUID.fromString("20000000-0000-0000-0000-000000000009");
	private static final UUID OLDER_POST = UUID.fromString("20000000-0000-0000-0000-000000000008");
	private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void cleanBusinessData() {
		jdbc.sql("delete from community_block").update();
		jdbc.sql("delete from community_media").update();
		jdbc.sql("delete from community_post_topic").update();
		jdbc.sql("delete from community_post").update();
		jdbc.sql("delete from community_profile").update();
	}

	@Test
	void feedIsNewestFirstCursorPagedAndFailsClosedForHiddenAndBlockedPosts() throws Exception {
		profile(VIEWER_PROFILE, VIEWER_SUBJECT, "Người đọc", "ACTIVE");
		profile(AUTHOR_PROFILE, UUID.randomUUID(), "Minh An", "ACTIVE");
		profile(BLOCKED_PROFILE, UUID.randomUUID(), "Đã chặn", "ACTIVE");
		profile(REVERSE_BLOCK_PROFILE, UUID.randomUUID(), "Đã chặn người đọc", "ACTIVE");
		post(OLDER_POST, AUTHOR_PROFILE, "Một câu chuyện cũ hơn", "ACTIVE", NOW.minusSeconds(120));
		post(NEWEST_POST, AUTHOR_PROFILE, "Một câu chuyện mới hơn", "ACTIVE", NOW.minusSeconds(60));
		post(UUID.randomUUID(), AUTHOR_PROFILE, "Nội dung đã ẩn", "MODERATION_HIDDEN", NOW);
		post(UUID.randomUUID(), BLOCKED_PROFILE, "Nội dung từ người đã chặn", "ACTIVE", NOW.plusSeconds(60));
		post(UUID.randomUUID(), REVERSE_BLOCK_PROFILE, "Nội dung không còn hiển thị", "ACTIVE", NOW.plusSeconds(120));
		topic(OLDER_POST, "MY_STORY");
		topic(NEWEST_POST, "SMALL_MILESTONE");
		block(VIEWER_PROFILE, BLOCKED_PROFILE);
		block(REVERSE_BLOCK_PROFILE, VIEWER_PROFILE);

		var first = mvc.perform(get("/api/v1/community/feed").param("limit", "1").with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].postId").value(NEWEST_POST.toString()))
				.andExpect(jsonPath("$.hasMore").value(true))
				.andReturn().getResponse().getContentAsString();
		var cursor = objectMapper.readTree(first).path("nextCursor").asText();

		mvc.perform(get("/api/v1/community/feed").param("limit", "1").param("cursor", cursor).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].postId").value(OLDER_POST.toString()))
				.andExpect(jsonPath("$.hasMore").value(false))
				.andExpect(jsonPath("$.nextCursor").isEmpty());
	}

	@Test
	void topicFilterAndDetailExposeDeletedAuthorAndOnlyReadyMedia() throws Exception {
		profile(VIEWER_PROFILE, VIEWER_SUBJECT, "Người đọc", "ACTIVE");
		profile(DELETED_PROFILE, UUID.randomUUID(), "Tên cũ không được lộ", "DELETED");
		post(NEWEST_POST, DELETED_PROFILE, "Một chia sẻ vẫn còn hữu ích.", "ACTIVE", NOW);
		topic(NEWEST_POST, "MY_STORY");
		media(UUID.fromString("30000000-0000-0000-0000-000000000001"), DELETED_PROFILE, NEWEST_POST,
				"IMAGE", "READY", "https://media.example.test/community/story.webp", 0);
		media(UUID.fromString("30000000-0000-0000-0000-000000000002"), DELETED_PROFILE, NEWEST_POST,
				"VIDEO", "PROCESSING", null, 1);

		mvc.perform(get("/api/v1/community/feed").param("topic", "MY_STORY").with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].author.displayName").value("Thành viên đã rời cộng đồng"))
				.andExpect(jsonPath("$.items[0].author.state").value("DELETED"))
				.andExpect(jsonPath("$.items[0].media.length()").value(1))
				.andExpect(jsonPath("$.items[0].mediaAvailability").value("PARTIAL"));

		mvc.perform(get("/api/v1/community/posts/{postId}", NEWEST_POST).with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.content").value("Một chia sẻ vẫn còn hữu ích."))
				.andExpect(jsonPath("$.media[0].url").value("https://media.example.test/community/story.webp"));
	}

	@Test
	void hiddenAndBlockedPostDetailsShareTheSameNotFoundContract() throws Exception {
		profile(VIEWER_PROFILE, VIEWER_SUBJECT, "Người đọc", "ACTIVE");
		profile(AUTHOR_PROFILE, UUID.randomUUID(), "Minh An", "ACTIVE");
		profile(BLOCKED_PROFILE, UUID.randomUUID(), "Đã chặn", "ACTIVE");
		var hiddenPost = UUID.randomUUID();
		var blockedPost = UUID.randomUUID();
		post(hiddenPost, AUTHOR_PROFILE, "Nội dung đã ẩn", "MODERATION_HIDDEN", NOW);
		post(blockedPost, BLOCKED_PROFILE, "Nội dung bị chặn", "ACTIVE", NOW);
		block(VIEWER_PROFILE, BLOCKED_PROFILE);

		for (var postId : new UUID[] { hiddenPost, blockedPost, UUID.randomUUID() }) {
			mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user()))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("COMMUNITY_POST_NOT_FOUND"));
		}
	}

	@Test
	void feedValidatesCursorAndRequiresTheUserRole() throws Exception {
		mvc.perform(get("/api/v1/community/feed"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().contentTypeCompatibleWith("application/problem+json"))
				.andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));

		mvc.perform(get("/api/v1/community/feed").with(jwt()
				.jwt(token -> token.subject(VIEWER_SUBJECT.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("COMMUNITY_USER_REQUIRED"));

		mvc.perform(get("/api/v1/community/feed").param("cursor", "not-a-cursor").with(user()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}

	@Test
	void topicCatalogueUsesOnlyTheGovernedNonDiagnosticCodes() throws Exception {
		mvc.perform(get("/api/v1/community/topics").with(user()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].code").value("MY_STORY"))
				.andExpect(jsonPath("$[5].code").value("HELPFUL_RESOURCE"))
				.andExpect(jsonPath("$.length()").value(6));
	}

	@Test
	void databaseRejectsReadyMediaWithoutAnHttpsDeliveryUrl() {
		profile(AUTHOR_PROFILE, UUID.randomUUID(), "Minh An", "ACTIVE");
		post(NEWEST_POST, AUTHOR_PROFILE, "Một chia sẻ", "ACTIVE", NOW);

		assertThatThrownBy(() -> media(UUID.randomUUID(), AUTHOR_PROFILE, NEWEST_POST, "IMAGE", "READY", null, 0))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user() {
		return jwt().jwt(token -> token.subject(VIEWER_SUBJECT.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private void profile(UUID id, UUID subject, String displayName, String status) {
		jdbc.sql("""
				insert into community_profile
				(id, account_subject, display_name, status, created_at, updated_at, version)
				values (:id, :subject, :displayName, :status, :now, :now, 0)
				""").param("id", id).param("subject", subject).param("displayName", displayName)
				.param("status", status).param("now", dbTime(NOW)).update();
	}

	private void post(UUID id, UUID authorId, String content, String state, Instant publishedAt) {
		jdbc.sql("""
				insert into community_post
				(id, author_profile_id, content, state, comment_count, reaction_count, published_at, updated_at, version)
				values (:id, :authorId, :content, :state, 2, 3, :publishedAt, :publishedAt, 0)
				""").param("id", id).param("authorId", authorId).param("content", content)
				.param("state", state).param("publishedAt", dbTime(publishedAt)).update();
	}

	private void topic(UUID postId, String code) {
		jdbc.sql("insert into community_post_topic (post_id, topic_code) values (:postId, :code)")
				.param("postId", postId).param("code", code).update();
	}

	private void block(UUID blocker, UUID blocked) {
		jdbc.sql("""
				insert into community_block (blocker_profile_id, blocked_profile_id, created_at)
				values (:blocker, :blocked, :now)
				""").param("blocker", blocker).param("blocked", blocked).param("now", dbTime(NOW)).update();
	}

	private void media(UUID id, UUID ownerId, UUID postId, String type, String state, String url, int position) {
		var statement = jdbc.sql("""
				insert into community_media
				(id, owner_profile_id, post_id, media_type, state, delivery_url, width, height,
				 duration_seconds, alt_text, position, created_at, updated_at, version)
				values (:id, :ownerId, :postId, :type, :state, :url, 1200, 800,
				 null, 'Ảnh minh họa cho bài viết', :position, :now, :now, 0)
				""").param("id", id).param("ownerId", ownerId).param("postId", postId)
				.param("type", type).param("state", state).param("position", position).param("now", dbTime(NOW));
		statement.param("url", url, Types.VARCHAR);
		statement.update();
	}

	private OffsetDateTime dbTime(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
