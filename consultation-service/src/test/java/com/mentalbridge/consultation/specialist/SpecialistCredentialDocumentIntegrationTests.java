package com.mentalbridge.consultation.specialist;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SpecialistCredentialDocumentIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@MockitoBean SpecialistCredentialStorage storage;

	@Test
	void ownerUploadAdminReviewAndOptInBadgeStaySeparated() throws Exception {
		var ownerId = UUID.randomUUID();
		var otherId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		mvc.perform(put("/api/v1/specialist-profile").with(specialist(ownerId))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody()))
				.andExpect(status().isCreated());
		when(storage.upload(any(byte[].class), anyString(), eq("image/png")))
				.thenAnswer(invocation -> new SpecialistCredentialStorage.StoredFile(
						invocation.getArgument(1), "png", 8));
		when(storage.viewUrl(anyString(), eq("png"), eq("image/png"), any(Instant.class), eq(false)))
				.thenReturn("https://example.test/private-credential");

		var uploaded = mvc.perform(multipart("/api/v1/specialist-profile/documents")
					.file(file()).param("title", "Clinical degree").param("issuer", "University")
					.param("issueYear", "2020").param("credentialType", "DEGREE")
					.with(specialist(ownerId)))
				.andExpect(status().isCreated()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.verificationStatus").value("PENDING"))
				.andExpect(jsonPath("$.badgeVisible").value(false))
				.andExpect(jsonPath("$.storageKey").doesNotExist()).andReturn();
		var documentId = UUID.fromString(com.jayway.jsonpath.JsonPath.read(
				uploaded.getResponse().getContentAsString(), "$.id"));
		var etag = uploaded.getResponse().getHeader("ETag");
		mvc.perform(get("/api/v1/specialist-profile/documents").with(specialist(ownerId)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(get("/api/v1/admin/specialist-profiles/documents/pending").with(admin(adminId)))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));

		mvc.perform(get("/api/v1/specialist-profile/documents").with(specialist(otherId)))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/specialist-profile/documents/{id}/file", documentId)
					.with(specialist(otherId))).andExpect(status().isNotFound());
		mvc.perform(get("/api/v1/specialist-profile/documents/{id}/file", documentId)
					.with(specialist(ownerId))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.url").value("https://example.test/private-credential"));
		mvc.perform(get("/api/v1/admin/specialist-profiles/documents/{id}/file", documentId)
					.with(admin(adminId))).andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"));
		mvc.perform(post("/api/v1/admin/specialist-profiles/documents/{id}/verify", documentId)
					.with(specialist(ownerId)).header("If-Match", etag))
				.andExpect(status().isForbidden());
		mvc.perform(multipart("/api/v1/specialist-profile/documents")
					.file(file()).param("title", "Clinical degree").param("issuer", "University")
					.param("issueYear", "2020").param("credentialType", "DEGREE")
					.with(user(UUID.randomUUID()))).andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/specialist-profile/documents/{id}/badge", documentId)
					.with(specialist(ownerId)).header("If-Match", etag)
					.contentType(MediaType.APPLICATION_JSON).content("{\"visible\":true}"))
				.andExpect(status().isConflict());
		mvc.perform(put("/api/v1/specialist-profile/documents/{id}/badge", documentId)
					.with(specialist(ownerId)).header("If-Match", etag)
					.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get("/api/v1/specialists/{id}/credential-badges", ownerId)
					.with(user(UUID.randomUUID()))).andExpect(status().isNotFound());

		var reviewed = mvc.perform(post("/api/v1/admin/specialist-profiles/documents/{id}/verify", documentId)
					.with(admin(adminId)).header("If-Match", etag))
				.andExpect(status().isOk()).andExpect(jsonPath("$.verificationStatus").value("VERIFIED"))
				.andExpect(jsonPath("$.badgeVisible").value(false)).andReturn();
		mvc.perform(post("/api/v1/admin/specialist-profiles/documents/{id}/verify", documentId)
					.with(admin(adminId)).header("If-Match", etag))
				.andExpect(status().isPreconditionFailed());
		mvc.perform(put("/api/v1/specialist-profile/documents/{id}/badge", documentId)
					.with(specialist(ownerId)).header("If-Match", reviewed.getResponse().getHeader("ETag"))
					.contentType(MediaType.APPLICATION_JSON).content("{\"visible\":true}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.badgeVisible").value(true));

		var profileEtag = mvc.perform(get("/api/v1/specialist-profile").with(specialist(ownerId)))
				.andReturn().getResponse().getHeader("ETag");
		var submitted = mvc.perform(post("/api/v1/specialist-profile/submit").with(specialist(ownerId))
					.header("If-Match", profileEtag)).andExpect(status().isOk()).andReturn();
		mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/approve", ownerId)
					.with(admin(adminId)).header("If-Match", submitted.getResponse().getHeader("ETag")))
				.andExpect(status().isOk());
		mvc.perform(get("/api/v1/specialists/{id}/credential-badges", ownerId)
					.with(user(UUID.randomUUID()))).andExpect(status().isOk())
				.andExpect(jsonPath("$[0].title").value("Clinical degree"))
				.andExpect(jsonPath("$[0].credentialType").value("DEGREE"))
				.andExpect(jsonPath("$[0].issuer").doesNotExist())
				.andExpect(jsonPath("$[0].fileName").doesNotExist());
	}

	@Test
	void rejectsInvalidFilesAndDoesNotPersistWhenPrivateStorageFails() throws Exception {
		var ownerId = UUID.randomUUID();
		mvc.perform(put("/api/v1/specialist-profile").with(specialist(ownerId))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody()))
				.andExpect(status().isCreated());
		mvc.perform(multipart("/api/v1/specialist-profile/documents")
					.file(new MockMultipartFile("file", "degree.pdf", "application/pdf", "invalid".getBytes()))
					.param("title", "Clinical degree").param("issuer", "University")
					.param("issueYear", "2020").param("credentialType", "DEGREE")
					.with(specialist(ownerId)))
				.andExpect(status().isBadRequest());
		when(storage.upload(any(byte[].class), anyString(), eq("image/png")))
				.thenThrow(new CloudinarySpecialistCredentialStorage.StorageFailure(new IllegalStateException("offline")));
		mvc.perform(multipart("/api/v1/specialist-profile/documents")
					.file(file()).param("title", "Clinical degree").param("issuer", "University")
					.param("issueYear", "2020").param("credentialType", "DEGREE")
					.with(specialist(ownerId)))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("CREDENTIAL_STORAGE_UNAVAILABLE"));
		mvc.perform(get("/api/v1/specialist-profile/documents").with(specialist(ownerId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(0)));
	}

	private MockMultipartFile file() {
		return new MockMultipartFile("file", "degree.png", "image/png",
				new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a});
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

	private String profileBody() {
		return """
				{"displayName":"Nguyen An","bio":"Non-clinical support profile",\
				"supportAreas":["DEPRESSIVE_SYMPTOMS","ANXIETY_SYMPTOMS"],\
				"languages":["vi","en"],"yearsOfExperience":5,"timezone":"Asia/Ho_Chi_Minh"}
				""";
	}
}
