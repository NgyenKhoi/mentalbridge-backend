package com.mentalbridge.consultation.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.appointment.AppointmentDecisionService;
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"mentalbridge.consultation.availability.video-enabled=true",
		"mentalbridge.consultation.video.enabled=true",
		"mentalbridge.consultation.video.url=wss://synthetic-project.livekit.cloud",
		"mentalbridge.consultation.video.api-key=synthetic-test-key",
		"mentalbridge.consultation.video.api-secret=synthetic-test-secret"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VideoAccessIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired AppointmentDecisionService decisions;
	@Autowired VideoSessionTransactions transactions;
	@Autowired VideoSessionService sessions;
	@Autowired LiveKitProperties properties;
	@Autowired VideoEvidenceService evidence;
	@Autowired VideoEventRepository events;
	@Autowired VideoCompletionTransactions completions;
	@Autowired com.mentalbridge.consultation.appointment.ChatSessionSettlementService chatSettlement;
	@Autowired VideoCompletionService completionService;
	@MockitoBean VideoProvider provider;

	@AfterEach
	void resetDemoOptIn() { properties.setDemoImmediateAccessEnabled(false); }

	@Test
	void demoSourceAloneRetainsScheduledJoinUntilOperatorExplicitlyOptsIn() throws Exception {
		var fixture = fixture(Instant.now().plusSeconds(86400), true, true);
		mvc.perform(get("/api/v1/appointments/{id}/video-session", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("TOO_EARLY"));
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isConflict());
		verifyNoInteractions(provider);
	}

	@Test
	void optedInDemoUserBooksNearFutureVideoAndBothAssignedActorsJoinImmediately() throws Exception {
		properties.setDemoImmediateAccessEnabled(true);
		var fixture = fixture(Instant.now().plusSeconds(1800), true, true);
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		for (var actor : java.util.List.of(actor(fixture.user, "USER"), actor(fixture.specialist, "SPECIALIST"))) {
			mvc.perform(get("/api/v1/appointments/{id}/video-session", fixture.id).with(actor))
					.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("ACTIVE"))
					.andExpect(jsonPath("$.joinAllowed").value(true));
			mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor))
					.andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("LIVEKIT"));
		}
		assertThat(rooms(fixture.id)).isOne();
		assertThat(credit(fixture)).isEqualTo("HELD");
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(UUID.randomUUID(), "USER")))
				.andExpect(status().isNotFound());
	}

	@Test
	void enabledDemoDoesNotGrantPaidOrUnconfirmedAppointmentsEarlyAccess() throws Exception {
		properties.setDemoImmediateAccessEnabled(true);
		var paid = fixture();
		mvc.perform(get("/api/v1/appointments/{id}/video-session", paid.id).with(actor(paid.user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("TOO_EARLY"));
		mvc.perform(post("/api/v1/appointments/{id}/video-access", paid.id).with(actor(paid.user, "USER")))
				.andExpect(status().isConflict());
		var requested = fixture(Instant.now().plusSeconds(1800), true, false);
		mvc.perform(post("/api/v1/appointments/{id}/video-access", requested.id).with(actor(requested.user, "USER")))
				.andExpect(status().isConflict());
		assertThat(rooms(requested.id)).isZero();
		verifyNoInteractions(provider);
	}

	@Test
	void demoOptInCannotExtendScheduledEndOrReviveExpiredDemoEntitlement() throws Exception {
		properties.setDemoImmediateAccessEnabled(true);
		var fixture = fixture(Instant.now().plusSeconds(1800), true, true);
		jdbc.sql("update current_service_entitlement set effective_until=now()-interval '1 second',effective_from=now()-interval '1 day' where account_id=:user")
				.param("user", fixture.user).update();
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isConflict());
		setSchedule(fixture.id, Instant.now().minusSeconds(3601));
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isConflict());
		verifyNoInteractions(provider);
	}

	@Test
	void explicitEarlyDemoEndKeepsFinancialVerificationPendingAndNeverInfersCompletion() throws Exception {
		properties.setDemoImmediateAccessEnabled(true);
		var fixture = fixture(Instant.now().plusSeconds(1800), true, true);
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		var response = completionService.confirm(fixture.id, fixture.specialist, "SPECIALIST", 1,
				"demo-video-end-" + UUID.randomUUID()).response();
		assertThat(response.appointmentStatus()).isEqualTo("SESSION_ENDED");
		assertThat(response.verificationState()).isEqualTo("PENDING_VERIFICATION");
		assertThat(response.completionFactId()).isNull();
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", fixture.id)
				.query(Long.class).single()).isZero();
	}

	@Test
	void bothAssignedRolesUseTheSameOpaqueRoomAndRestrictedProviderBoundary() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return null;
		}).when(provider).prepareRoom(anyString());
		var user = mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.provider").value("LIVEKIT"))
				.andExpect(jsonPath("$.participantToken").value("synthetic-access-token"))
				.andReturn();
		var specialist = mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id)
				.with(actor(fixture.specialist, "SPECIALIST"))).andExpect(status().isOk()).andReturn();
		var room = json.readTree(user.getResponse().getContentAsByteArray()).get("roomName").asText();
		assertThat(json.readTree(specialist.getResponse().getContentAsByteArray()).get("roomName").asText()).isEqualTo(room);
		assertThat(room).doesNotContain(fixture.id.toString(), fixture.user.toString(), fixture.specialist.toString());
		assertThat(rooms(fixture.id)).isOne();
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(user.getResponse().getContentAsString()).doesNotContain("apiSecret", "synthetic-test-secret");
	}

	@Test
	void tokenRequestCannotSupplyClientRoomOrParticipantAuthority() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id)
				.with(actor(fixture.user, "USER")).contentType(MediaType.APPLICATION_JSON)
				.content("{\"roomName\":\"other-room\",\"identity\":\"other-user\"}"))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VIDEO_ACCESS_INVALID"));
		assertThat(rooms(fixture.id)).isZero();
		verifyNoInteractions(provider);
	}

	@Test
	void waitingPreviewIsLocalAndCannotIssueConnectionOrCreateAttendance() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().plusSeconds(240));
		mvc.perform(get("/api/v1/appointments/{id}/video-session", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("WAITING"))
				.andExpect(jsonPath("$.previewAllowed").value(true)).andExpect(jsonPath("$.joinAllowed").value(false));
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VIDEO_JOIN_NOT_ALLOWED"));
		assertThat(rooms(fixture.id)).isZero();
		verifyNoInteractions(provider);
	}

	@Test
	void crossAppointmentWrongRoleAndUnauthenticatedActorsCannotRequestTokens() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		var endpoint = "/api/v1/appointments/{id}/video-access";
		mvc.perform(post(endpoint, fixture.id)).andExpect(status().isUnauthorized());
		mvc.perform(post(endpoint, fixture.id).with(actor(UUID.randomUUID(), "USER"))).andExpect(status().isNotFound());
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.user, "SPECIALIST"))).andExpect(status().isNotFound());
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.specialist, "ADMIN"))).andExpect(status().isForbidden());
		verifyNoInteractions(provider);
	}

	@Test
	void endClosesMediaWithoutCompletingConsumingOrCreatingEarning() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		setSchedule(fixture.id, Instant.now().minusSeconds(3601));
		sessions.closeDueSessions();
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return null;
		}).when(provider).closeRoom(anyString(), any(), any());
		sessions.close(fixture.id);
		mvc.perform(get("/api/v1/appointments/{id}/video-session", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.phase").value("ENDED"));
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isConflict());
		assertThat(jdbc.sql("select state from appointment_video_room where appointment_id=:id").param("id", fixture.id)
				.query(String.class).single()).isEqualTo("CLOSED");
		assertThat(jdbc.sql("select completion_fact_id from appointment where id=:id").param("id", fixture.id)
				.query(UUID.class).optional()).isEmpty();
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", fixture.id)
				.query(Long.class).single()).isZero();
	}

	@Test
	void providerPreparationFailureLeavesCreditHeldAndNeverIssuesAToken() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_PROVIDER_UNAVAILABLE", "Video unavailable"))
				.when(provider).prepareRoom(anyString());
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isServiceUnavailable());
		assertThat(credit(fixture)).isEqualTo("HELD");
	}

	@Test
	void endRacingProviderCreationCannotIssueATokenOrResurrectAnOpenRoom() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		doAnswer(call -> {
			setSchedule(fixture.id, Instant.now().minusSeconds(3601));
			transactions.end(fixture.id);
			sessions.close(fixture.id);
			return null;
		}).when(provider).prepareRoom(anyString());
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isConflict());
		assertThat(jdbc.sql("select state from appointment_video_room where appointment_id=:id").param("id", fixture.id)
				.query(String.class).single()).isEqualTo("CLOSED");
		assertThat(credit(fixture)).isEqualTo("HELD");
		org.mockito.Mockito.verify(provider, org.mockito.Mockito.never()).issueToken(anyString(), anyString(), any(), any());
	}

	@Test
	void missingConfigurationAndUnapprovedSpecialistFailClosed() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		jdbc.sql("update specialist_profile set approval_status='SUSPENDED',decision_reason_code='ACCOUNT_REVIEW_REQUIRED' where account_id=:id")
				.param("id", fixture.specialist).update();
		mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isConflict());
		var original = properties.getApiSecret();
		try {
			properties.setApiSecret("");
			mvc.perform(post("/api/v1/appointments/{id}/video-access", fixture.id).with(actor(fixture.user, "USER")))
					.andExpect(status().isServiceUnavailable());
		}
		finally { properties.setApiSecret(original); }
		verifyNoInteractions(provider);
	}

	@Test
	void reorderedAndDuplicateEventsPersistWithoutCompletingAndReconnectDoesNotDoubleCount() throws Exception {
		var fixture = fixture();
		var start = Instant.now().minusSeconds(240).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		setSchedule(fixture.id, start);
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		var room = jdbc.sql("select room_name from appointment_video_room where appointment_id=:id").param("id", fixture.id).query(String.class).single();
		var userIdentity = identity(fixture.id, "user_identity");
		var specialistIdentity = identity(fixture.id, "specialist_identity");
		var userLeave = event(room, userIdentity, "user-session-1", "participant_left", start.plusSeconds(180));
		evidence.record(userLeave);
		evidence.record(event(room, specialistIdentity, "specialist-session-1", "participant_joined", start.plusSeconds(10)));
		evidence.record(event(room, userIdentity, "user-session-1", "participant_joined", start.plusSeconds(5)));
		evidence.record(userLeave);
		evidence.record(event(room, specialistIdentity, "specialist-session-1", "participant_left", start.plusSeconds(180)));
		evidence.record(event(room, userIdentity, "user-session-2", "participant_joined", start.plusSeconds(20)));
		evidence.record(event(room, userIdentity, "user-session-2", "participant_left", start.plusSeconds(160)));
		var persisted = events.findByAppointmentId(fixture.id);
		assertThat(persisted).hasSize(6);
		assertThat(VideoAttendance.evaluate(persisted, start, start.plusSeconds(3600)))
				.isEqualTo(new VideoAttendance.Result(true, 170));
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(jdbc.sql("select completion_fact_id from appointment where id=:id").param("id", fixture.id).query(UUID.class).optional()).isEmpty();
		var conflicting = new VideoProvider.VerifiedVideoEvent(userLeave.id(), userLeave.type(), room,
				userLeave.roomSid(), userLeave.identity(), userLeave.participantSid(), start.plusSeconds(181));
		evidence.record(conflicting);
		assertThat(events.findByAppointmentId(fixture.id)).hasSize(6);
		assertThat(jdbc.sql("select failure_reason from appointment_video_room where appointment_id=:id").param("id", fixture.id)
				.query(String.class).single()).isEqualTo("CONFLICTING_PROVIDER_EVENT");
	}

	@Test
	void verifiedWebhookUsesRawBodyAndRejectsInvalidSignatureWithoutEvidence() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		var room = jdbc.sql("select room_name from appointment_video_room where appointment_id=:id").param("id", fixture.id).query(String.class).single();
		var event = event(room, identity(fixture.id, "user_identity"), "user-session", "participant_joined", Instant.now().minusSeconds(60));
		when(provider.verifyWebhook("synthetic-raw-body", "synthetic-signature")).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return event;
		});
		mvc.perform(post("/internal/v1/video/livekit/webhook").contentType("application/webhook+json")
				.header("Authorization", "synthetic-signature").content("synthetic-raw-body")).andExpect(status().isNoContent());
		when(provider.verifyWebhook("synthetic-raw-body", "invalid")).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "VIDEO_WEBHOOK_INVALID", "Invalid signature"));
		mvc.perform(post("/internal/v1/video/livekit/webhook").contentType("application/webhook+json")
				.header("Authorization", "invalid").content("synthetic-raw-body")).andExpect(status().isUnauthorized());
		assertThat(events.findByAppointmentId(fixture.id)).hasSize(1);
	}

	@Test
	void signedRoomAndParticipantCallbacksReachOfficialReceiverAndNeverSettleWithoutConfirmation() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		var room = jdbc.sql("select room_name from appointment_video_room where appointment_id=:id")
				.param("id", fixture.id).query(String.class).single();
		var receiver = new LiveKitVideoProvider(properties);
		when(provider.verifyWebhook(any(), any())).thenAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return receiver.verifyWebhook(call.getArgument(0), call.getArgument(1));
		});
		var count = 0;
		for (var type : java.util.List.of("room_started", "participant_joined", "participant_left", "room_finished")) {
			var payload = json.createObjectNode().put("id", UUID.randomUUID().toString())
					.put("event", type).put("createdAt", Instant.now().minusSeconds(30).getEpochSecond());
			payload.putObject("room").put("name", room).put("sid", "room-incarnation");
			if (type.startsWith("participant_")) {
				payload.putObject("participant").put("identity", identity(fixture.id, "user_identity"))
						.put("sid", "user-session");
			}
			var body = json.writeValueAsString(payload);
			var signature = LiveKitVideoProviderTests.signature(properties, body, Instant.now().plusSeconds(120));
			mvc.perform(post("/internal/v1/video/livekit/webhook").contentType("application/webhook+json")
					.header("Authorization", signature).content(body)).andExpect(status().isNoContent());
			if (!type.equals("room_started")) count++;
			assertThat(events.findByAppointmentId(fixture.id)).hasSize(count);
			mvc.perform(post("/internal/v1/video/livekit/webhook").contentType("application/webhook+json")
					.header("Authorization", signature).content(body)).andExpect(status().isNoContent());
			mvc.perform(post("/internal/v1/video/livekit/webhook").contentType("application/webhook+json")
					.header("Authorization", "invalid").content(body)).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("VIDEO_WEBHOOK_INVALID"));
			mvc.perform(post("/internal/v1/video/livekit/webhook").contentType("application/webhook+json")
					.header("Authorization", signature).content(body + " ")).andExpect(status().isUnauthorized())
					.andExpect(jsonPath("$.code").value("VIDEO_WEBHOOK_INVALID"));
			if (type.startsWith("participant_")) {
				payload.remove("participant");
				var malformed = json.writeValueAsString(payload);
				mvc.perform(post("/internal/v1/video/livekit/webhook").contentType("application/webhook+json")
						.header("Authorization", LiveKitVideoProviderTests.signature(properties, malformed,
								Instant.now().plusSeconds(120))).content(malformed))
						.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("VIDEO_WEBHOOK_INVALID"));
			}
			assertThat(events.findByAppointmentId(fixture.id)).hasSize(count);
		}
		var finished = events.findByAppointmentId(fixture.id).stream()
				.filter(event -> event.type.equals("room_finished")).findFirst().orElseThrow();
		assertThat(finished.identity).isNull();
		assertThat(finished.participantSid).isNull();
		assertThat(finished.role).isNull();
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(jdbc.sql("select completion_fact_id from appointment where id=:id").param("id", fixture.id)
				.query(UUID.class).optional()).isEmpty();
	}

	@Test
	void retentionPreservesHoldsAndBusinessHistoryThenPurgesWithoutReplayResurrection() throws Exception {
		var fixture = fixture();
		setSchedule(fixture.id, Instant.now().minusSeconds(120));
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		var room = jdbc.sql("select room_name from appointment_video_room where appointment_id=:id").param("id", fixture.id).query(String.class).single();
		var joined = event(room, identity(fixture.id, "user_identity"), "user-session", "participant_joined", Instant.now().minusSeconds(60));
		evidence.record(joined);
		setSchedule(fixture.id, Instant.now().minusSeconds(3601));
		transactions.end(fixture.id);
		sessions.close(fixture.id);
		jdbc.sql("update appointment_video_room set closed_at=now()-interval '91 days',retention_hold_reason='LEGAL_HOLD' where appointment_id=:id").param("id", fixture.id).update();
		jdbc.sql("update appointment_video_event set received_at=now()-interval '91 days' where appointment_id=:id").param("id", fixture.id).update();
		var history = jdbc.sql("select count(*) from appointment_status_history where appointment_id=:id").param("id", fixture.id).query(Long.class).single();
		evidence.purge(fixture.id);
		assertThat(events.findByAppointmentId(fixture.id)).hasSize(1);
		jdbc.sql("update appointment_video_room set retention_hold_reason=null where appointment_id=:id").param("id", fixture.id).update();
		evidence.purge(fixture.id);
		assertThat(events.findByAppointmentId(fixture.id)).isEmpty();
		assertThat(jdbc.sql("select state from appointment_video_room where appointment_id=:id").param("id", fixture.id).query(String.class).single()).isEqualTo("PURGED");
		evidence.record(joined);
		assertThat(events.findByAppointmentId(fixture.id)).isEmpty();
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(jdbc.sql("select count(*) from appointment_status_history where appointment_id=:id").param("id", fixture.id).query(Long.class).single()).isEqualTo(history);
	}

	@Test
	void explicitEarlySpecialistConfirmationPlusCompleteEvidenceConsumesAndEarnsExactlyOnce() throws Exception {
		var fixture = joinedFixtureWithEvidence();
		var key = "finish-video-" + UUID.randomUUID();
		var first = mvc.perform(post("/api/v1/specialist/appointments/{id}/video-completion", fixture.id)
				.with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"1\"").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"CONSULTATION_FINISHED\"}"))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.verificationState").value("VERIFIED"))
				.andExpect(jsonPath("$.appointmentStatus").value("COMPLETED"))
				.andExpect(jsonPath("$.creditState").value("CONSUMED")).andReturn();
		var fact = json.readTree(first.getResponse().getContentAsByteArray()).get("completionFactId").asText();
		mvc.perform(post("/api/v1/specialist/appointments/{id}/video-completion", fixture.id)
				.with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"1\"").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"CONSULTATION_FINISHED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.completionFactId").value(fact));
		completions.reconcile(fixture.id);
		assertThat(jdbc.sql("select count(*) from service_credit_ledger where appointment_id=:id and event_type='CONSUMED'").param("id", fixture.id).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id and completion_fact_id=:fact and specialist_amount_minor=210000").param("id", fixture.id).param("fact", UUID.fromString(fact)).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select session_ended_at<scheduled_end_at from appointment where id=:id").param("id", fixture.id).query(Boolean.class).single()).isTrue();
		mvc.perform(get("/api/v1/appointments/{id}/video-session", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.completion.verificationState").value("VERIFIED"));
	}

	@Test
	void overlapAloneAndChatSettlementCannotCompleteVideoWithoutExplicitConfirmation() throws Exception {
		var fixture = joinedFixtureWithEvidence();
		setSchedule(fixture.id, Instant.now().minusSeconds(3601));
		chatSettlement.end(fixture.id);
		assertThat(jdbc.sql("select status from appointment where id=:id").param("id", fixture.id).query(String.class).single()).isEqualTo("CONFIRMED");
		transactions.end(fixture.id);
		sessions.close(fixture.id);
		chatSettlement.settle(fixture.id);
		completions.reconcile(fixture.id);
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(jdbc.sql("select completion_fact_id from appointment where id=:id").param("id", fixture.id).query(UUID.class).optional()).isEmpty();
	}

	@Test
	void missingEvidenceReturnsAcceptedPendingAndLatePairReconcilesWithoutForcedGraceWait() throws Exception {
		var fixture = fixture();
		var start = Instant.now().minusSeconds(240).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		setSchedule(fixture.id, start);
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		var key = "finish-video-" + UUID.randomUUID();
		mvc.perform(post("/api/v1/specialist/appointments/{id}/video-completion", fixture.id)
				.with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"1\"").header("Idempotency-Key", key)
				.contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"CONSULTATION_FINISHED\"}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.verificationState").value("PENDING_VERIFICATION"))
				.andExpect(jsonPath("$.creditState").value("HELD"));
		assertThat(credit(fixture)).isEqualTo("HELD");
		addPairedEvidence(fixture, start);
		completions.reconcile(fixture.id);
		assertThat(credit(fixture)).isEqualTo("CONSUMED");
	}

	@Test
	void confirmationRejectsWrongActorsExtraFieldsMissingStaleVersionsAndConflictingKeys() throws Exception {
		var fixture = joinedFixtureWithEvidence();
		var endpoint = "/api/v1/specialist/appointments/{id}/video-completion";
		var body = "{\"confirmation\":\"CONSULTATION_FINISHED\"}";
		var key = "finish-video-" + UUID.randomUUID();
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.user, "USER")).header("If-Match", "\"1\"")
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		mvc.perform(post(endpoint, fixture.id).with(actor(UUID.randomUUID(), "SPECIALIST")).header("If-Match", "\"1\"")
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isNotFound());
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"1\"")
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"CONSULTATION_FINISHED\",\"attendance\":60}"))
				.andExpect(status().isBadRequest());
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.specialist, "SPECIALIST"))
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionRequired());
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"0\"")
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isPreconditionFailed());
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"1\"")
				.header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
		mvc.perform(post(endpoint, fixture.id).with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"1\"")
				.header("Idempotency-Key", "different-finish-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
	}

	@Test
	void uncertainClosureAndTwentyFourHourTimeoutKeepCreditHeldAndNeverCreateSettlement() throws Exception {
		var fixture = joinedFixtureWithEvidence();
		doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_PROVIDER_UNAVAILABLE", "Video unavailable"))
				.when(provider).closeRoom(anyString(), any(), any());
		mvc.perform(post("/api/v1/specialist/appointments/{id}/video-completion", fixture.id)
				.with(actor(fixture.specialist, "SPECIALIST")).header("If-Match", "\"1\"").header("Idempotency-Key", "finish-video-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content("{\"confirmation\":\"CONSULTATION_FINISHED\"}"))
				.andExpect(status().isAccepted()).andExpect(jsonPath("$.creditState").value("HELD"));
		jdbc.sql("update appointment_video_confirmation set accepted_at=now()-interval '25 hours' where appointment_id=:id").param("id", fixture.id).update();
		completions.reconcile(fixture.id);
		mvc.perform(get("/api/v1/appointments/{id}/video-session", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.completion.verificationState").value("EVIDENCE_REVIEW"));
		assertThat(credit(fixture)).isEqualTo("HELD");
		assertThat(jdbc.sql("select session_settled_at from appointment where id=:id").param("id", fixture.id).query(Instant.class).optional()).isEmpty();
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", fixture.id).query(Long.class).single()).isZero();
	}

	@Test
	void concurrentConfirmationReplaysCreateOneIntentCreditTransitionAndEarning() throws Exception {
		var fixture = joinedFixtureWithEvidence();
		var key = "concurrent-finish-" + UUID.randomUUID();
		var start = new java.util.concurrent.CountDownLatch(1);
		try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> { start.await(); return completionService.confirm(fixture.id, fixture.specialist, "SPECIALIST", 1, key); });
			var second = executor.submit(() -> { start.await(); return completionService.confirm(fixture.id, fixture.specialist, "SPECIALIST", 1, key); });
			start.countDown();
			assertThat(first.get(15, java.util.concurrent.TimeUnit.SECONDS).response().confirmationId())
					.isEqualTo(second.get(15, java.util.concurrent.TimeUnit.SECONDS).response().confirmationId());
		}
		completions.reconcile(fixture.id);
		assertThat(jdbc.sql("select count(*) from appointment_video_confirmation where appointment_id=:id").param("id", fixture.id).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from service_credit_ledger where appointment_id=:id and event_type='CONSUMED'").param("id", fixture.id).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", fixture.id).query(Long.class).single()).isOne();
	}

	@Test
	void existingDisputeReleasePreservesVideoFactAndCreditReadAndBlocksRetention() throws Exception {
		var fixture = joinedFixtureWithEvidence();
		var completed = completionService.confirm(fixture.id, fixture.specialist, "SPECIALIST", 1, "finish-video-" + UUID.randomUUID()).response();
		var opened = mvc.perform(post("/api/v1/appointments/{id}/dispute", fixture.id).with(actor(fixture.user, "USER"))
				.header("Idempotency-Key", "open-video-dispute-" + UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"reasonCode\":\"TECHNICAL_FAILURE\"}")).andExpect(status().isOk()).andReturn();
		var disputeId = json.readTree(opened.getResponse().getContentAsByteArray()).get("id").asText();
		mvc.perform(post("/api/v1/admin/appointment-disputes/{id}/resolve", disputeId).with(actor(UUID.randomUUID(), "ADMIN"))
				.header("If-Match", "\"0\"").header("Idempotency-Key", "release-video-dispute-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content("{\"outcome\":\"RELEASE_USER_CREDIT\",\"reasonCode\":\"TECHNICAL_FAILURE_CONFIRMED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.creditAction").value("ADJUSTED_RELEASED"));
		mvc.perform(get("/api/v1/appointments/{id}/video-session", fixture.id).with(actor(fixture.user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.completion.creditState").value("AVAILABLE"))
				.andExpect(jsonPath("$.completion.completionFactId").value(completed.completionFactId().toString()));
		jdbc.sql("update appointment_video_room set closed_at=now()-interval '91 days' where appointment_id=:id").param("id", fixture.id).update();
		jdbc.sql("update appointment_video_event set received_at=now()-interval '91 days' where appointment_id=:id").param("id", fixture.id).update();
		evidence.purge(fixture.id);
		assertThat(events.findByAppointmentId(fixture.id)).hasSize(4);
		assertThat(jdbc.sql("select completion_fact_id from appointment where id=:id").param("id", fixture.id).query(UUID.class).single()).isEqualTo(completed.completionFactId());
	}

	Fixture joinedFixtureWithEvidence() throws Exception {
		var fixture = fixture();
		var start = Instant.now().minusSeconds(240).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
		setSchedule(fixture.id, start);
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		sessions.access(fixture.id, fixture.user, "USER");
		addPairedEvidence(fixture, start);
		return fixture;
	}

	void addPairedEvidence(Fixture fixture, Instant start) {
		var room = jdbc.sql("select room_name from appointment_video_room where appointment_id=:id").param("id", fixture.id).query(String.class).single();
		for (var role : java.util.List.of("user_identity", "specialist_identity")) {
			var identity = identity(fixture.id, role);
			evidence.record(event(room, identity, role + "-session", "participant_left", start.plusSeconds(180)));
			evidence.record(event(room, identity, role + "-session", "participant_joined", start.plusSeconds(10)));
		}
	}

	String identity(UUID id, String column) {
		return jdbc.sql("select " + column + " from appointment_video_room where appointment_id=:id").param("id", id).query(String.class).single();
	}

	VideoProvider.VerifiedVideoEvent event(String room, String identity, String sid, String type, Instant at) {
		return new VideoProvider.VerifiedVideoEvent(UUID.randomUUID().toString(), type, room, "room-incarnation", identity, sid, at);
	}

	Fixture fixture() throws Exception {
		return fixture(Instant.now().plusSeconds(86400), false, true);
	}

	Fixture fixture(Instant start, boolean demo, boolean accept) throws Exception {
		var user = UUID.randomUUID();
		var specialist = UUID.randomUUID();
		var slot = UUID.randomUUID();
		jdbc.sql("insert into current_service_entitlement(account_id,package_code,source,source_reference,established_by,effective_from,effective_until,policy_version) values(:id,'PLUS',:source,:ref,:admin,now()-interval '1 minute',now()+interval '30 days','service-entitlement-v1')")
				.param("id", user).param("source", demo ? "DEMO" : "PAID").param("admin", demo ? UUID.randomUUID() : null)
				.param("ref", "video-fixture-" + user).update();
		if (!demo) establishPaidEntitlement(jdbc, user);
		jdbc.sql("insert into specialist_profile(account_id,display_name,biography,years_experience,timezone,approval_status,submitted_at,reviewed_at,reviewed_by) values(:id,'Synthetic Specialist','Video fixture',5,'UTC','APPROVED',now(),now(),:admin)")
				.param("id", specialist).param("admin", UUID.randomUUID()).update();
		jdbc.sql("insert into specialist_profile_support_area(specialist_account_id,support_area) values(:id,'ANXIETY_SYMPTOMS')")
				.param("id", specialist).update();
		jdbc.sql("insert into specialist_profile_language(specialist_account_id,language_tag) values(:id,'vi')")
				.param("id", specialist).update();
		jdbc.sql("insert into availability_slot(id,specialist_account_id,start_at,end_at,timezone,modality,idempotency_key,created_at,updated_at) values(:id,:specialist,:start,:end,'UTC','IN_APP_VIDEO',:key,now(),now())")
				.param("id", slot).param("specialist", specialist).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3600))).param("key", "video-slot-" + slot).update();
		if (demo) {
			mvc.perform(get("/api/v1/bookable-slots").with(actor(user, "USER")))
					.andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id=='" + slot + "')]").isNotEmpty());
			mvc.perform(get("/api/v1/specialists/{id}", specialist).with(actor(user, "USER")))
					.andExpect(status().isOk()).andExpect(jsonPath("$.selectableSlots[?(@.id=='" + slot + "')]").isNotEmpty());
		}
		var result = mvc.perform(post("/api/v1/appointments").with(actor(user, "USER"))
				.header("Idempotency-Key", "video-appointment-" + UUID.randomUUID())
				.contentType(MediaType.APPLICATION_JSON).content("{\"slotId\":\"%s\",\"modality\":\"IN_APP_VIDEO\"}".formatted(slot)))
				.andExpect(status().isCreated()).andReturn();
		var body = json.readTree(result.getResponse().getContentAsByteArray());
		var id = UUID.fromString(body.get("id").asText());
		if (accept) decisions.accept(specialist, id, 0, "accept-video-" + UUID.randomUUID());
		return new Fixture(id, user, specialist, UUID.fromString(body.get("heldCreditId").asText()));
	}

	void setSchedule(UUID id, Instant start) {
		jdbc.sql("update appointment set requested_at=:requested,decision_deadline_at=:deadline,scheduled_start_at=:start,scheduled_end_at=:end where id=:id")
				.param("id", id).param("requested", Timestamp.from(start.minusSeconds(120)))
				.param("deadline", Timestamp.from(start.minusSeconds(60))).param("start", Timestamp.from(start))
				.param("end", Timestamp.from(start.plusSeconds(3600))).update();
	}

	String credit(Fixture fixture) {
		return jdbc.sql("select state from service_credit where id=:id").param("id", fixture.credit).query(String.class).single();
	}

	long rooms(UUID id) {
		return jdbc.sql("select count(*) from appointment_video_room where appointment_id=:id").param("id", id).query(Long.class).single();
	}

	static RequestPostProcessor actor(UUID id, String role) {
		return jwt().jwt(builder -> builder.subject(id.toString()).claim("roles", java.util.List.of(role)))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role));
	}

	record Fixture(UUID id, UUID user, UUID specialist, UUID credit) { }
}
