package com.mentalbridge.community.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.mentalbridge.community.CommunityTestProperties;
import com.mentalbridge.community.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class CommunityMediaIntegrationTests extends CommunityTestProperties {

	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000031");
	private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000032");

	@Autowired
	private MockMvc mvc;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private CommunityMediaService mediaService;

	@MockitoBean
	private CommunityMediaStorage storage;

	@BeforeEach
	void cleanBusinessData() {
		jdbc.sql("delete from community_comment_revision").update();
		jdbc.sql("delete from community_comment").update();
		jdbc.sql("delete from community_block").update();
		jdbc.sql("delete from community_media").update();
		jdbc.sql("delete from community_post_topic").update();
		jdbc.sql("delete from community_post").update();
		jdbc.sql("delete from community_profile").update();
		when(storage.authorize(anyString(), any(), anyLong())).thenAnswer(invocation ->
				new CommunityMediaStorage.UploadAuthorization(URI.create("https://api.cloudinary.test/upload"),
						Map.of("api_key", "public-key", "signature", "signed-value",
								"public_id", invocation.getArgument(0))));
	}

	@Test
	void ownerCreatesIdempotentIntentAndFinalizesVerifiedImage() throws Exception {
		var created = createIntent(OWNER, "media-upload-owner-0001", "photo.jpg", "IMAGE", "image/jpeg", 1234)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.state").value("PENDING"))
				.andExpect(jsonPath("$.uploadFields.signature").value("signed-value"))
				.andReturn().getResponse().getContentAsString();
		var mediaId = UUID.fromString(objectMapper.readTree(created).path("mediaId").asText());
		var storageKey = jdbc.sql("select storage_key from community_media where id = :id")
				.param("id", mediaId).query(String.class).single();

		createIntent(OWNER, "media-upload-owner-0001", "photo.jpg", "IMAGE", "image/jpeg", 1234)
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.mediaId").value(mediaId.toString()));
		createIntent(OWNER, "media-upload-owner-0001", "other.jpg", "IMAGE", "image/jpeg", 1234)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		mvc.perform(post("/api/v1/community/media/{mediaId}/finalize", mediaId).with(user(OTHER)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMUNITY_MEDIA_NOT_FOUND"));
		verify(storage, never()).inspect(anyString(), any());

		var asset = new CommunityMediaStorage.StoredAsset(storageKey, "image", "authenticated", "jpg", 1234,
				1200, 800, null, 7);
		when(storage.inspect(storageKey, CommunityMediaEntity.Type.IMAGE)).thenReturn(asset);
		when(storage.deliveryUrl(asset, CommunityMediaEntity.Type.IMAGE))
				.thenReturn("https://res.cloudinary.test/s--signed--/q_auto,f_auto/v7/" + mediaId);

		mvc.perform(post("/api/v1/community/media/{mediaId}/finalize", mediaId).with(user(OWNER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("READY"))
				.andExpect(jsonPath("$.version").value(1));
		mvc.perform(post("/api/v1/community/media/{mediaId}/finalize", mediaId).with(user(OWNER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("READY"));
		verify(storage, times(1)).inspect(storageKey, CommunityMediaEntity.Type.IMAGE);
		assertThat(jdbc.sql("select state, delivery_url, width, height from community_media where id = :id")
				.param("id", mediaId).query().singleRow())
				.containsEntry("state", "READY")
				.containsEntry("width", 1200)
				.containsEntry("height", 800);
	}

	@Test
	void finalizeRejectsMismatchedSignatureAndPostAttachmentRequiresReadyOwnership() throws Exception {
		var created = createIntent(OWNER, "media-upload-owner-0002", "photo.jpg", "IMAGE", "image/jpeg", 100)
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		var mediaId = UUID.fromString(objectMapper.readTree(created).path("mediaId").asText());
		var storageKey = jdbc.sql("select storage_key from community_media where id = :id")
				.param("id", mediaId).query(String.class).single();
		when(storage.inspect(storageKey, CommunityMediaEntity.Type.IMAGE)).thenReturn(
				new CommunityMediaStorage.StoredAsset(storageKey, "image", "authenticated", "png", 100,
						640, 480, null, 1));

		mvc.perform(post("/api/v1/community/media/{mediaId}/finalize", mediaId).with(user(OWNER)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("REJECTED"));
		mvc.perform(post("/api/v1/community/posts").header("Idempotency-Key", "post-media-rejected-0001")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"content\":\"BĂ i viáº¿t\",\"topics\":[\"MY_STORY\"],\"mediaIds\":[\"" + mediaId + "\"]}")
				.with(user(OWNER)))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COMMUNITY_MEDIA_NOT_ATTACHABLE"));
		assertThat(jdbc.sql("select delivery_url from community_media where id = :id")
				.param("id", mediaId).query(String.class).optional()).isEmpty();
	}

	@Test
	void boundedValidationAndOwnerOnlyDeleteProtectProviderObjects() throws Exception {
		createIntent(OWNER, "media-upload-too-large-01", "large.png", "IMAGE", "image/png", 10_485_761)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMUNITY_MEDIA_INVALID"));
		createIntent(OWNER, "media-upload-bad-type-001", "file.svg", "IMAGE", "image/svg+xml", 100)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("COMMUNITY_MEDIA_INVALID"));

		var created = createIntent(OWNER, "media-upload-delete-0001", "clip.mp4", "VIDEO", "video/mp4", 500)
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		var mediaId = UUID.fromString(objectMapper.readTree(created).path("mediaId").asText());
		var storageKey = jdbc.sql("select storage_key from community_media where id = :id")
				.param("id", mediaId).query(String.class).single();

		mvc.perform(delete("/api/v1/community/media/{mediaId}", mediaId).header("If-Match", "\"0\"")
				.with(user(OTHER)))
				.andExpect(status().isNotFound());
		mvc.perform(delete("/api/v1/community/media/{mediaId}", mediaId).header("If-Match", "\"0\"")
				.with(user(OWNER)))
				.andExpect(status().isNoContent());
		verify(storage).delete(storageKey, CommunityMediaEntity.Type.VIDEO);
		assertThat(jdbc.sql("select state, storage_key from community_media where id = :id")
				.param("id", mediaId).query().singleRow())
				.containsEntry("state", "DELETED")
				.containsEntry("storage_key", null);
	}

	@Test
	void cleanupExpiresAndPurgesUnattachedReadyMediaAfterRetention() {
		var profileId = UUID.randomUUID();
		var mediaId = UUID.randomUUID();
		var storageKey = "mentalbridge/community/" + profileId + "/" + mediaId;
		jdbc.sql("""
				insert into community_profile
				(id, account_subject, display_name, status, created_at, updated_at, version)
				values (:id, :subject, 'Thành viên', 'ACTIVE', now() - interval '2 days', now() - interval '2 days', 0)
				""").param("id", profileId).param("subject", OWNER).update();
		jdbc.sql("""
				insert into community_media
				(id, owner_profile_id, media_type, state, delivery_url, storage_provider, storage_key,
				 expected_mime_type, expected_size_bytes, position, created_at, updated_at, version)
				values (:id, :ownerId, 'IMAGE', 'READY', 'https://media.example.test/ready.webp',
				 'CLOUDINARY', :storageKey, 'image/webp', 100, 0,
				 now() - interval '2 days', now() - interval '2 days', 0)
				""").param("id", mediaId).param("ownerId", profileId).param("storageKey", storageKey).update();

		mediaService.cleanupExpiredMedia();

		verify(storage).delete(storageKey, CommunityMediaEntity.Type.IMAGE);
		assertThat(jdbc.sql("select state, storage_key, delivery_url from community_media where id = :id")
				.param("id", mediaId).query().singleRow())
				.containsEntry("state", "EXPIRED")
				.containsEntry("storage_key", null)
				.containsEntry("delivery_url", null);
	}

	private org.springframework.test.web.servlet.ResultActions createIntent(UUID subject, String key, String fileName,
			String type, String mimeType, long sizeBytes) throws Exception {
		return mvc.perform(post("/api/v1/community/media/upload-intents")
				.header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON)
				.content(objectMapper.writeValueAsString(Map.of("fileName", fileName, "mediaType", type,
						"mimeType", mimeType, "sizeBytes", sizeBytes)))
				.with(user(subject)));
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
