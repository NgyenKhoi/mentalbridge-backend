package com.mentalbridge.care.profile;

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
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfileConsentHistoryIntegrationTests extends CareTestProperties {

	private static final UUID DEFINITION_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");

	@Autowired MockMvc mvc;
	@Autowired ObjectMapper objectMapper;

	@Test
	void firstTimeUserGetsProfileOnboardingStateAndEmptyConsents() throws Exception {
		var userId = UUID.randomUUID();

		mvc.perform(get("/api/v1/profile").with(user(userId)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
		mvc.perform(get("/api/v1/consents").with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.decisions.length()").value(0));
	}

	@Test
	void profileUsesJwtOwnershipAndOptimisticConcurrency() throws Exception {
		var userId = UUID.randomUUID();
		mvc.perform(put("/api/v1/profile").with(user(userId)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"Lan\",\"dateOfBirth\":\"2000-01-01\",\"gender\":\"OTHER\"}"))
				.andExpect(status().isCreated()).andExpect(header().string("ETag", "\"0\""))
				.andExpect(jsonPath("$.accountId").value(userId.toString()))
				.andExpect(jsonPath("$.locale").value("vi-VN"))
				.andExpect(jsonPath("$.timezone").value("Asia/Ho_Chi_Minh"))
				.andExpect(jsonPath("$.reminderEnabled").value(false));

		mvc.perform(put("/api/v1/profile").with(user(userId)).contentType(MediaType.APPLICATION_JSON)
				.content(profileBody("Lan stale")))
				.andExpect(status().isPreconditionFailed())
				.andExpect(jsonPath("$.code").value("PROFILE_VERSION_REQUIRED"));

		mvc.perform(put("/api/v1/profile").with(user(userId)).header("If-Match", "\"0\"")
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("Lan updated")))
				.andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""))
				.andExpect(jsonPath("$.displayName").value("Lan updated"));

		mvc.perform(get("/api/v1/profile").with(user(UUID.randomUUID())))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PROFILE_NOT_FOUND"));
		mvc.perform(get("/api/v1/profile").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isForbidden());
		mvc.perform(put("/api/v1/profile").with(user(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
				.content(profileBody("Invalid timezone").replace("Asia/Ho_Chi_Minh", "not-a-timezone")))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(put("/api/v1/profile").with(user(UUID.randomUUID())).contentType(MediaType.APPLICATION_JSON)
				.content("{\"displayName\":\"Invalid date\",\"dateOfBirth\":\"not-a-date\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
				.andExpect(jsonPath("$.violations[0].field").value("dateOfBirth"))
				.andExpect(jsonPath("$.violations[0].code").value("INVALID_DATE"));
	}

	@Test
	void consentIsBackendVersionedAppendOnlyIdempotentAndRevocable() throws Exception {
		var userId = createProfile();
		mvc.perform(get("/api/v1/privacy-disclosures/current"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.version").value("privacy-capstone-v2"))
				.andExpect(jsonPath("$.capstoneOnly").value(true));

		var grant = consentBody(true);
		var first = mvc.perform(post("/api/v1/consent-decisions").with(user(userId))
				.header("Idempotency-Key", "profile-consent-grant-0001")
				.contentType(MediaType.APPLICATION_JSON).content(grant))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.granted").value(true)).andReturn();
		var decisionId = objectMapper.readTree(first.getResponse().getContentAsString()).get("decisionId").asText();

		mvc.perform(post("/api/v1/consent-decisions").with(user(userId))
				.header("Idempotency-Key", "profile-consent-grant-0001")
				.contentType(MediaType.APPLICATION_JSON).content(grant))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.decisionId").value(decisionId));
		mvc.perform(post("/api/v1/consent-decisions").with(user(userId))
				.header("Idempotency-Key", "profile-consent-grant-0001")
				.contentType(MediaType.APPLICATION_JSON).content(consentBody(false)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

		mvc.perform(post("/api/v1/consent-decisions").with(user(userId))
				.header("Idempotency-Key", "profile-consent-revoke-001")
				.contentType(MediaType.APPLICATION_JSON).content(consentBody(false)))
				.andExpect(status().isCreated());
		mvc.perform(get("/api/v1/consents").with(user(userId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.decisions.length()").value(1))
				.andExpect(jsonPath("$.decisions[0].granted").value(false));
		mvc.perform(post("/api/v1/assessments").with(user(userId))
				.header("Idempotency-Key", "revoked-assessment-0001")
				.contentType(MediaType.APPLICATION_JSON).content(assessmentBody(0)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PRIVACY_DISCLOSURE_REQUIRED"));
	}

	@Test
	void historyUsesStableCursorAndReturnsSummaryOnly() throws Exception {
		var userId = createProfile();
		grant(userId, "history-consent-grant-01");
		for (var index = 0; index < 3; index++) {
			mvc.perform(post("/api/v1/assessments").with(user(userId))
					.header("Idempotency-Key", "history-assessment-000" + index)
					.contentType(MediaType.APPLICATION_JSON).content(assessmentBody(index)))
					.andExpect(status().isCreated());
			Thread.sleep(2);
		}

		var first = mvc.perform(get("/api/v1/assessments").with(user(userId)).queryParam("limit", "2"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(2))
				.andExpect(jsonPath("$.hasMore").value(true)).andExpect(jsonPath("$.items[0].answers").doesNotExist())
				.andReturn();
		var cursor = objectMapper.readTree(first.getResponse().getContentAsString()).get("nextCursor").asText();
		var second = mvc.perform(get("/api/v1/assessments").with(user(userId)).queryParam("limit", "2")
				.queryParam("cursor", cursor))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.hasMore").value(false)).andReturn();
		assertThat(objectMapper.readTree(first.getResponse().getContentAsString()).get("items").get(1).get("assessmentId"))
				.isNotEqualTo(objectMapper.readTree(second.getResponse().getContentAsString()).get("items").get(0).get("assessmentId"));

		mvc.perform(get("/api/v1/assessments").with(user(userId)).queryParam("cursor", "not-a-cursor"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		mvc.perform(get("/api/v1/assessments").with(user(UUID.randomUUID())))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
	}

	private UUID createProfile() throws Exception {
		var userId = UUID.randomUUID();
		mvc.perform(put("/api/v1/profile").with(user(userId)).contentType(MediaType.APPLICATION_JSON)
				.content(profileBody("Care test user"))).andExpect(status().isCreated());
		return userId;
	}

	private void grant(UUID userId, String key) throws Exception {
		mvc.perform(post("/api/v1/consent-decisions").with(user(userId)).header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content(consentBody(true))).andExpect(status().isCreated());
	}

	private RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}

	private String profileBody(String displayName) {
		return "{\"displayName\":\"" + displayName
				+ "\",\"dateOfBirth\":\"2000-01-01\",\"gender\":\"OTHER\",\"locale\":\"vi-VN\","
				+ "\"timezone\":\"Asia/Ho_Chi_Minh\",\"reminderEnabled\":false}";
	}

	private String consentBody(boolean granted) {
		return "{\"consentType\":\"PRIVACY_POLICY\",\"policyVersion\":\"privacy-capstone-v2\",\"granted\":"
				+ granted + "}";
	}

	private String assessmentBody(int itemNine) {
		var values = new int[] { 1, 1, 1, 1, 1, 1, 1, 0, itemNine };
		var answers = new StringBuilder();
		for (var index = 0; index < values.length; index++) {
			if (index > 0) answers.append(',');
			answers.append("{\"questionId\":\"11000000-0000-0000-0000-")
					.append(String.format("%012d", index + 1)).append("\",\"value\":").append(values[index]).append('}');
		}
		return "{\"questionnaireDefinitionId\":\"" + DEFINITION_ID
				+ "\",\"privacyPolicyVersion\":\"privacy-capstone-v2\",\"privacyDisclosureAcknowledged\":true,\"answers\":["
				+ answers + "]}";
	}
}
