package com.mentalbridge.community.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.mentalbridge.community.CommunityTestProperties;
import com.mentalbridge.community.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityAvatarIntegrationTests extends CommunityTestProperties {

	@Autowired private MockMvc mvc;
	@Autowired private JdbcClient jdbc;
	@Autowired private CommunityMediaService mediaService;
	@MockitoBean private CommunityMediaStorage storage;

	private UUID subject;
	private UUID profileId;

	@BeforeEach
	void createProfile() {
		subject = UUID.randomUUID();
		profileId = UUID.randomUUID();
		jdbc.sql("""
				insert into community_profile (id, account_subject, display_name, avatar_preset, status,
				created_at, updated_at, version) values (:id, :subject, 'Mầm xanh', 'LEAF', 'ACTIVE', now(), now(), 0)
				""").param("id", profileId).param("subject", subject).update();
	}

	@AfterEach
	void releaseSelectedTestImages() {
		jdbc.sql("update community_profile set avatar_media_id=null where id=:id").param("id", profileId).update();
	}

	@Test
	void selectedPhotoSurvivesReloadAndNameEditsAndCanBeRemovedIdempotently() throws Exception {
		var image = image(profileId, "IMAGE", "READY");
		select(image, "\"0\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.avatarImage.mediaId").value(image.toString()))
				.andExpect(jsonPath("$.avatarImage.width").value(800))
				.andExpect(jsonPath("$.avatarPreset").value("LEAF"))
				.andExpect(jsonPath("$.accountSubject").doesNotExist());
		select(image, "\"0\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
		mvc.perform(get("/api/v1/community/profile").with(user(subject)))
				.andExpect(jsonPath("$.avatarImage.mediaId").value(image.toString()));
		mvc.perform(put("/api/v1/community/profile").with(user(subject)).header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON).content("{\"displayName\":\"Tên mới\",\"avatarPreset\":\"WAVE\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.avatarImage.mediaId").value(image.toString()));
		select(null, "\"1\"").andExpect(status().isPreconditionFailed());
		select(null, "\"2\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""))
				.andExpect(jsonPath("$.avatarImage").doesNotExist());
		select(null, "\"2\"").andExpect(status().isOk()).andExpect(header().string("ETag", "\"3\""));
	}

	@Test
	void rejectsForeignUnverifiedVideoAndAttachedImagesWithoutChangingTheProfile() throws Exception {
		var foreignProfile = UUID.randomUUID();
		jdbc.sql("""
				insert into community_profile (id, account_subject, display_name, status, created_at, updated_at, version)
				values (:id, :subject, 'Khác', 'ACTIVE', now(), now(), 0)
				""").param("id", foreignProfile).param("subject", UUID.randomUUID()).update();
		var foreignImage = image(foreignProfile, "IMAGE", "READY");
		select(foreignImage, "\"0\"").andExpect(status().isNotFound());
		select(UUID.randomUUID(), "\"0\"").andExpect(status().isNotFound());
		select(image(profileId, "VIDEO", "READY"), "\"0\"").andExpect(status().isConflict());
		select(image(profileId, "IMAGE", "PENDING"), "\"0\"").andExpect(status().isConflict());
		select(image(profileId, "IMAGE", "DELETED"), "\"0\"").andExpect(status().isConflict());
		var legacy = image(profileId, "IMAGE", "READY");
		jdbc.sql("update community_media set storage_provider=null, storage_key=null where id=:id")
				.param("id", legacy).update();
		select(legacy, "\"0\"").andExpect(status().isConflict());
		var unknownDimensions = image(profileId, "IMAGE", "READY");
		jdbc.sql("update community_media set width=null where id=:id").param("id", unknownDimensions).update();
		select(unknownDimensions, "\"0\"").andExpect(status().isConflict());
		var postId = insertPost("PROFILE");
		var attached = image(profileId, "IMAGE", "READY");
		jdbc.sql("update community_media set post_id=:post where id=:id").param("id", attached).param("post", postId).update();
		select(attached, "\"0\"").andExpect(status().isConflict());
		assertThatThrownBy(() -> jdbc.sql("update community_profile set avatar_media_id=:image where id=:id")
				.param("image", foreignImage).param("id", profileId).update()).isInstanceOf(DataIntegrityViolationException.class);
		mvc.perform(get("/api/v1/community/profile").with(user(subject)))
				.andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.avatarImage").doesNotExist());
	}

	@Test
	void authorProjectionUsesTheCurrentPhotoButAnonymousAndDeletedIdentitiesDoNotLeakIt() throws Exception {
		var named = insertPost("PROFILE");
		var anonymous = insertPost("ANONYMOUS");
		var image = image(profileId, "IMAGE", "READY");
		select(image, "\"0\"").andExpect(status().isOk());
		mvc.perform(get("/api/v1/community/posts/{id}", named).with(user(subject)))
				.andExpect(jsonPath("$.author.avatarImage.mediaId").value(image.toString()));
		mvc.perform(get("/api/v1/community/posts/{id}", anonymous).with(user(subject)))
				.andExpect(jsonPath("$.author.avatarImage").doesNotExist())
				.andExpect(jsonPath("$.author.communityProfileId").isEmpty());
		mvc.perform(post("/api/v1/community/posts/{id}/comments", named).with(user(subject))
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"Cảm ơn bạn đã chia sẻ\",\"parentCommentId\":null}"))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.author.avatarImage.mediaId").value(image.toString()));
		jdbc.sql("update community_profile set status='DELETED' where id=:id").param("id", profileId).update();
		mvc.perform(get("/api/v1/community/posts/{id}", named).with(user(UUID.randomUUID())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.author.avatarImage").doesNotExist());
	}

	@Test
	void activeAvatarCannotBePurgedDeletedOrAttachedToAPost() throws Exception {
		var image = image(profileId, "IMAGE", "READY");
		jdbc.sql("update community_media set storage_provider='CLOUDINARY', storage_key=:key where id=:id")
				.param("key", "test-avatar-" + image).param("id", image).update();
		select(image, "\"0\"").andExpect(status().isOk());
		mediaService.cleanupExpiredMedia();
		assertThat(jdbc.sql("select state from community_media where id=:id").param("id", image)
				.query(String.class).single()).isEqualTo("READY");
		mvc.perform(delete("/api/v1/community/media/{id}", image).with(user(subject)).header("If-Match", "\"0\""))
				.andExpect(status().isConflict());
		mvc.perform(post("/api/v1/community/posts").with(user(subject)).header("Idempotency-Key", UUID.randomUUID().toString())
				.contentType(MediaType.APPLICATION_JSON).content("""
				{"content":"Một ngày mới", "topics":["MY_STORY"], "mediaIds":["%s"]}
				""".formatted(image))).andExpect(status().isConflict());
		select(null, "\"1\"").andExpect(status().isOk());
		mediaService.cleanupExpiredMedia();
		assertThat(jdbc.sql("select state from community_media where id=:id").param("id", image)
				.query(String.class).single()).isEqualTo("EXPIRED");
	}

	@Test
	void concurrentDifferentSelectionsCannotOverwriteEachOther() throws Exception {
		var first = image(profileId, "IMAGE", "READY");
		var second = image(profileId, "IMAGE", "READY");
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var one = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return select(first, "\"0\"").andReturn().getResponse().getStatus(); });
			var two = executor.submit(() -> { start.await(10, TimeUnit.SECONDS); return select(second, "\"0\"").andReturn().getResponse().getStatus(); });
			start.countDown();
			assertThat(java.util.List.of(one.get(15, TimeUnit.SECONDS), two.get(15, TimeUnit.SECONDS)))
					.containsExactlyInAnyOrder(200, 412);
		}
	}

	@Test
	void requiresUserAuthorizationVersionAndAnExactMediaIdOnlyBody() throws Exception {
		mvc.perform(put("/api/v1/community/profile/avatar").contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":null}"))
				.andExpect(status().isUnauthorized());
		mvc.perform(put("/api/v1/community/profile/avatar").with(jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))
				.contentType(MediaType.APPLICATION_JSON).content("{\"mediaId\":null}"))
				.andExpect(status().isForbidden());
		select(null, null).andExpect(status().isPreconditionFailed());
		for (var body : java.util.List.of("{}", "[]", "{\"mediaId\":42}", "{\"mediaId\":\"\"}",
				"{\"mediaId\":\"1-1-1-1-1\"}", "{\"mediaId\":null,\"url\":\"https://example.test\"}")) {
			mvc.perform(put("/api/v1/community/profile/avatar").with(user(subject)).header("If-Match", "\"0\"")
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
	}

	private ResultActions select(UUID mediaId, String etag) throws Exception {
		var request = put("/api/v1/community/profile/avatar").with(user(subject)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"mediaId\":" + (mediaId == null ? "null" : "\"" + mediaId + "\"") + "}");
		if (etag != null) request.header("If-Match", etag);
		return mvc.perform(request);
	}

	private UUID image(UUID owner, String type, String state) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into community_media (id, owner_profile_id, media_type, state, storage_provider, storage_key,
				delivery_url, width, height,
				position, created_at, updated_at, version)
				values (:id, :owner, :type, :state, 'CLOUDINARY', :key, :url, 800, 800, 0,
				now()-interval '2 days', now()-interval '2 days', 0)
				""").param("id", id).param("owner", owner).param("type", type).param("state", state)
				.param("key", "test-avatar-" + id)
				.param("url", "READY".equals(state) ? "https://res.cloudinary.test/avatar/" + id : null).update();
		return id;
	}

	private UUID insertPost(String mode) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into community_post (id, author_profile_id, content, state, author_mode, published_at, updated_at, version)
				values (:id, :owner, 'Ngày hôm nay', 'ACTIVE', :mode, now(), now(), 0)
				""").param("id", id).param("owner", profileId).param("mode", mode).update();
		return id;
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID owner) {
		return jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
