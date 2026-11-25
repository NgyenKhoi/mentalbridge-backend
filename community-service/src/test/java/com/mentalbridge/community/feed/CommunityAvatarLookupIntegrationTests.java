package com.mentalbridge.community.feed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collections;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mentalbridge.community.CommunityTestProperties;
import com.mentalbridge.community.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "mentalbridge.community.friends.enabled=true")
class CommunityAvatarLookupIntegrationTests extends CommunityTestProperties {

	private static final String PATH = "/api/v1/community/avatars";

	@Autowired private MockMvc mvc;
	@Autowired private JdbcClient jdbc;

	@Test
	void returnsPresetAndPhotoOnlyForVisibleActiveProfilesWithoutCreatingOne() throws Exception {
		var viewer = UUID.randomUUID();
		var photoSubject = UUID.randomUUID();
		var photoProfile = profile(photoSubject, "LEAF", "ACTIVE");
		var image = image(photoProfile);
		jdbc.sql("update community_profile set avatar_media_id=:image where id=:id").param("image", image)
				.param("id", photoProfile).update();
		var presetSubject = UUID.randomUUID();
		profile(presetSubject, null, "ACTIVE");
		var deletedSubject = UUID.randomUUID();
		profile(deletedSubject, "WAVE", "DELETED");
		var unknown = UUID.randomUUID();

		mvc.perform(get(PATH).param("accountIds", String.join(",", photoSubject.toString(), presetSubject.toString(),
				deletedSubject.toString(), unknown.toString(), photoSubject.toString().toUpperCase()))
				.with(caller(viewer, "ROLE_USER")))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.items[?(@.accountId=='%s')].avatarPreset", photoSubject).value("LEAF"))
				.andExpect(jsonPath("$.items[?(@.accountId=='%s')].avatarImage.mediaId", photoSubject)
						.value(image.toString()))
				.andExpect(jsonPath("$.items[?(@.accountId=='%s')].avatarImage.url", photoSubject)
						.value("https://res.cloudinary.test/avatar/" + image))
				.andExpect(jsonPath("$.items[?(@.accountId=='%s')].avatarPreset", presetSubject).value((Object) null))
				.andExpect(jsonPath("$.items[?(@.accountId=='%s')].avatarImage", presetSubject).value((Object) null))
				.andExpect(jsonPath("$.items[0].displayName").doesNotExist())
				.andExpect(jsonPath("$.items[0].communityProfileId").doesNotExist());
		assertThat(jdbc.sql("select count(*) from community_profile where account_subject=:subject")
				.param("subject", viewer).query(Long.class).single()).isZero();

		mvc.perform(get(PATH).param("accountIds", presetSubject.toString())
				.with(caller(UUID.randomUUID(), "ROLE_SPECIALIST")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].accountId").value(presetSubject.toString()));
	}

	@Test
	void omitsProfilesBlockedInEitherDirection() throws Exception {
		var viewer = UUID.randomUUID();
		var viewerProfile = profile(viewer, "SUNRISE", "ACTIVE");
		var blockedSubject = UUID.randomUUID();
		var blockedProfile = profile(blockedSubject, "LOTUS", "ACTIVE");
		var blockerSubject = UUID.randomUUID();
		var blockerProfile = profile(blockerSubject, "CLOUD", "ACTIVE");
		block(viewerProfile, blockedProfile);
		block(blockerProfile, viewerProfile);

		mvc.perform(get(PATH).param("accountIds", String.join(",", viewer.toString(), blockedSubject.toString(),
				blockerSubject.toString())).with(caller(viewer, "ROLE_USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].accountId").value(viewer.toString()))
				.andExpect(jsonPath("$.items[0].avatarPreset").value("SUNRISE"));
		mvc.perform(get(PATH).param("accountIds", blockedSubject.toString()).with(caller(UUID.randomUUID(), "ROLE_USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].avatarPreset").value("LOTUS"));
	}

	@Test
	void rejectsMalformedOrTooManyIdsAndAnonymousCallers() throws Exception {
		var tooMany = Stream.generate(UUID::randomUUID).limit(51).map(UUID::toString).collect(Collectors.joining(","));
		var viewer = caller(UUID.randomUUID(), "ROLE_USER");
		for (var value : java.util.List.of("", ",", "not-a-uuid", "1-1-1-1-1", UUID.randomUUID() + ",", tooMany)) {
			mvc.perform(get(PATH).param("accountIds", value).with(viewer)).andExpect(status().isBadRequest());
		}
		mvc.perform(get(PATH).with(viewer)).andExpect(status().isBadRequest());
		var fifty = String.join(",", Collections.nCopies(50, UUID.randomUUID().toString()));
		mvc.perform(get(PATH).param("accountIds", fifty).with(viewer)).andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(0));
		mvc.perform(get(PATH).param("accountIds", UUID.randomUUID().toString())).andExpect(status().isUnauthorized());
	}

	private UUID profile(UUID subject, String preset, String state) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into community_profile (id, account_subject, display_name, avatar_preset, status,
				created_at, updated_at, version) values (:id, :subject, 'Thành viên', :preset, :state, now(), now(), 0)
				""").param("id", id).param("subject", subject).param("preset", preset).param("state", state).update();
		return id;
	}

	private UUID image(UUID owner) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into community_media (id, owner_profile_id, media_type, state, storage_provider, storage_key,
				delivery_url, width, height, position, created_at, updated_at, version)
				values (:id, :owner, 'IMAGE', 'READY', 'CLOUDINARY', :key, :url, 800, 800, 0, now(), now(), 0)
				""").param("id", id).param("owner", owner).param("key", "test-avatar-" + id)
				.param("url", "https://res.cloudinary.test/avatar/" + id).update();
		return id;
	}

	private void block(UUID blocker, UUID blocked) {
		jdbc.sql("insert into community_block(blocker_profile_id,blocked_profile_id,created_at) values(:a,:b,now())")
				.param("a", blocker).param("b", blocked).update();
	}

	private RequestPostProcessor caller(UUID subject, String role) {
		return jwt().jwt(token -> token.subject(subject.toString())).authorities(new SimpleGrantedAuthority(role));
	}
}
