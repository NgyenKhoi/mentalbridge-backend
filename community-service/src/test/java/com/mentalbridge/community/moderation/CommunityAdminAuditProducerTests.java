package com.mentalbridge.community.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.community.moderation.CommunityModerationModels.Action;
import com.mentalbridge.community.moderation.CommunityModerationModels.CaseState;
import com.mentalbridge.community.moderation.CommunityModerationModels.CreateModerationActionRequest;
import com.mentalbridge.community.moderation.CommunityModerationModels.Priority;
import com.mentalbridge.community.moderation.CommunityModerationModels.TargetType;
import com.mentalbridge.community.shared.CommunityApiException;

class CommunityAdminAuditProducerTests {

	private static final Instant NOW = Instant.parse("2026-10-06T14:00:00Z");

	private JdbcClient jdbc;
	private ApplicationEventPublisher eventPublisher;
	private CommunityModerationService service;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		jdbc = mock(JdbcClient.class);
		eventPublisher = mock(ApplicationEventPublisher.class);
		objectMapper = new ObjectMapper().findAndRegisterModules();
		service = new CommunityModerationService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), objectMapper);
	}

	@Test
	void postRemovalProducesCommunityPostRemovedFact() throws Exception {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		setupMocksForAction(actor, caseId, postId, authorId, TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.REMOVE, "HARASSMENT"), correlationId);

		var captor = ArgumentCaptor.forClass(CommunityAdminAuditEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());

		var event = captor.getValue();
		assertThat(event.eventId()).isNotNull();
		assertThat(event.eventType()).isEqualTo("community.post.removed");
		assertThat(event.occurredAt()).isEqualTo(NOW);
		assertThat(event.producer()).isEqualTo("community-service");
		assertThat(event.schemaVersion()).isEqualTo("1.0");
		assertThat(event.sourceService()).isEqualTo("COMMUNITY");
		assertThat(event.domain()).isEqualTo("COMMUNITY_MODERATION");
		assertThat(event.actorId()).isEqualTo(actor);
		assertThat(event.actorType()).isEqualTo("ADMIN");
		assertThat(event.action()).isEqualTo("COMMUNITY_POST_REMOVED");
		assertThat(event.result()).isEqualTo("SUCCEEDED");
		assertThat(event.reasonCode()).isEqualTo("HARASSMENT");
		assertThat(event.correlationId()).isEqualTo(correlationId);
		assertThat(event.targetAccountId()).isEqualTo(authorId);
		assertThat(event.targetIdentifier()).isEqualTo("post:" + postId);

		// Verify serialized JSON conforms to schema without forbidden / extra fields
		JsonNode json = objectMapper.readTree(objectMapper.writeValueAsString(event));
		assertThat(json.has("postBody")).isFalse();
		assertThat(json.has("evidenceContent")).isFalse();
		assertThat(json.has("rawJournal")).isFalse();
		assertThat(json.get("action").asText()).isEqualTo("COMMUNITY_POST_REMOVED");
		assertThat(json.get("sourceService").asText()).isEqualTo("COMMUNITY");
	}

	@Test
	void restrictAccessProducesCommunityUserSuspendedFact() {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		setupMocksForAction(actor, caseId, postId, authorId, TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.RESTRICT_COMMUNITY_ACCESS, "POLICY_VIOLATION"), correlationId);

		var captor = ArgumentCaptor.forClass(CommunityAdminAuditEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());

		var event = captor.getValue();
		assertThat(event.eventType()).isEqualTo("community.user.suspended");
		assertThat(event.action()).isEqualTo("COMMUNITY_USER_SUSPENDED");
		assertThat(event.targetAccountId()).isEqualTo(authorId);
		assertThat(event.targetIdentifier()).isEqualTo("account:" + authorId);
		assertThat(event.correlationId()).isEqualTo(correlationId);
	}

	@Test
	void noActionProducesModerationCaseResolvedFact() {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		setupMocksForAction(actor, caseId, postId, authorId, TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.NO_ACTION, "REVIEW_COMPLETED"), correlationId);

		var captor = ArgumentCaptor.forClass(CommunityAdminAuditEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());

		var event = captor.getValue();
		assertThat(event.eventType()).isEqualTo("community.moderation.case-resolved");
		assertThat(event.action()).isEqualTo("MODERATION_CASE_RESOLVED");
		assertThat(event.correlationId()).isEqualTo(correlationId);
	}

	@Test
	void hideActionProducesModerationActionAppliedFact() {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		setupMocksForAction(actor, caseId, postId, authorId, TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.HIDE, "SPAM"), correlationId);

		var captor = ArgumentCaptor.forClass(CommunityAdminAuditEvent.class);
		verify(eventPublisher).publishEvent(captor.capture());

		var event = captor.getValue();
		assertThat(event.eventType()).isEqualTo("community.moderation.action-applied");
		assertThat(event.action()).isEqualTo("MODERATION_ACTION_APPLIED");
		assertThat(event.reasonCode()).isEqualTo("SPAM");
		assertThat(event.correlationId()).isEqualTo(correlationId);
	}

	@Test
	void idempotentReplayDoesNotProduceDuplicateAuditEvent() {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		var replayStmt = mock(JdbcClient.StatementSpec.class);
		var replayQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(org.mockito.ArgumentMatchers.contains("select request_fingerprint, case_id from community_moderation_action")))
				.thenReturn(replayStmt);
		when(replayStmt.param(any(String.class), any())).thenReturn(replayStmt);
		when(replayStmt.query(any(RowMapper.class))).thenReturn(replayQuery);

		String fingerprint = service.fingerprint(caseId.toString(), "REMOVE", "HARASSMENT");
		when(replayQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.Replay(fingerprint, caseId)));

		var detailStmt = mock(JdbcClient.StatementSpec.class);
		var detailQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(org.mockito.ArgumentMatchers.contains("select * from community_moderation_case where id = :id")))
				.thenReturn(detailStmt);
		when(detailStmt.param(any(String.class), any())).thenReturn(detailStmt);
		when(detailStmt.query(any(RowMapper.class))).thenReturn(detailQuery);
		when(detailQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.CaseRow(
				caseId, TargetType.POST, UUID.randomUUID(), CaseState.RESOLVED, Priority.NORMAL, "evidence", "ACTIVE", 1L, NOW, NOW, 1L)));

		var reportStmt = mock(JdbcClient.StatementSpec.class);
		var reportQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(org.mockito.ArgumentMatchers.contains("select distinct reason from community_report")))
				.thenReturn(reportStmt);
		when(reportStmt.param(any(String.class), any())).thenReturn(reportStmt);
		when(reportStmt.query(any(Class.class))).thenReturn(reportQuery);
		when(reportQuery.list()).thenReturn(java.util.List.of());

		var contextStmt = mock(JdbcClient.StatementSpec.class);
		var contextQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(org.mockito.ArgumentMatchers.contains("select details from community_report")))
				.thenReturn(contextStmt);
		when(contextStmt.param(any(String.class), any())).thenReturn(contextStmt);
		when(contextStmt.query(any(Class.class))).thenReturn(contextQuery);
		when(contextQuery.list()).thenReturn(java.util.List.of());

		var actionStmt = mock(JdbcClient.StatementSpec.class);
		var actionQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(org.mockito.ArgumentMatchers.contains("select * from community_moderation_action where case_id = :id")))
				.thenReturn(actionStmt);
		when(actionStmt.param(any(String.class), any())).thenReturn(actionStmt);
		when(actionStmt.query(any(RowMapper.class))).thenReturn(actionQuery);
		when(actionQuery.list()).thenReturn(java.util.List.of());

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.REMOVE, "HARASSMENT"), UUID.randomUUID());

		verify(eventPublisher, never()).publishEvent(any());
	}

	@Test
	void invalidReasonCodeThrowsAndDoesNotEmit() {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();

		assertThatThrownBy(() -> service.act(actor, caseId, UUID.randomUUID().toString(),
				new CreateModerationActionRequest(Action.REMOVE, "invalid reason with spaces!"), UUID.randomUUID()))
				.isInstanceOf(CommunityApiException.class);

		verify(eventPublisher, never()).publishEvent(any());
	}


	@SuppressWarnings("unchecked")
	private void setupMocksForAction(UUID actor, UUID caseId, UUID postId, UUID authorId, TargetType targetType, String idempotencyKey, String state) {
		var stmt = mock(JdbcClient.StatementSpec.class);
		when(jdbc.sql(any(String.class))).thenReturn(stmt);
		when(stmt.param(any(String.class), any())).thenReturn(stmt);
		when(stmt.update()).thenReturn(1);

		var mappedQuerySpec = mock(JdbcClient.MappedQuerySpec.class);
		when(stmt.query(any(RowMapper.class))).thenReturn(mappedQuerySpec);
		when(stmt.query(any(Class.class))).thenReturn(mappedQuerySpec);

		// replay check returns empty
		when(mappedQuerySpec.optional())
				.thenReturn(Optional.empty()) // replay
				.thenReturn(Optional.of(new CommunityModerationService.Target(targetType, postId, authorId))) // target
				.thenReturn(Optional.of(new CommunityModerationService.Snapshot("Sample content", state, 1L, authorId, null))) // snapshot
				.thenReturn(Optional.of(new CommunityModerationService.CaseRow(caseId, targetType, postId, CaseState.OPEN, Priority.NORMAL, "evidence", "ACTIVE", 1L, NOW, NOW, 1L))); // get case row

		var listQuerySpec = mock(java.util.List.class);
		when(mappedQuerySpec.list()).thenReturn(java.util.List.of());
	}
}
