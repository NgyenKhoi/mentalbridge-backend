package com.mentalbridge.consultation.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ProfileAmendmentIntegrationTests extends ConsultationTestProperties {

	private static final String OWN = "/api/v1/specialist-profile/amendments";
	private static final String ADMIN = "/api/v1/admin/specialist-profiles/amendments";
	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;

	@Test
	void amendmentReloadAndApprovalKeepDiscoveryLiveAndRetainApprovedVersions() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var initial = approved(owner, reviewer);
		var slot = slot(owner);
		var appointment = confirmedAppointment(owner, slot);
		slot(owner, 3);
		var appointmentBefore = appointmentState(appointment);
		var started = start(owner, etag(initial));
		var id = id(started);
		assertPublic(owner, "Original");
		assertThat(appointmentState(appointment)).isEqualTo(appointmentBefore);
		mvc.perform(get(OWN + "/current").with(actor(owner, "SPECIALIST")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.approvedProfile.displayName").value("Original"))
				.andExpect(jsonPath("$.amendment.status").value("DRAFT"));
		var edited = edit(owner, id, etag(started), "Updated");
		var submitted = command(OWN, id, "submit", owner, "SPECIALIST", etag(edited));
		assertPublic(owner, "Original");
		mvc.perform(get(ADMIN + "/{id}", id).with(actor(reviewer, "ADMIN")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.approvedProfile.displayName").value("Original"))
				.andExpect(jsonPath("$.amendment.proposedProfile.displayName").value("Updated"));
		var promoted = command(ADMIN, id, "approve", reviewer, "ADMIN", etag(submitted));
		assertPublic(owner, "Updated");
		assertThat(appointmentState(appointment)).isEqualTo(appointmentBefore);
		assertThat(publicState(owner)).contains("APPROVED");
		assertThat(jdbc.sql("select published_version from specialist_profile where account_id=:id")
				.param("id", owner).query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("select profile_snapshot->>'displayName' from specialist_profile_approved_version where specialist_account_id=:id order by published_version")
				.param("id", owner).query(String.class).list()).containsExactly("Original", "Updated");
		assertThat(jdbc.sql("select status from availability_slot where id=:id").param("id", slot).query(String.class).single()).isEqualTo("ACTIVE");
		command(ADMIN, id, "approve", reviewer, "ADMIN", etag(promoted));
		assertThat(jdbc.sql("select count(*) from specialist_profile_approved_version where specialist_account_id=:id")
				.param("id", owner).query(Long.class).single()).isEqualTo(2);
		mvc.perform(post(ADMIN + "/{id}/approve", id).with(actor(reviewer, "ADMIN")).header("If-Match", etag(submitted)))
				.andExpect(status().isPreconditionFailed()).andExpect(jsonPath("$.code").value("PROFILE_AMENDMENT_VERSION_MISMATCH"));
	}

	@Test
	void rejectionAndCorrectionNeverChangeThePublishedSnapshotAndPreserveReviewPayload() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var initial = approved(owner, reviewer);
		var before = publicState(owner);
		var started = start(owner, etag(initial));
		var id = id(started);
		var edit = edit(owner, id, etag(started), "Needs review");
		var submit = command(OWN, id, "submit", owner, "SPECIALIST", etag(edit));
		var rejected = mvc.perform(post(ADMIN + "/{id}/reject", id).with(actor(reviewer, "ADMIN"))
				.header("If-Match", etag(submit)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"PROFILE_CONTENT_NOT_APPROVED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED")).andReturn();
		assertThat(publicState(owner)).isEqualTo(before);
		var corrected = edit(owner, id, etag(rejected), "Corrected");
		assertThat(json.readTree(corrected.getResponse().getContentAsString()).path("reasonCode").asText()).isEqualTo("PROFILE_CONTENT_NOT_APPROVED");
		assertThat(jdbc.sql("select proposed_profile->>'displayName' from specialist_profile_amendment_history where amendment_id=:id and actor_role='ADMIN'")
				.param("id", id).query(String.class).list()).containsExactly("Needs review");
		var resubmitted = command(OWN, id, "resubmit", owner, "SPECIALIST", etag(corrected));
		assertThat(publicState(owner)).isEqualTo(before);
		command(ADMIN, id, "approve", reviewer, "ADMIN", etag(resubmitted));
		assertThat(publicState(owner)).contains("Corrected");
	}

	@Test
	void wrongActorsInvalidFieldsMissingAndStaleVersionsFailClosed() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var approved = approved(owner, reviewer);
		var other = UUID.randomUUID();
		approved(other, reviewer);
		mvc.perform(get(OWN + "/current")).andExpect(status().isUnauthorized());
		mvc.perform(get(OWN + "/current").with(actor(owner, "USER"))).andExpect(status().isForbidden());
		mvc.perform(post(OWN).with(actor(owner, "SPECIALIST"))).andExpect(status().isPreconditionRequired());
		var started = start(owner, etag(approved));
		var id = id(started);
		mvc.perform(put(OWN + "/{id}", id).with(actor(other, "SPECIALIST")).header("If-Match", etag(started))
				.contentType(MediaType.APPLICATION_JSON).content(body("Attack"))).andExpect(status().isNotFound());
		mvc.perform(get(ADMIN + "/{id}", id).with(actor(owner, "SPECIALIST"))).andExpect(status().isForbidden());
		mvc.perform(put(OWN + "/{id}", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(started))
				.contentType(MediaType.APPLICATION_JSON).content(body("Invalid").replace("Asia/Ho_Chi_Minh", "bad-zone")))
				.andExpect(status().isBadRequest());
		mvc.perform(put(OWN + "/{id}", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(started))
				.contentType(MediaType.APPLICATION_JSON).content(body(""))).andExpect(status().isBadRequest());
		var edited = edit(owner, id, etag(started), "Valid change");
		mvc.perform(post(OWN + "/{id}/submit", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(started)))
				.andExpect(status().isPreconditionFailed());
		var submitted = command(OWN, id, "submit", owner, "SPECIALIST", etag(edited));
		mvc.perform(post(ADMIN + "/{id}/reject", id).with(actor(reviewer, "ADMIN")).header("If-Match", etag(submitted))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reasonCode\":\"POLICY_VIOLATION\"}"))
				.andExpect(status().isBadRequest());
		mvc.perform(get(ADMIN).with(actor(reviewer, "ADMIN")).param("limit", "101")).andExpect(status().isBadRequest());
	}

	@Test
	void editingPendingWithdrawsReviewAndOldApprovalCannotPublishTheNewDraft() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var initial = approved(owner, reviewer);
		var started = start(owner, etag(initial));
		var id = id(started);
		var submitted = command(OWN, id, "submit", owner, "SPECIALIST", etag(started));
		var edited = edit(owner, id, etag(submitted), "Not submitted");
		assertThat(json.readTree(edited.getResponse().getContentAsString()).path("status").asText()).isEqualTo("DRAFT");
		mvc.perform(post(ADMIN + "/{id}/approve", id).with(actor(reviewer, "ADMIN")).header("If-Match", etag(submitted)))
				.andExpect(status().isPreconditionFailed());
		mvc.perform(post(ADMIN + "/{id}/approve", id).with(actor(reviewer, "ADMIN")).header("If-Match", etag(edited)))
				.andExpect(status().isConflict());
		assertThat(publicState(owner)).contains("Original");
	}

	@Test
	void simultaneousStartsReuseOneDraftAndConcurrentEditReviewHasExactlyOneWinner() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var initial = approved(owner, reviewer);
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var starts = List.of(0, 1).stream().map(ignored -> workers.submit(() -> {
				ready.countDown();
				assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
				return start(owner, etag(initial));
			})).toList();
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			var first = starts.get(0).get(20, TimeUnit.SECONDS);
			var second = starts.get(1).get(20, TimeUnit.SECONDS);
			assertThat(id(first)).isEqualTo(id(second));
		}
		var current = mvc.perform(get(OWN + "/current").with(actor(owner, "SPECIALIST"))).andReturn();
		var amendmentId = UUID.fromString(json.readTree(current.getResponse().getContentAsString()).path("amendment").path("id").asText());
		var submitted = command(OWN, amendmentId, "submit", owner, "SPECIALIST", etag(current));
		var gate = new CountDownLatch(1);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var edit = workers.submit(() -> {
				assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(put(OWN + "/{id}", amendmentId).with(actor(owner, "SPECIALIST"))
						.header("If-Match", etag(submitted)).contentType(MediaType.APPLICATION_JSON).content(body("Racing draft")))
						.andReturn().getResponse().getStatus();
			});
			var approve = workers.submit(() -> {
				assertThat(gate.await(10, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post(ADMIN + "/{id}/approve", amendmentId).with(actor(reviewer, "ADMIN"))
						.header("If-Match", etag(submitted))).andReturn().getResponse().getStatus();
			});
			gate.countDown();
			assertThat(List.of(edit.get(20, TimeUnit.SECONDS), approve.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 412);
		}
		assertThat(publicState(owner)).doesNotContain("Racing draft");
	}

	@Test
	void suspensionBlocksAmendmentCommandsWithoutLettingReviewRestoreTheSpecialist() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var initial = approved(owner, reviewer);
		var started = start(owner, etag(initial));
		var id = id(started);
		var submitted = command(OWN, id, "submit", owner, "SPECIALIST", etag(started));
		mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/suspend", owner).with(actor(reviewer, "ADMIN"))
				.header("If-Match", etag(initial)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"QUALITY_REVIEW_REQUIRED\"}")).andExpect(status().isOk());
		mvc.perform(post(ADMIN + "/{id}/approve", id).with(actor(reviewer, "ADMIN")).header("If-Match", etag(submitted)))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SPECIALIST_NOT_APPROVED"));
		mvc.perform(put(OWN + "/{id}", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(submitted))
				.contentType(MediaType.APPLICATION_JSON).content(body("Suspended edit"))).andExpect(status().isConflict());
		assertThat(publicState(owner)).contains("SUSPENDED");
	}

	@ParameterizedTest
	@ValueSource(strings = { "DRAFT", "PENDING_REVIEW", "REJECTED" })
	void cancellationIsTerminalAuditedAndKeepsPublicAndOperationalData(String state) throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var initial = approved(owner, reviewer);
		var slot = slot(owner);
		var appointment = confirmedAppointment(owner, slot);
		var publicBefore = publicState(owner);
		var appointmentBefore = appointmentState(appointment);
		var started = start(owner, etag(initial));
		var id = id(started);
		var current = edit(owner, id, etag(started), "Private proposal");
		if (!state.equals("DRAFT")) current = command(OWN, id, "submit", owner, "SPECIALIST", etag(current));
		if (state.equals("REJECTED")) current = mvc.perform(post(ADMIN + "/{id}/reject", id).with(actor(reviewer, "ADMIN"))
				.header("If-Match", etag(current)).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"PROFILE_CONTENT_NOT_APPROVED\"}")).andExpect(status().isOk()).andReturn();
		var cancelled = command(OWN, id, "cancel", owner, "SPECIALIST", etag(current));
		assertThat(json.readTree(cancelled.getResponse().getContentAsString()).path("status").asText()).isEqualTo("CANCELLED");
		assertThat(publicState(owner)).isEqualTo(publicBefore);
		assertThat(appointmentState(appointment)).isEqualTo(appointmentBefore);
		assertThat(jdbc.sql("select status from availability_slot where id=:id").param("id", slot).query(String.class).single()).isEqualTo("ACTIVE");
		assertThat(jdbc.sql("select proposed_profile->>'displayName' from specialist_profile_amendment_history where amendment_id=:id and status='CANCELLED'")
				.param("id", id).query(String.class).list()).containsExactly("Private proposal");
		command(OWN, id, "cancel", owner, "SPECIALIST", etag(cancelled));
		assertThat(jdbc.sql("select count(*) from specialist_profile_amendment_history where amendment_id=:id and status='CANCELLED'")
				.param("id", id).query(Long.class).single()).isEqualTo(1);
		mvc.perform(post(OWN + "/{id}/cancel", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(current)))
				.andExpect(status().isPreconditionFailed());
		mvc.perform(put(OWN + "/{id}", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(cancelled))
				.contentType(MediaType.APPLICATION_JSON).content(body("Revive"))).andExpect(status().isConflict());
		mvc.perform(post(OWN + "/{id}/submit", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(cancelled)))
				.andExpect(status().isConflict());
		mvc.perform(post(ADMIN + "/{id}/approve", id).with(actor(reviewer, "ADMIN")).header("If-Match", etag(cancelled)))
				.andExpect(status().isConflict());
		var next = start(owner, etag(initial));
		assertThat(id(next)).isNotEqualTo(id);
		assertThat(json.readTree(next.getResponse().getContentAsString()).path("proposedProfile").path("displayName").asText()).isEqualTo("Original");
		if (state.equals("REJECTED")) assertThat(jdbc.sql("select count(*) from specialist_profile_amendment_history where amendment_id=:id and status='REJECTED' and reason_code='PROFILE_CONTENT_NOT_APPROVED'")
				.param("id", id).query(Long.class).single()).isEqualTo(1);
	}

	@Test
	void cancellationRequiresOwnerRoleAndVersionAndCannotUndoApproval() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var other = UUID.randomUUID();
		approved(other, reviewer);
		var started = start(owner, etag(approved(owner, reviewer)));
		var id = id(started);
		mvc.perform(post(OWN + "/{id}/cancel", id)).andExpect(status().isUnauthorized());
		mvc.perform(post(OWN + "/{id}/cancel", id).with(actor(owner, "USER"))).andExpect(status().isForbidden());
		mvc.perform(post(OWN + "/{id}/cancel", id).with(actor(other, "SPECIALIST")).header("If-Match", etag(started)))
				.andExpect(status().isNotFound());
		mvc.perform(post(OWN + "/{id}/cancel", id).with(actor(owner, "SPECIALIST"))).andExpect(status().isPreconditionRequired());
		var submitted = command(OWN, id, "submit", owner, "SPECIALIST", etag(started));
		var promoted = command(ADMIN, id, "approve", reviewer, "ADMIN", etag(submitted));
		mvc.perform(post(OWN + "/{id}/cancel", id).with(actor(owner, "SPECIALIST")).header("If-Match", etag(promoted)))
				.andExpect(status().isConflict());
	}

	@Test
	void cancellationAndApprovalRaceHasOnlyOneWinner() throws Exception {
		var owner = UUID.randomUUID();
		var reviewer = UUID.randomUUID();
		var started = start(owner, etag(approved(owner, reviewer)));
		var id = id(started);
		var edited = edit(owner, id, etag(started), "Reviewed proposal");
		var submitted = command(OWN, id, "submit", owner, "SPECIALIST", etag(edited));
		var ready = new CountDownLatch(2);
		var go = new CountDownLatch(1);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var cancel = workers.submit(() -> {
				ready.countDown();
				assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post(OWN + "/{id}/cancel", id).with(actor(owner, "SPECIALIST"))
						.header("If-Match", etag(submitted))).andReturn().getResponse().getStatus();
			});
			var approve = workers.submit(() -> {
				ready.countDown();
				assertThat(go.await(10, TimeUnit.SECONDS)).isTrue();
				return mvc.perform(post(ADMIN + "/{id}/approve", id).with(actor(reviewer, "ADMIN"))
						.header("If-Match", etag(submitted))).andReturn().getResponse().getStatus();
			});
			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			go.countDown();
			assertThat(List.of(cancel.get(20, TimeUnit.SECONDS), approve.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 412);
		}
		var terminal = jdbc.sql("select status from specialist_profile_amendment where id=:id").param("id", id).query(String.class).single();
		assertThat(publicState(owner)).contains(terminal.equals("CANCELLED") ? "Original" : "Reviewed proposal");
		assertThat(jdbc.sql("select count(*) from specialist_profile_amendment_history where amendment_id=:id and status in ('CANCELLED','APPROVED')")
				.param("id", id).query(Long.class).single()).isEqualTo(1);
	}

	private MvcResult approved(UUID owner, UUID reviewer) throws Exception {
		var saved = mvc.perform(put("/api/v1/specialist-profile").with(actor(owner, "SPECIALIST"))
				.contentType(MediaType.APPLICATION_JSON).content(body("Original"))).andExpect(status().isCreated()).andReturn();
		var submitted = mvc.perform(post("/api/v1/specialist-profile/submit").with(actor(owner, "SPECIALIST"))
				.header("If-Match", etag(saved))).andExpect(status().isOk()).andReturn();
		return mvc.perform(post("/api/v1/admin/specialist-profiles/{id}/approve", owner).with(actor(reviewer, "ADMIN"))
				.header("If-Match", etag(submitted))).andExpect(status().isOk()).andReturn();
	}

	private MvcResult start(UUID owner, String version) throws Exception {
		return mvc.perform(post(OWN).with(actor(owner, "SPECIALIST")).header("If-Match", version))
				.andExpect(status().isOk()).andReturn();
	}

	private MvcResult edit(UUID owner, UUID id, String version, String name) throws Exception {
		return mvc.perform(put(OWN + "/{id}", id).with(actor(owner, "SPECIALIST")).header("If-Match", version)
				.contentType(MediaType.APPLICATION_JSON).content(body(name))).andExpect(status().isOk()).andReturn();
	}

	private MvcResult command(String root, UUID id, String action, UUID actor, String role, String version) throws Exception {
		return mvc.perform(post(root + "/{id}/" + action, id).with(actor(actor, role)).header("If-Match", version))
				.andExpect(status().isOk()).andReturn();
	}

	private UUID slot(UUID owner) throws Exception {
		return slot(owner, 2);
	}

	private UUID slot(UUID owner, int days) throws Exception {
		var starts = Instant.now().plusSeconds(days * 86400L).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		var response = mvc.perform(post("/api/v1/availability-slots").with(actor(owner, "SPECIALIST"))
				.header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"startAt\":\"%s\",\"endAt\":\"%s\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"modality\":\"IN_APP_CHAT\"}"
						.formatted(starts, starts.plusSeconds(3600))))
				.andExpect(status().isCreated()).andReturn();
		return id(response);
	}

	private void assertPublic(UUID owner, String name) throws Exception {
		mvc.perform(get("/api/v1/specialists/{id}", owner).with(actor(UUID.randomUUID(), "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value(name));
	}

	private UUID confirmedAppointment(UUID owner, UUID slot) {
		var user = UUID.randomUUID();
		var period = UUID.randomUUID();
		var credit = UUID.randomUUID();
		var appointment = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (id, account_id, plan_version, credit_policy_version,
				package_code, source, source_reference, period_start, period_end, allocated_count, created_at, updated_at)
				values (:id, :user, 'amendment-test-v1', 'consultation-credit-v1', 'PLUS', 'DEMO',
				'amendment-test', now()-interval '1 day', now()+interval '30 days', 1, now(), now())
				""").param("id", period).param("user", user).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, appointment_id, created_at, updated_at)
				values (:id, :period, 1, 'HELD', :appointment, now(), now())
				""").param("id", credit).param("period", period).param("appointment", appointment).update();
		jdbc.sql("""
				insert into appointment (id, availability_slot_id, service_credit_id, user_account_id,
				specialist_account_id, status, modality, scheduled_start_at, scheduled_end_at,
				display_timezone, decision_deadline_at, idempotency_key, requested_at, decided_at,
				decision_reason, created_at, updated_at)
				select :id, id, :credit, :user, :owner, 'CONFIRMED', modality, start_at, end_at,
				timezone, start_at-interval '2 hours', :key, now(), now(), 'SPECIALIST_ACCEPTED', now(), now()
				from availability_slot where id=:slot
				""").param("id", appointment).param("credit", credit).param("user", user).param("owner", owner)
				.param("key", UUID.randomUUID().toString()).param("slot", slot).update();
		return appointment;
	}

	private String appointmentState(UUID id) {
		return jdbc.sql("select row_to_json(a)::text from appointment a where id=:id")
				.param("id", id).query(String.class).single();
	}

	private String publicState(UUID owner) {
		return jdbc.sql("select row_to_json(p)::text from specialist_profile p where account_id=:id")
				.param("id", owner).query(String.class).single();
	}

	private String etag(MvcResult result) { return result.getResponse().getHeader("ETag"); }
	private UUID id(MvcResult result) throws Exception { return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).path("id").asText()); }
	private RequestPostProcessor actor(UUID id, String role) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority("ROLE_" + role));
	}
	private String body(String name) {
		return "{\"displayName\":\"%s\",\"bio\":\"Non-clinical support\",\"supportAreas\":[\"ANXIETY_SYMPTOMS\"],\"languages\":[\"vi\"],\"yearsOfExperience\":5,\"timezone\":\"Asia/Ho_Chi_Minh\"}".formatted(name);
	}
}
