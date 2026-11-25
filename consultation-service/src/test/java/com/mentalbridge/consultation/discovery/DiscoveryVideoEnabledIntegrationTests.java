package com.mentalbridge.consultation.discovery;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mentalbridge.consultation.availability.video-enabled=true")
@AutoConfigureMockMvc
@Transactional
class DiscoveryVideoEnabledIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@MockitoBean ScreeningContextResolver screeningContexts;

	@Test
	void enabledCapabilityReturnsOnlyAnInternalVideoSlotIdentity() throws Exception {
		var specialist = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (
				 account_id, display_name, biography, years_experience, timezone, approval_status,
				 submitted_at, reviewed_at, reviewed_by
				) values (:id, 'Video specialist', 'Synthetic public biography', 4,
				 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialist).param("admin", UUID.randomUUID()).update();
		jdbc.sql("insert into specialist_profile_support_area values (:id, 'ANXIETY_SYMPTOMS')")
				.param("id", specialist).update();
		jdbc.sql("insert into specialist_profile_language values (:id, 'vi')")
				.param("id", specialist).update();
		var slot = UUID.randomUUID();
		var start = Instant.now().plusSeconds(86_400);
		jdbc.sql("""
				insert into availability_slot (
				 id, specialist_account_id, start_at, end_at, timezone, modality,
				 idempotency_key, created_at, updated_at
				) values (:id, :specialist, :start, :end, 'Asia/Ho_Chi_Minh', 'IN_APP_VIDEO',
				 :key, now(), now())
				""").param("id", slot).param("specialist", specialist).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3_600)))
				.param("key", "discovery-video-" + slot).update();

		mvc.perform(get("/api/v1/specialists").param("modality", "IN_APP_VIDEO")
				.with(jwt().jwt(token -> token.subject(UUID.randomUUID().toString()).tokenValue("token"))
						.authorities(new SimpleGrantedAuthority("ROLE_USER"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.videoEnabled").value(true))
				.andExpect(jsonPath("$.items[0].selectableSlots[0].id").value(slot.toString()))
				.andExpect(jsonPath("$.items[0].selectableSlots[0].modality").value("IN_APP_VIDEO"))
				.andExpect(jsonPath("$.items[0].selectableSlots[0].meetingLink").doesNotExist())
				.andExpect(jsonPath("$.items[0].selectableSlots[0].url").doesNotExist());
	}
}
