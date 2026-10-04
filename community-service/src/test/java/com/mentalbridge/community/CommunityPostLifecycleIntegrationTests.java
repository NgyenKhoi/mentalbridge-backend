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

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
class CommunityPostLifecycleIntegrationTests extends CommunityTestProperties {

	private static final UUID OWNER_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000011");
	private static final UUID OTHER_SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000012");
	private static final Instant NOW = Instant.parse("2026-09-29T05:00:00Z");

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void cleanBusinessData() {
		jdbc.sql("update community_topic set active = true").update();
		jdbc.sql("delete from community_comment_revision").update();
		jdbc.sql("delete from community_comment").update();
		jdbc.sql("delete from community_block").update();
		jdbc.sql("delete from community_media").update();
		jdbc.sql("delete from community_post_topic").update();
		jdbc.sql("delete from community_post").update();
		jdbc.sql("delete from community_profile").update();
	}

	@Test
	void createIsOwnerScopedIdempotentAndAcceptsFiveThousandUnicodeCodePoints() throws Exception {
		var content = "🌱".repeat(5_000);
		var request = request(content, List.of("MY_STORY"), List.of());
		var first = mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-create-owner-0001")
				.contentType(MediaType.APPLICATION_JSON).content(request).with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.content").value(content))
				.andExpect(jsonPath("$.author.displayName").value("Thành viên MentalBridge"))
				.andReturn().getResponse().getContentAsString();
		var postId = objectMapper.readTree(first).path("postId").asText();
		jdbc.sql("update community_post set request_fingerprint = :fingerprint where id = :id")
				.param("fingerprint", legacyFingerprint(content, "MY_STORY"))
				.param("id", UUID.fromString(postId)).update();

		mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-create-owner-0001")
				.contentType(MediaType.APPLICATION_JSON).content(request).with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.postId").value(postId));

		mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-create-owner-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Nội dung khác", List.of("MY_STORY"), List.of()))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		assertThat(jdbc.sql("select count(*) from community_post").query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from community_profile where account_subject = :subject")
				.param("subject", OWNER_SUBJECT).query(Long.class).single()).isOne();
	}

	@Test
	void anonymousModeIsPerPostAndDoesNotExposeTheCommunityProfileIdentifier() throws Exception {
		var anonymousRequest = objectMapper.writeValueAsString(Map.of(
				"content", "Một chia sẻ riêng tư",
				"topics", List.of("MY_STORY"),
				"mediaIds", List.of(),
				"authorMode", "ANONYMOUS"));
		var created = mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-anonymous-owner-0001")
				.contentType(MediaType.APPLICATION_JSON).content(anonymousRequest).with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.author.communityProfileId").isEmpty())
				.andExpect(jsonPath("$.author.displayName").value("Thành viên ẩn danh"))
				.andExpect(jsonPath("$.author.avatarPreset").isEmpty())
				.andExpect(jsonPath("$.author.state").value("ANONYMOUS"))
				.andReturn().getResponse().getContentAsString();
		var postId = UUID.fromString(objectMapper.readTree(created).path("postId").asText());

		mvc.perform(get("/api/v1/community/feed").with(user(OTHER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].author.communityProfileId").isEmpty())
				.andExpect(jsonPath("$.items[0].author.state").value("ANONYMOUS"));

		var row = jdbc.sql("select author_profile_id, author_mode from community_post where id = :id")
				.param("id", postId).query().singleRow();
		assertThat(row.get("author_profile_id")).isNotNull();
		assertThat(row).containsEntry("author_mode", "ANONYMOUS");

		var profileRequest = objectMapper.writeValueAsString(Map.of(
				"content", "Một chia sẻ riêng tư",
				"topics", List.of("MY_STORY"),
				"mediaIds", List.of(),
				"authorMode", "PROFILE"));
		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(profileRequest).with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.author.communityProfileId").isNotEmpty())
				.andExpect(jsonPath("$.author.state").value("ACTIVE"));
	}

	@Test
	void resourceAttachmentCanBeCreatedReplayedReplacedAndRemovedOnlyByThePostOwner() throws Exception {
		var firstResource = UUID.fromString("30000000-0000-4000-8000-000000000614");
		var replacementResource = UUID.fromString("40000000-0000-4000-8000-000000000614");
		var created = mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-resource-owner-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Tài nguyên mình thấy hữu ích", List.of("HELPFUL_RESOURCE"), List.of(), firstResource))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.resourceAttachment.resourceId").value(firstResource.toString()))
				.andReturn().getResponse().getContentAsString();
		var postId = UUID.fromString(objectMapper.readTree(created).path("postId").asText());

		mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-resource-owner-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Tài nguyên mình thấy hữu ích", List.of("HELPFUL_RESOURCE"), List.of(), firstResource))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.postId").value(postId.toString()));
		mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-resource-owner-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Tài nguyên mình thấy hữu ích", List.of("HELPFUL_RESOURCE"), List.of(), replacementResource))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Không được sửa", List.of("HELPFUL_RESOURCE"), List.of(), replacementResource))
				.with(user(OTHER_SUBJECT)))
				.andExpect(status().isNotFound());
		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Đã chọn tài nguyên khác", List.of("HELPFUL_RESOURCE"), List.of(), replacementResource))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resourceAttachment.resourceId").value(replacementResource.toString()));
		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Đã bỏ tài nguyên", List.of("HELPFUL_RESOURCE"), List.of()))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.resourceAttachment").doesNotExist());

		assertThat(jdbc.sql("select resource_id from community_post where id = :id")
				.param("id", postId).query(UUID.class).optional()).isEmpty();
	}

	@Test
	void editUsesOwnerOnlyEtagAndDeleteTombstonesImmediately() throws Exception {
		var created = createPost(OWNER_SUBJECT, "post-lifecycle-0001", "Chia sẻ ban đầu");
		var postId = UUID.fromString(objectMapper.readTree(created).path("postId").asText());

		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));
		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(OTHER_SUBJECT)))
				.andExpect(status().isOk()).andExpect(header().doesNotExist("ETag"));

		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Chia sẻ đã chỉnh sửa", List.of("MY_STORY", "SMALL_MILESTONE"), List.of()))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.content").value("Chia sẻ đã chỉnh sửa"));

		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Ghi đè cũ", List.of("MY_STORY"), List.of())).with(user(OWNER_SUBJECT)))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("COMMUNITY_POST_VERSION_MISMATCH"));

		mvc.perform(delete("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"1\"")
				.with(user(OTHER_SUBJECT)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("COMMUNITY_POST_NOT_FOUND"));
		mvc.perform(delete("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"1\"")
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isNoContent());

		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(OWNER_SUBJECT)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/community/feed").with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
		var row = jdbc.sql("select state, content, version from community_post where id = :id")
				.param("id", postId).query().singleRow();
		assertThat(row).containsEntry("state", "OWNER_DELETED")
				.containsEntry("content", "Chia sẻ đã chỉnh sửa")
				.containsEntry("version", 2L);
	}

	@Test
	void mediaMustBeReadyOwnedAndNotAttachedToAnotherPost() throws Exception {
		var ownerProfile = UUID.randomUUID();
		var otherProfile = UUID.randomUUID();
		profile(ownerProfile, OWNER_SUBJECT);
		profile(otherProfile, OTHER_SUBJECT);
		var ownedReady = UUID.randomUUID();
		var otherReady = UUID.randomUUID();
		var processing = UUID.randomUUID();
		media(ownedReady, ownerProfile, "READY", "https://media.example.test/owned.webp");
		media(otherReady, otherProfile, "READY", "https://media.example.test/other.webp");
		media(processing, ownerProfile, "PROCESSING", null);

		mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", "post-media-owner-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Bài có ảnh", List.of("MY_STORY"), List.of(ownedReady)))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.media[0].mediaId").value(ownedReady.toString()));

		for (var invalid : List.of(otherReady, processing, UUID.randomUUID())) {
			mvc.perform(post("/api/v1/community/posts")
					.header("Idempotency-Key", "post-media-invalid-" + invalid)
					.contentType(MediaType.APPLICATION_JSON)
					.content(request("Không thể gắn", List.of("MY_STORY"), List.of(invalid)))
					.with(user(OWNER_SUBJECT)))
					.andExpect(status().isConflict())
					.andExpect(jsonPath("$.code").value("COMMUNITY_MEDIA_NOT_ATTACHABLE"));
		}
	}

	@Test
	void editPreservesSamePostNonReadyMediaThatTheReadProjectionOmits() throws Exception {
		var ownerProfile = UUID.randomUUID();
		profile(ownerProfile, OWNER_SUBJECT);
		var ready = UUID.randomUUID();
		var processing = UUID.randomUUID();
		media(ready, ownerProfile, "READY", "https://media.example.test/ready.webp");
		media(processing, ownerProfile, "PROCESSING", null);

		var created = mvc.perform(post("/api/v1/community/posts")
				.header("Idempotency-Key", "post-partial-media-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Bài có media đang xử lý", List.of("MY_STORY"), List.of(ready)))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.mediaAvailability").value("READY"))
				.andReturn().getResponse().getContentAsString();
		var postId = UUID.fromString(objectMapper.readTree(created).path("postId").asText());
		attachMedia(processing, postId, 1);

		mvc.perform(get("/api/v1/community/posts/{postId}", postId).with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.media.length()").value(1))
				.andExpect(jsonPath("$.media[0].mediaId").value(ready.toString()))
				.andExpect(jsonPath("$.mediaAvailability").value("PARTIAL"));

		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Nội dung và chủ đề đã sửa", List.of("SMALL_MILESTONE"), List.of(ready)))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.content").value("Nội dung và chủ đề đã sửa"))
				.andExpect(jsonPath("$.topics[0]").value("SMALL_MILESTONE"))
				.andExpect(jsonPath("$.media.length()").value(1))
				.andExpect(jsonPath("$.media[0].mediaId").value(ready.toString()))
				.andExpect(jsonPath("$.mediaAvailability").value("PARTIAL"));

		assertThat(attachedPostId(processing)).contains(postId);

		mvc.perform(delete("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"1\"")
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isNoContent());
		assertThat(attachedPostId(ready)).isEmpty();
		assertThat(attachedPostId(processing)).isEmpty();
	}

	@Test
	void validationIsBoundedAndDoesNotCountEmojiAsTwoCharacters() throws Exception {
		mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", "post-too-long-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("🌱".repeat(5_001), List.of("MY_STORY"), List.of()))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMUNITY_POST_INVALID"));
		mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", "short")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Nội dung", List.of("MY_STORY"), List.of())).with(user(OWNER_SUBJECT)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_IDEMPOTENCY_KEY"));
		mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", "post-null-media-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"Nội dung\",\"topics\":[\"MY_STORY\"],\"mediaIds\":[null]}")
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("COMMUNITY_POST_INVALID"));
	}

	private String createPost(UUID subject, String key, String content) throws Exception {
		return mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(request(content, List.of("MY_STORY"), List.of())).with(user(subject)))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
	}

	private String request(String content, List<String> topics, List<UUID> mediaIds) throws Exception {
		return request(content, topics, mediaIds, null);
	}

	private String request(String content, List<String> topics, List<UUID> mediaIds, UUID resourceId) throws Exception {
		var payload = new LinkedHashMap<String, Object>();
		payload.put("content", content);
		payload.put("topics", topics);
		payload.put("mediaIds", mediaIds);
		if (resourceId != null) {
			payload.put("resourceId", resourceId);
		}
		return objectMapper.writeValueAsString(payload);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private void profile(UUID id, UUID subject) {
		jdbc.sql("""
				insert into community_profile
				(id, account_subject, display_name, status, created_at, updated_at, version)
				values (:id, :subject, 'Thành viên', 'ACTIVE', :now, :now, 0)
				""").param("id", id).param("subject", subject).param("now", dbTime(NOW)).update();
	}

	private void media(UUID id, UUID ownerId, String state, String url) {
		var statement = jdbc.sql("""
				insert into community_media
				(id, owner_profile_id, post_id, media_type, state, delivery_url, position, created_at, updated_at, version)
				values (:id, :ownerId, null, 'IMAGE', :state, :url, 0, :now, :now, 0)
				""").param("id", id).param("ownerId", ownerId).param("state", state).param("now", dbTime(NOW));
		statement.param("url", url, Types.VARCHAR).update();
	}

	private void attachMedia(UUID mediaId, UUID postId, int position) {
		jdbc.sql("update community_media set post_id = :postId, position = :position where id = :mediaId")
				.param("postId", postId).param("position", position).param("mediaId", mediaId).update();
	}

	private java.util.Optional<UUID> attachedPostId(UUID mediaId) {
		return jdbc.sql("select post_id from community_media where id = :mediaId")
				.param("mediaId", mediaId).query(UUID.class).optional();
	}

	@Test
	void inactiveTopicCannotBeAddedButAnExistingHistoricalClassificationCanBePreservedOrRemoved() throws Exception {
		var created = createPost(OWNER_SUBJECT, "post-topic-history-0001", "Chia sẻ ban đầu");
		var postId = UUID.fromString(objectMapper.readTree(created).path("postId").asText());
		jdbc.sql("update community_topic set active = false, updated_at = :now where code = 'MY_STORY'")
				.param("now", dbTime(NOW)).update();

		mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", "post-topic-history-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Chia sẻ ban đầu", List.of("MY_STORY"), List.of())).with(user(OWNER_SUBJECT)))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.postId").value(postId.toString()));

		mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", "post-inactive-topic-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Bài mới", List.of("MY_STORY"), List.of())).with(user(OTHER_SUBJECT)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMUNITY_TOPIC_UNAVAILABLE"));

		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Chỉ sửa nội dung", List.of("MY_STORY"), List.of())).with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.topics[0]").value("MY_STORY"));

		mvc.perform(patch("/api/v1/community/posts/{postId}", postId).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content(request("Chuyển sang chủ đề đang hoạt động", List.of("SMALL_MILESTONE"), List.of()))
				.with(user(OWNER_SUBJECT)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.topics[0]").value("SMALL_MILESTONE"));

		assertThat(jdbc.sql("select count(*) from community_post_topic where post_id = :postId and topic_code = 'MY_STORY'")
				.param("postId", postId).query(Long.class).single()).isZero();
	}

	private String legacyFingerprint(String content, String topic) throws Exception {
		var digest = MessageDigest.getInstance("SHA-256");
		addFingerprintPart(digest, content);
		addFingerprintPart(digest, topic);
		return HexFormat.of().formatHex(digest.digest());
	}

	private void addFingerprintPart(MessageDigest digest, String value) {
		var bytes = value.getBytes(StandardCharsets.UTF_8);
		digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
		digest.update(bytes);
	}

	private OffsetDateTime dbTime(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
