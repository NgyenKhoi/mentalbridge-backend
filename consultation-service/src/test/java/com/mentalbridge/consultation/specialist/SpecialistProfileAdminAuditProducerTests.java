package com.mentalbridge.consultation.specialist;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.kafka.core.KafkaTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

class SpecialistProfileAdminAuditProducerTests {

	private static final Instant NOW = Instant.parse("2026-10-05T12:00:00Z");

	private SpecialistProfileRepository profiles;
	private SpecialistProfileStatusHistoryRepository history;
	private SpecialistSuspensionEffects suspensionEffects;
	private ApprovedProfileVersionRepository approvedVersions;
	private ConsultationAdminAuditOutbox auditOutbox;
	private SpecialistProfileService service;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		profiles = mock(SpecialistProfileRepository.class);
		history = mock(SpecialistProfileStatusHistoryRepository.class);
		suspensionEffects = mock(SpecialistSuspensionEffects.class);
		approvedVersions = mock(ApprovedProfileVersionRepository.class);
		auditOutbox = mock(ConsultationAdminAuditOutbox.class);
		objectMapper = new ObjectMapper().findAndRegisterModules();

		service = new SpecialistProfileService(
				profiles,
				history,
				suspensionEffects,
				Clock.fixed(NOW, ZoneOffset.UTC),
				approvedVersions,
				auditOutbox
		);
	}

	@Test
	void specialistApprovalProducesExactlyOneMinimizedAuditFact() throws Exception {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var profile = submittedProfile(specialistId);

		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		service.approve(specialistId, adminId, profile.version(), correlationId);

		var captor = ArgumentCaptor.forClass(ConsultationAdminAuditEvent.class);
		verify(auditOutbox).record(anyString(), captor.capture());

		var event = captor.getValue();
		assertThat(event.eventId()).isNotNull();
		assertThat(event.eventType()).isEqualTo("consultation.specialist.approved");
		assertThat(event.occurredAt()).isEqualTo(NOW);
		assertThat(event.producer()).isEqualTo("consultation-service");
		assertThat(event.schemaVersion()).isEqualTo("1.0");
		assertThat(event.sourceService()).isEqualTo("CONSULTATION");
		assertThat(event.domain()).isEqualTo("SPECIALIST_REVIEW");
		assertThat(event.actorId()).isEqualTo(adminId);
		assertThat(event.actorType()).isEqualTo("ADMIN");
		assertThat(event.action()).isEqualTo("SPECIALIST_APPROVED");
		assertThat(event.result()).isEqualTo("SUCCEEDED");
		assertThat(event.reasonCode()).isNull();
		assertThat(event.correlationId()).isEqualTo(correlationId);
		assertThat(event.targetAccountId()).isEqualTo(specialistId);
		assertThat(event.targetIdentifier()).isEqualTo("account:" + specialistId);

		// Verify serialized JSON conforms to schema without forbidden / extra fields
		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));
		assertThat(json.has("rawJournal")).isFalse();
		assertThat(json.has("patientData")).isFalse();
		assertThat(json.has("answers")).isFalse();
		assertThat(json.has("notes")).isFalse();
		assertThat(json.get("action").asText()).isEqualTo("SPECIALIST_APPROVED");
		assertThat(json.get("sourceService").asText()).isEqualTo("CONSULTATION");
	}

	@Test
	void specialistRejectionProducesAuditFactWithSafeReasonCode() {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var profile = submittedProfile(specialistId);

		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		service.reject(specialistId, adminId, profile.version(), SpecialistDecisionReasonCode.PROFILE_INFORMATION_INCOMPLETE, correlationId);

		var captor = ArgumentCaptor.forClass(ConsultationAdminAuditEvent.class);
		verify(auditOutbox).record(anyString(), captor.capture());

		var event = captor.getValue();
		assertThat(event.eventType()).isEqualTo("consultation.specialist.rejected");
		assertThat(event.action()).isEqualTo("SPECIALIST_REJECTED");
		assertThat(event.reasonCode()).isEqualTo("PROFILE_INFORMATION_INCOMPLETE");
		assertThat(event.correlationId()).isEqualTo(correlationId);
	}

	@Test
	void specialistSuspensionProducesAuditFactWithSafeReasonCode() {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var profile = approvedProfile(specialistId);

		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));
		when(suspensionEffects.apply(specialistId, adminId, NOW)).thenReturn(SpecialistSuspensionEffects.Effects.none());

		service.suspend(specialistId, adminId, profile.version(), SpecialistDecisionReasonCode.POLICY_VIOLATION, correlationId);

		var captor = ArgumentCaptor.forClass(ConsultationAdminAuditEvent.class);
		verify(auditOutbox).record(anyString(), captor.capture());

		var event = captor.getValue();
		assertThat(event.eventType()).isEqualTo("consultation.specialist.suspended");
		assertThat(event.action()).isEqualTo("SPECIALIST_SUSPENDED");
		assertThat(event.reasonCode()).isEqualTo("POLICY_VIOLATION");
		assertThat(event.correlationId()).isEqualTo(correlationId);
	}

	@Test
	void specialistRestorationProducesAuditFact() {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var profile = approvedProfile(specialistId);
		profile.suspend(adminId, SpecialistDecisionReasonCode.QUALITY_REVIEW_REQUIRED, NOW.minusSeconds(600));

		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		service.restore(specialistId, adminId, profile.version(), correlationId);

		var captor = ArgumentCaptor.forClass(ConsultationAdminAuditEvent.class);
		verify(auditOutbox).record(anyString(), captor.capture());

		var event = captor.getValue();
		assertThat(event.eventType()).isEqualTo("consultation.specialist.restored");
		assertThat(event.action()).isEqualTo("SPECIALIST_RESTORED");
		assertThat(event.reasonCode()).isNull();
		assertThat(event.correlationId()).isEqualTo(correlationId);
	}

	@Test
	void rollbackDoesNotEmitAuditFact() {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var profile = submittedProfile(specialistId);

		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		// Calling approve with wrong expectedVersion throws Precondition Failed
		assertThatThrownBy(() -> service.approve(specialistId, adminId, profile.version() + 99L, UUID.randomUUID()))
				.isInstanceOf(ApiException.class);

		verify(auditOutbox, never()).record(anyString(), any());
	}

	@Test
	void idempotentCallWhenAlreadyInTargetStateDoesNotEmit() {
		var specialistId = UUID.randomUUID();
		var adminId = UUID.randomUUID();
		var profile = approvedProfile(specialistId);

		when(profiles.findByIdForUpdate(specialistId)).thenReturn(Optional.of(profile));

		// Already approved: returns view without emitting new event
		service.approve(specialistId, adminId, profile.version(), UUID.randomUUID());

		verify(auditOutbox, never()).record(anyString(), any());
	}

	private SpecialistProfileEntity submittedProfile(UUID specialistId) {
		var profile = new SpecialistProfileEntity(specialistId,
				new SpecialistProfileService.ProfileCommand("Dr. An", "Specialist bio",
						Set.of(SupportArea.DEPRESSIVE_SYMPTOMS), Set.of("vi"), 5, "Asia/Ho_Chi_Minh"),
				NOW.minusSeconds(86400));
		profile.submit(NOW.minusSeconds(3600));
		return profile;
	}

	private SpecialistProfileEntity approvedProfile(UUID specialistId) {
		var profile = submittedProfile(specialistId);
		profile.approve(UUID.randomUUID(), NOW.minusSeconds(1800));
		return profile;
	}
}
