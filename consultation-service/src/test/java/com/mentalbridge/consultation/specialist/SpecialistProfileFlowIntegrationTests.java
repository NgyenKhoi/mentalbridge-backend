package com.mentalbridge.consultation.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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

	@Test
	void adminRejectsAndSpecialistEditsAndResubmitsTheSameProfile() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var submittedEtag = createAndSubmit(specialistId);

		var rejected = mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/reject", specialistId)
				.with(admin(adminId)).header("If-Match", submittedEtag)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"PROFILE_INFORMATION_INCOMPLETE\"}"))
				.andExpect(status().isOk()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.approvalStatus").value("REJECTED"))
				.andExpect(jsonPath("$.decisionReasonCode").value("PROFILE_INFORMATION_INCOMPLETE"))
				.andReturn();

		var edited = mvc.perform(put("/api/v1/specialist-profile").with(specialist(specialistId))
				.header("If-Match", rejected.getResponse().getHeader("ETag"))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("Updated after feedback")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.approvalStatus").value("REJECTED"))
				.andExpect(jsonPath("$.decisionReasonCode").value("PROFILE_INFORMATION_INCOMPLETE"))
				.andReturn();

		mvc.perform(post("/api/v1/specialist-profile/resubmit").with(specialist(specialistId))
				.header("If-Match", edited.getResponse().getHeader("ETag")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.approvalStatus").value("PENDING"))
				.andExpect(jsonPath("$.decisionReasonCode").isEmpty())
				.andExpect(jsonPath("$.reviewedAt").isEmpty());

		assertThat(jdbc.sql("""
				select approval_status from specialist_profile_status_history
				where specialist_account_id=:id order by occurred_at, id
				""").param("id", specialistId).query(String.class).list())
				.containsExactly("PENDING", "REJECTED", "PENDING");
	}

	@Test
	void suspensionCancelsFutureAppointmentsAndReleasesCreditsExactlyOnce() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var approvedEtag = approve(specialistId, adminId);
		var userId = UUID.randomUUID();
		var appointmentId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		var slotId = UUID.randomUUID();
		var start = OffsetDateTime.now().plusDays(14).withNano(0);
		insertFutureAppointment(specialistId, userId, appointmentId, creditId, slotId, start);

		var suspended = mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/suspend", specialistId)
				.with(admin(adminId)).header("If-Match", approvedEtag)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"QUALITY_REVIEW_REQUIRED\"}"))
				.andExpect(status().isOk()).andExpect(header().exists("ETag"))
				.andExpect(jsonPath("$.profile.approvalStatus").value("SUSPENDED"))
				.andExpect(jsonPath("$.effects.withdrawnAvailabilitySlots").value(1))
				.andExpect(jsonPath("$.effects.cancelledAppointments").value(1))
				.andExpect(jsonPath("$.effects.releasedCredits").value(1)).andReturn();

		assertThat(jdbc.sql("select status from appointment where id=:id")
				.param("id", appointmentId).query(String.class).single()).isEqualTo("CANCELLED");
		assertThat(jdbc.sql("select state from service_credit where id=:id")
				.param("id", creditId).query(String.class).single()).isEqualTo("AVAILABLE");
		assertThat(jdbc.sql("select status from availability_slot where id=:id")
				.param("id", slotId).query(String.class).single()).isEqualTo("WITHDRAWN");
		mvc.perform(post("/api/v1/availability-slots").with(specialist(specialistId))
				.header("Idempotency-Key", "suspended-specialist-slot")
				.contentType(MediaType.APPLICATION_JSON).content("""
						{"startAt":"2098-01-02T02:00:00Z","endAt":"2098-01-02T03:00:00Z",
						 "timezone":"Asia/Ho_Chi_Minh","modality":"IN_APP_CHAT"}
						"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SPECIALIST_NOT_APPROVED"));
		mvc.perform(get("/api/v1/admin/specialist-profiles?status=SUSPENDED").with(admin(adminId)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].decisionReasonCode").value("QUALITY_REVIEW_REQUIRED"));

		var replay = mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/suspend", specialistId)
				.with(admin(adminId)).header("If-Match", suspended.getResponse().getHeader("ETag"))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"QUALITY_REVIEW_REQUIRED\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.effects.cancelledAppointments").value(0))
				.andExpect(jsonPath("$.effects.releasedCredits").value(0)).andReturn();

		var restored = mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/restore", specialistId)
				.with(admin(adminId)).header("If-Match", replay.getResponse().getHeader("ETag")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.approvalStatus").value("APPROVED"))
				.andExpect(jsonPath("$.decisionReasonCode").isEmpty()).andReturn();

		mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/restore", specialistId)
				.with(admin(adminId)).header("If-Match", restored.getResponse().getHeader("ETag")))
				.andExpect(status().isOk())
				.andExpect(header().string("ETag", restored.getResponse().getHeader("ETag")));
		assertThat(jdbc.sql("select status from appointment where id=:id")
				.param("id", appointmentId).query(String.class).single()).isEqualTo("CANCELLED");
		assertThat(jdbc.sql("select status from availability_slot where id=:id")
				.param("id", slotId).query(String.class).single()).isEqualTo("WITHDRAWN");
		assertThat(jdbc.sql("""
				select count(*) from service_credit_ledger
				where credit_id=:creditId and event_type='RELEASED'
				""").param("creditId", creditId).query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("""
				select approval_status from specialist_profile_status_history
				where specialist_account_id=:id order by occurred_at, id
				""").param("id", specialistId).query(String.class).list())
				.containsExactly("PENDING", "APPROVED", "SUSPENDED", "APPROVED");
	}

	@Test
	void invalidLifecycleTransitionsReasonsAndRolesFailClosed() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var submittedEtag = createAndSubmit(specialistId);

		mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/suspend", specialistId)
				.with(admin(adminId)).header("If-Match", submittedEtag)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"POLICY_VIOLATION\"}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SPECIALIST_PROFILE_NOT_SUSPENDABLE"));
		mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/reject", specialistId)
				.with(admin(adminId)).header("If-Match", submittedEtag)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"POLICY_VIOLATION\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
		mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/reject", specialistId)
				.with(specialist(specialistId)).header("If-Match", submittedEtag)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"PROFILE_INFORMATION_INCOMPLETE\"}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/v1/specialist-profile/resubmit").with(specialist(specialistId))
				.header("If-Match", submittedEtag)).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("SPECIALIST_PROFILE_NOT_RESUBMITTABLE"));
	}

	private String createAndSubmit(UUID specialistId) throws Exception {
		var created = mvc.perform(put("/api/v1/specialist-profile").with(specialist(specialistId))
				.contentType(MediaType.APPLICATION_JSON).content(profileBody("Lifecycle specialist")))
				.andExpect(status().isCreated()).andReturn();
		return mvc.perform(post("/api/v1/specialist-profile/submit").with(specialist(specialistId))
				.header("If-Match", created.getResponse().getHeader("ETag")))
				.andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
	}

	private String approve(UUID specialistId, UUID adminId) throws Exception {
		var submittedEtag = createAndSubmit(specialistId);
		return mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/approve", specialistId)
				.with(admin(adminId)).header("If-Match", submittedEtag))
				.andExpect(status().isOk()).andReturn().getResponse().getHeader("ETag");
	}

	private void insertFutureAppointment(UUID specialistId, UUID userId, UUID appointmentId,
			UUID creditId, UUID slotId, OffsetDateTime start) {
		var now = OffsetDateTime.now().withNano(0);
		jdbc.sql("""
				insert into availability_slot (
				    id, specialist_account_id, start_at, end_at, timezone, modality,
				    idempotency_key, created_at, updated_at
				) values (:id, :specialistId, :start, :end, 'Asia/Ho_Chi_Minh', 'IN_APP_CHAT',
				    'suspension-slot-key-0001', :now, :now)
				""").param("id", slotId).param("specialistId", specialistId).param("start", start)
				.param("end", start.plusHours(1)).param("now", now).update();
		var periodId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				    id, account_id, plan_version, package_code, source, source_reference,
				    period_start, period_end, allocated_count, created_at, updated_at
				) values (:id, :userId, 'lifecycle-test-v1', 'PLUS', 'DEMO',
				    'mb-360-suspension-test', :periodStart, :periodEnd, 1, :now, :now)
				""").param("id", periodId).param("userId", userId).param("periodStart", now.minusDays(1))
				.param("periodEnd", now.plusDays(30)).param("now", now).update();
		jdbc.sql("""
				insert into service_credit (
				    id, period_id, ordinal, state, appointment_id, created_at, updated_at
				) values (:id, :periodId, 1, 'HELD', :appointmentId, :now, :now)
				""").param("id", creditId).param("periodId", periodId)
				.param("appointmentId", appointmentId).param("now", now).update();
		jdbc.sql("""
				insert into appointment (
				    id, availability_slot_id, service_credit_id, user_account_id,
				    specialist_account_id, status, modality, scheduled_start_at,
				    scheduled_end_at, display_timezone, decision_deadline_at,
				    idempotency_key, requested_at,
				    created_at, updated_at
				) values (:id, :slotId, :creditId, :userId, :specialistId, 'CONFIRMED',
				    'IN_APP_CHAT', :start, :end, 'Asia/Ho_Chi_Minh', :deadline,
				    'suspension-appointment-request', :requestedAt, :now, :now)
				""").param("id", appointmentId).param("slotId", slotId).param("creditId", creditId)
				.param("userId", userId).param("specialistId", specialistId).param("start", start)
				.param("end", start.plusHours(1)).param("deadline", start.minusHours(2))
				.param("requestedAt", now).param("now", now).update();
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
