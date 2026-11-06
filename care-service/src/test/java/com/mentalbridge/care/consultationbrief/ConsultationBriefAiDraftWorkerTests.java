package com.mentalbridge.care.consultationbrief;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.consent.ConsentService;

class ConsultationBriefAiDraftWorkerTests {
	private final ConsultationBriefAiDraftClient client = mock(ConsultationBriefAiDraftClient.class);
	private final JdbcClient jdbc = mock(JdbcClient.class, Answers.RETURNS_DEEP_STUBS);
	private final ConsentService consents = mock(ConsentService.class);
	private final ConsultationBriefAiDraftWorker worker = new ConsultationBriefAiDraftWorker(client, jdbc,
			mock(TransactionTemplate.class), new ObjectMapper(), Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC), consents);

	@Test
	void queuedJobWithLostContextCannotForwardPersonalPayload() {
		var owner = UUID.randomUUID();
		when(consents.authorizeAiContextProcessing(owner)).thenReturn(authority(false));
		worker.dispatch(UUID.randomUUID(), owner, source(), "synthetic-bearer", UUID.randomUUID());
		verify(client, never()).draft(any(), any(), any());
	}

	@Test
	void contextLostAfterAttemptAssemblyCannotForwardPersonalPayload() {
		var owner = UUID.randomUUID();
		when(consents.authorizeAiContextProcessing(owner)).thenReturn(authority(true), authority(false));
		worker.dispatch(UUID.randomUUID(), owner, source(), "synthetic-bearer", UUID.randomUUID());
		verify(client, never()).draft(any(), any(), any());
	}

	@Test
	void unavailableContextAuthorityCannotForwardPersonalPayload() {
		var owner = UUID.randomUUID();
		when(consents.authorizeAiContextProcessing(owner))
				.thenThrow(new CannotCreateTransactionException("Synthetic consent authority unavailable"));
		worker.dispatch(UUID.randomUUID(), owner, source(), "synthetic-bearer", UUID.randomUUID());
		verify(client, never()).draft(any(), any(), any());
	}

	@Test
	void unavailableContextAuthorityAfterFirstAttemptPreventsPayloadResend() {
		var owner = UUID.randomUUID();
		var source = source();
		var correlationId = UUID.randomUUID();
		when(consents.authorizeAiContextProcessing(owner)).thenReturn(authority(true), authority(true))
				.thenThrow(new CannotCreateTransactionException("Synthetic consent authority unavailable"));
		when(client.draft(any(), any(), any())).thenThrow(new IllegalStateException("Synthetic provider unavailable"));
		worker.dispatch(UUID.randomUUID(), owner, source, "synthetic-bearer", correlationId);
		verify(client).draft(new ConsultationBriefAiDraftClient.DraftProviderRequest(source.appointmentId(), source.briefId(),
				source.briefVersion(), source.supportEvaluationId(), source.currentSituation(), source.userGoals(),
				source.screeningContext(), ConsultationBriefAiDraftService.SOURCE_SET_VERSION), "synthetic-bearer", correlationId);
	}

	private ConsentService.AiAuthorizationView authority(boolean granted) {
		return new ConsentService.AiAuthorizationView(granted, granted ? "GRANTED" : "REVOKED", "AI_CONTEXT_PROCESSING",
				"ai-context-processing-capstone-v1", Instant.parse("2026-11-06T00:00:00Z"));
	}

	private ConsultationBriefService.AiDraftSource source() {
		return new ConsultationBriefService.AiDraftSource(UUID.randomUUID(), UUID.randomUUID(), 0,
				"Synthetic saved personal situation", UUID.randomUUID(), List.of(), List.of("Synthetic goal"));
	}
}
