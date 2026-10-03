package com.mentalbridge.community;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

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
class CommunityProfileIntegrationTests extends CommunityTestProperties {

	private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000011");
	private static final UUID OTHER_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000012");

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MockMvc mvc;

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
	void ownerCreatesReadsAndUpdatesAnIndependentDisplayIdentity() throws Exception {
		mvc.perform(get("/api/v1/community/profile").with(user(OWNER)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("COMMUNITY_PROFILE_NOT_FOUND"));

		var created = mvc.perform(put("/api/v1/community/profile").with(user(OWNER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName":"  Binh Minh  ","avatarPreset":"SUNRISE"}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().string("ETag", "\"0\""))
				.andExpect(header().string("Location", "/api/v1/community/profile"))
				.andExpect(jsonPath("$.displayName").value("Binh Minh"))
				.andExpect(jsonPath("$.avatarPreset").value("SUNRISE"))
				.andExpect(jsonPath("$.version").value(0))
				.andExpect(jsonPath("$.accountSubject").doesNotExist())
				.andReturn().getResponse().getContentAsString();

		var profileId = com.jayway.jsonpath.JsonPath.read(created, "$.communityProfileId").toString();
		mvc.perform(get("/api/v1/community/profile").with(user(OWNER)))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.communityProfileId").value(profileId));

		mvc.perform(get("/api/v1/community/profile").with(user(OTHER_OWNER)))
				.andExpect(status().isNotFound());

		mvc.perform(put("/api/v1/community/profile").with(user(OWNER)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName":"La Xanh","avatarPreset":"LEAF"}
						"""))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.communityProfileId").value(profileId))
				.andExpect(jsonPath("$.displayName").value("La Xanh"))
				.andExpect(jsonPath("$.avatarPreset").value("LEAF"));
	}

	@Test
	void updateRequiresTheExactVersionAndIdenticalReplacementIsStable() throws Exception {
		create(OWNER, "May", "CLOUD");

		mvc.perform(put("/api/v1/community/profile").with(user(OWNER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"May\",\"avatarPreset\":\"CLOUD\"}"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("COMMUNITY_PROFILE_VERSION_REQUIRED"));

		mvc.perform(put("/api/v1/community/profile").with(user(OWNER)).header("If-Match", "\"9\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"May\",\"avatarPreset\":\"CLOUD\"}"))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("COMMUNITY_PROFILE_VERSION_MISMATCH"));

		mvc.perform(put("/api/v1/community/profile").with(user(OWNER)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"May\",\"avatarPreset\":\"CLOUD\"}"))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", "\"0\""));
	}

	@Test
	void requestCannotChooseAnAccountSubjectAndTextUsesCodePointBounds() throws Exception {
		mvc.perform(put("/api/v1/community/profile").with(user(OWNER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"displayName":"An danh","avatarPreset":null,"accountSubject":"%s"}
						""".formatted(OTHER_OWNER)))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

		var eightyEmoji = "\uD83C\uDF3F".repeat(80);
		mvc.perform(put("/api/v1/community/profile").with(user(OWNER))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"%s\",\"avatarPreset\":null}".formatted(eightyEmoji)))
				.andExpect(status().isCreated());

		mvc.perform(put("/api/v1/community/profile").with(user(OWNER)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"%s\",\"avatarPreset\":null}".formatted("\uD83C\uDF3F".repeat(81))))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
	}

	@Test
	void onlyLocallyVerifiedUserRoleCanManageTheProfile() throws Exception {
		mvc.perform(get("/api/v1/community/profile"))
				.andExpect(status().isUnauthorized());

		mvc.perform(get("/api/v1/community/profile").with(jwt()
				.jwt(token -> token.subject(OWNER.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden());
	}

	private void create(UUID owner, String displayName, String avatarPreset) throws Exception {
		mvc.perform(put("/api/v1/community/profile").with(user(owner))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"%s\",\"avatarPreset\":\"%s\"}".formatted(displayName, avatarPreset)))
				.andExpect(status().isCreated());
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID subject) {
		return jwt().jwt(token -> token.subject(subject.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
