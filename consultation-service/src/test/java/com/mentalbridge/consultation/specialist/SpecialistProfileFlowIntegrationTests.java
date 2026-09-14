package com.mentalbridge.consultation.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SpecialistProfileFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	@Test
	void specialistSavesSubmitsAndAdminApprovesTheRealProfile() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();

		mvc.perform(get("/api/v1/specialist-profile").with(specialist(specialistId)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("SPECIALIST_PROFILE_NOT_FOUND"));

		var created = mvc.perform(put("/api/v1/specialist-profile").with(specialist(specialistId))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("Nguyen An")))
				.andExpect(status().isCreated()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.accountId").value(specialistId.toString()))
				.andExpect(jsonPath("$.approvalStatus").value("PENDING"))
				.andExpect(jsonPath("$.submittedAt").isEmpty())
				.andExpect(jsonPath("$.supportAreas.length()").value(2)).andReturn();
		var draftEtag = created.getResponse().getHeader("ETag");

		mvc.perform(get("/api/v1/admin/specialist-profiles").with(admin(adminId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));

		mvc.perform(post("/api/v1/specialist-profile/submit").with(specialist(specialistId))
				.header("If-Match", draftEtag))
				.andExpect(status().isOk()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.submittedAt").isNotEmpty()).andReturn();
		var submittedEtag = mvc.perform(get("/api/v1/specialist-profile").with(specialist(specialistId)))
				.andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");

		mvc.perform(get("/api/v1/admin/specialist-profiles").with(admin(adminId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].accountId").value(specialistId.toString()));

		var approved = mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/approve", specialistId).with(admin(adminId))
				.header("If-Match", submittedEtag))
				.andExpect(status().isOk()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
				.andExpect(jsonPath("$.reviewedBy").value(adminId.toString())).andReturn();

		mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/approve", specialistId).with(admin(adminId))
				.header("If-Match", approved.getResponse().getHeader("ETag")))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", approved.getResponse().getHeader("ETag")))
				.andExpect(jsonPath("$.approvalStatus").value("APPROVED"));

		mvc.perform(get("/api/v1/specialist-profile").with(specialist(specialistId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
		assertThat(jdbc.sql("select approval_status from specialist_profile_status_history where specialist_account_id = :id order by occurred_at")
				.param("id", specialistId).query(String.class).list()).containsExactly("PENDING", "APPROVED");
	}

	@Test
	void editingASubmittedPendingProfileRemovesItFromReviewUntilResubmitted() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var created = mvc.perform(put("/api/v1/specialist-profile").with(specialist(specialistId))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("First"))).andExpect(status().isCreated())
				.andReturn();
		mvc.perform(get("/api/v1/admin/specialist-profiles/{id}", specialistId).with(admin(adminId)))
				.andExpect(status().isNotFound());
		mvc.perform(post("/api/v1/specialist-profile/submit").with(specialist(specialistId))
				.header("If-Match", created.getResponse().getHeader("ETag"))).andExpect(status().isOk());
		var submittedEtag = mvc.perform(get("/api/v1/specialist-profile").with(specialist(specialistId)))
				.andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");

		mvc.perform(put("/api/v1/specialist-profile").with(specialist(specialistId))
				.header("If-Match", submittedEtag).contentType(MediaType.APPLICATION_JSON)
				.content(profileBody("Updated")))
				.andExpect(status().isOk()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.submittedAt").isEmpty());
		mvc.perform(get("/api/v1/admin/specialist-profiles").with(admin(adminId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
	}

	@Test
	void rolesVersionsAndProfileValidationFailClosed() throws Exception {
		var specialistId = UUID.randomUUID();
		mvc.perform(put("/api/v1/specialist-profile").with(specialist(specialistId))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("Valid"))).andExpect(status().isCreated());

		mvc.perform(put("/api/v1/specialist-profile").with(specialist(specialistId))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("No version")))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("SPECIALIST_PROFILE_VERSION_MISMATCH"));
		mvc.perform(post("/api/v1/specialist-profile/submit").with(specialist(specialistId)))
				.andExpect(status().isPreconditionRequired())
				.andExpect(jsonPath("$.code").value("PROFILE_VERSION_REQUIRED"));
		mvc.perform(put("/api/v1/specialist-profile").with(specialist(UUID.randomUUID()))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("Bad zone").replace("Asia/Ho_Chi_Minh", "bad-zone")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(get("/api/v1/specialist-profile").with(user(UUID.randomUUID())))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/admin/specialist-profiles").with(specialist(specialistId)))
				.andExpect(status().isForbidden());
	}

	private RequestPostProcessor specialist(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"));
	}

	private RequestPostProcessor admin(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
	}

	private RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private String profileBody(String displayName) {
		return """
				{"displayName":"%s","bio":"Non-clinical support profile",\
				"supportAreas":["DEPRESSIVE_SYMPTOMS","ANXIETY_SYMPTOMS"],\
				"languages":["vi","en"],"yearsOfExperience":5,"timezone":"Asia/Ho_Chi_Minh"}
				""".formatted(displayName);
	}
}
