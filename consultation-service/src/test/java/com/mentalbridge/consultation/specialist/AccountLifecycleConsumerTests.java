package com.mentalbridge.consultation.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

class AccountLifecycleConsumerTests {

	private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

	private SpecialistProfileRepository profiles;
	private SpecialistProfileStatusHistoryRepository history;
	private SpecialistSuspensionEffects suspensionEffects;
	private JdbcClient jdbc;
	private ObjectMapper objectMapper;
	private SpecialistAccountLifecycleService lifecycleService;
	private AccountLifecycleConsumer consumer;
	private SpecialistProfileService profileService;

	@BeforeEach
	void setUp() {
		profiles = mock(SpecialistProfileRepository.class);
		history = mock(SpecialistProfileStatusHistoryRepository.class);
		suspensionEffects = mock(SpecialistSuspensionEffects.class);
		jdbc = mock(JdbcClient.class);
		objectMapper = new ObjectMapper();
		lifecycleService = new SpecialistAccountLifecycleService(profiles, history, suspensionEffects, jdbc);
		consumer = new AccountLifecycleConsumer(lifecycleService, objectMapper);
		profileService = new SpecialistProfileService(profiles, history, suspensionEffects,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void specialistSuspensionTransitionsProfileAndEnforcesDownstreamIneligibility() {
		var specialistId = UUID.randomUUID();
		var profile = approvedProfile(specialistId);
		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		var eventPayload = """
				{
				  "messageId": "%s",
				  "messageType": "identity.account.state-changed",
				  "occurredAt": "%s",
				  "producer": "identity-service",
				  "schemaVersion": "1.0",
				  "correlationId": "%s",
				  "aggregateId": "%s",
				  "aggregateVersion": 2,
				  "payload": {
				    "accountId": "%s",
				    "status": "DISABLED",
				    "role": "SPECIALIST",
				    "reasonCode": "POLICY_VIOLATION"
				  }
				}
				""".formatted(UUID.randomUUID(), NOW, UUID.randomUUID(), specialistId, specialistId);

		consumer.onMessage(eventPayload);

		verify(suspensionEffects).apply(specialistId, SpecialistAccountLifecycleService.SYSTEM_ACTOR_ID, NOW);
		verify(profiles).saveAndFlush(profile);

		assertThat(profile.approvalStatus()).isEqualTo(SpecialistApprovalStatus.SUSPENDED);
		assertThat(profile.decisionReasonCode()).isEqualTo(SpecialistDecisionReasonCode.POLICY_VIOLATION);

		var historyCaptor = ArgumentCaptor.forClass(SpecialistProfileStatusHistoryEntity.class);
		verify(history).saveAndFlush(historyCaptor.capture());
		var historyEntity = historyCaptor.getValue();
		assertThat(historyEntity.specialistAccountId()).isEqualTo(specialistId);
		assertThat(historyEntity.approvalStatus()).isEqualTo(SpecialistApprovalStatus.SUSPENDED);
		assertThat(historyEntity.actorRole()).isEqualTo(SpecialistProfileStatusHistoryEntity.ActorRole.ADMIN);
		assertThat(historyEntity.reasonCode()).isEqualTo(SpecialistDecisionReasonCode.POLICY_VIOLATION);

		assertThatThrownBy(() -> profileService.requireApprovedForAvailability(specialistId))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> {
					var apiEx = (ApiException) ex;
					assertThat(apiEx.status()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(apiEx.code()).isEqualTo("SPECIALIST_NOT_APPROVED");
					assertThat(apiEx.getMessage()).contains("Only an approved specialist can publish availability");
				});

		assertThatThrownBy(() -> profileService.requireApprovedForBooking(specialistId))
				.isInstanceOf(ApiException.class)
				.satisfies(ex -> {
					var apiEx = (ApiException) ex;
					assertThat(apiEx.status()).isEqualTo(HttpStatus.CONFLICT);
					assertThat(apiEx.code()).isEqualTo("SPECIALIST_NOT_APPROVED");
					assertThat(apiEx.getMessage()).contains("Only an approved specialist can receive appointment requests");
				});
	}

	@Test
	void specialistRestorationTransitionsProfileAndRestoresDownstreamEligibility() {
		var specialistId = UUID.randomUUID();
		var profile = approvedProfile(specialistId);
		profile.suspend(UUID.randomUUID(), SpecialistDecisionReasonCode.POLICY_VIOLATION, NOW.minusSeconds(3600));
		assertThat(profile.approvalStatus()).isEqualTo(SpecialistApprovalStatus.SUSPENDED);

		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		var eventPayload = """
				{
				  "messageId": "%s",
				  "messageType": "identity.account.state-changed",
				  "occurredAt": "%s",
				  "producer": "identity-service",
				  "schemaVersion": "1.0",
				  "correlationId": "%s",
				  "aggregateId": "%s",
				  "aggregateVersion": 3,
				  "payload": {
				    "accountId": "%s",
				    "status": "ACTIVE",
				    "role": "SPECIALIST",
				    "reasonCode": "REVIEW_COMPLETED"
				  }
				}
				""".formatted(UUID.randomUUID(), NOW, UUID.randomUUID(), specialistId, specialistId);

		consumer.onMessage(eventPayload);

		verify(profiles).saveAndFlush(profile);
		assertThat(profile.approvalStatus()).isEqualTo(SpecialistApprovalStatus.APPROVED);
		assertThat(profile.decisionReasonCode()).isNull();

		var historyCaptor = ArgumentCaptor.forClass(SpecialistProfileStatusHistoryEntity.class);
		verify(history).saveAndFlush(historyCaptor.capture());
		assertThat(historyCaptor.getValue().approvalStatus()).isEqualTo(SpecialistApprovalStatus.APPROVED);

		profileService.requireApprovedForAvailability(specialistId);
		profileService.requireApprovedForBooking(specialistId);
	}

	@Test
	void idempotentWhenSpecialistAlreadyInTargetStatus() {
		var specialistId = UUID.randomUUID();
		var profile = approvedProfile(specialistId);
		profile.suspend(UUID.randomUUID(), SpecialistDecisionReasonCode.POLICY_VIOLATION, NOW.minusSeconds(3600));
		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		var disabledPayload = """
				{
				  "messageType": "identity.account.state-changed",
				  "occurredAt": "%s",
				  "payload": {
				    "accountId": "%s",
				    "status": "DISABLED",
				    "role": "SPECIALIST",
				    "reasonCode": "POLICY_VIOLATION"
				  }
				}
				""".formatted(NOW, specialistId);

		consumer.onMessage(disabledPayload);

		verify(suspensionEffects, never()).apply(any(), any(), any());
		verify(profiles, never()).saveAndFlush(any());
		verify(history, never()).saveAndFlush(any());

		profile.restore(UUID.randomUUID(), NOW);
		assertThat(profile.approvalStatus()).isEqualTo(SpecialistApprovalStatus.APPROVED);

		var activePayload = """
				{
				  "messageType": "identity.account.state-changed",
				  "occurredAt": "%s",
				  "payload": {
				    "accountId": "%s",
				    "status": "ACTIVE",
				    "role": "SPECIALIST",
				    "reasonCode": "REVIEW_COMPLETED"
				  }
				}
				""".formatted(NOW, specialistId);

		consumer.onMessage(activePayload);

		verify(profiles, never()).saveAndFlush(any());
		verify(history, never()).saveAndFlush(any());
	}

	@Test
	void mapsSafetyConcernAndAccountReviewRequiredProperly() {
		var specialistId = UUID.randomUUID();
		var profile = approvedProfile(specialistId);
		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		var safetyPayload = """
				{
				  "messageType": "identity.account.state-changed",
				  "occurredAt": "%s",
				  "payload": {
				    "accountId": "%s",
				    "status": "DISABLED",
				    "role": "SPECIALIST",
				    "reasonCode": "SAFETY_CONCERN"
				  }
				}
				""".formatted(NOW, specialistId);

		consumer.onMessage(safetyPayload);

		assertThat(profile.approvalStatus()).isEqualTo(SpecialistApprovalStatus.SUSPENDED);
		assertThat(profile.decisionReasonCode()).isEqualTo(SpecialistDecisionReasonCode.POLICY_VIOLATION);
	}

	@Test
	void ignoresNonStateChangedOrUnregisteredMessagesSafely() {
		var otherPayload = """
				{
				  "messageType": "identity.account.registered",
				  "occurredAt": "%s",
				  "payload": {
				    "accountId": "%s",
				    "actorType": "SPECIALIST",
				    "status": "PENDING_EMAIL_VERIFICATION"
				  }
				}
				""".formatted(NOW, UUID.randomUUID());

		consumer.onMessage(otherPayload);

		verify(profiles, never()).findByIdForUpdate(any());
		verify(suspensionEffects, never()).apply(any(), any(), any());
	}

	@Test
	void userSuspensionCancelsUpcomingAppointmentsAndReleasesHeldCredits() {
		var userId = UUID.randomUUID();
		var apptId = UUID.randomUUID();
		var creditId = UUID.randomUUID();

		var querySpec = mock(JdbcClient.StatementSpec.class);
		var queryMapped = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(any(String.class))).thenReturn(querySpec);
		when(querySpec.param(any(String.class), any())).thenReturn(querySpec);
		when(querySpec.query(any(RowMapper.class))).thenReturn(queryMapped);
		when(querySpec.update()).thenReturn(1);

		var userPayload = """
				{
				  "messageType": "identity.account.state-changed",
				  "occurredAt": "%s",
				  "payload": {
				    "accountId": "%s",
				    "status": "DISABLED",
				    "role": "USER",
				    "reasonCode": "POLICY_VIOLATION"
				  }
				}
				""".formatted(NOW, userId);

		consumer.onMessage(userPayload);
		verify(jdbc).sql(any(String.class));
	}

	private SpecialistProfileEntity approvedProfile(UUID specialistId) {
		var profile = new SpecialistProfileEntity(specialistId,
				new SpecialistProfileService.ProfileCommand("Dr. Nguyen", "Specialist in anxiety and depression",
						Set.of(SupportArea.DEPRESSIVE_SYMPTOMS, SupportArea.ANXIETY_SYMPTOMS), Set.of("vi", "en"), 5,
						"Asia/Ho_Chi_Minh"),
				NOW.minusSeconds(86400));
		profile.submit(NOW.minusSeconds(86400));
		profile.approve(UUID.randomUUID(), NOW.minusSeconds(80000));
		return profile;
	}
}
