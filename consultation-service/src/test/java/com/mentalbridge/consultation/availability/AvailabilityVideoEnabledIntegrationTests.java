package com.mentalbridge.consultation.availability;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mentalbridge.consultation.availability.video-enabled=true")
@AutoConfigureMockMvc
class AvailabilityVideoEnabledIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;

	@Test
	void approvedCapabilityPublishesVideoSlotWithoutExternalLink() throws Exception {
		var specialistId = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				account_id, display_name, biography, years_experience, timezone,
				approval_status, submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Video specialist', 'Synthetic integration profile', 3,
				'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", UUID.randomUUID()).update();
		var start = Instant.now().plusSeconds(432_000).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);

		mvc.perform(post("/api/v1/availability-slots")
				.with(jwt().jwt(token -> token.subject(specialistId.toString()))
						.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))
				.header("Idempotency-Key", "enabled-video-slot-key-01")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"startAt":"%s","endAt":"%s","timezone":"Asia/Ho_Chi_Minh",\
						"modality":"IN_APP_VIDEO"}
						""".formatted(start, start.plusSeconds(3_600))))
				.andExpect(status().isCreated()).andExpect(jsonPath("$.modality").value("IN_APP_VIDEO"))
				.andExpect(jsonPath("$.readiness").value("AVAILABLE"))
				.andExpect(jsonPath("$.meetingLink").doesNotExist())
				.andExpect(jsonPath("$.practiceLocationId").doesNotExist());
	}
}
