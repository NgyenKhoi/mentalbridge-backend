package com.mentalbridge.community.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
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
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
	private CommunityModerationService service;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		jdbc = mock(JdbcClient.class);
		objectMapper = new ObjectMapper().findAndRegisterModules();
		service = new CommunityModerationService(jdbc, Clock.fixed(NOW, ZoneOffset.UTC), objectMapper);
	}

	@Test
	void postRemovalProducesCommunityPostRemovedFact() throws Exception {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorProfileId = UUID.randomUUID();
		var authorAccountSubject = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		var outboxStmt = setupMocksForAction(actor, caseId, postId, authorProfileId, authorAccountSubject,
				TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.REMOVE, "HARASSMENT"), correlationId);

		var payloadCaptor = ArgumentCaptor.forClass(String.class);
		var dedupKeyCaptor = ArgumentCaptor.forClass(String.class);
		verify(outboxStmt).param(eq("payload"), payloadCaptor.capture());
		verify(outboxStmt).param(eq("dedupKey"), dedupKeyCaptor.capture());

		assertThat(dedupKeyCaptor.getValue()).isEqualTo("audit:moderation:" + caseId + ":1:REMOVE");

		JsonNode json = objectMapper.readTree(payloadCaptor.getValue());
		assertThat(json.path("eventId").asText()).isNotBlank();
		assertThat(json.path("eventType").asText()).isEqualTo("community.post.removed");
		assertThat(json.path("occurredAt").asText()).isEqualTo(NOW.toString());
		assertThat(json.path("producer").asText()).isEqualTo("community-service");
		assertThat(json.path("schemaVersion").asText()).isEqualTo("1.0");
		assertThat(json.path("sourceService").asText()).isEqualTo("COMMUNITY");
		assertThat(json.path("domain").asText()).isEqualTo("COMMUNITY_MODERATION");
		assertThat(json.path("actorId").asText()).isEqualTo(actor.toString());
		assertThat(json.path("actorType").asText()).isEqualTo("ADMIN");
		assertThat(json.path("action").asText()).isEqualTo("COMMUNITY_POST_REMOVED");
		assertThat(json.path("result").asText()).isEqualTo("SUCCEEDED");
		assertThat(json.path("reasonCode").asText()).isEqualTo("HARASSMENT");
		assertThat(json.path("correlationId").asText()).isEqualTo(correlationId.toString());
		assertThat(json.path("targetAccountId").asText()).isEqualTo(authorAccountSubject.toString());
		assertThat(json.path("targetIdentifier").asText()).isEqualTo("account:" + authorAccountSubject);

		// Verify privacy minimization (no sensitive payload or internal prefixes)
		assertThat(json.has("postBody")).isFalse();
		assertThat(json.has("evidenceContent")).isFalse();
		assertThat(json.has("rawJournal")).isFalse();
		assertThat(json.has("displayName")).isFalse();
		assertThat(json.has("email")).isFalse();
		assertThat(json.path("targetIdentifier").asText()).doesNotStartWith("post:");
	}

	@Test
	void restrictAccessProducesCommunityUserSuspendedFact() throws Exception {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorProfileId = UUID.randomUUID();
		var authorAccountSubject = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		var outboxStmt = setupMocksForAction(actor, caseId, postId, authorProfileId, authorAccountSubject,
				TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.RESTRICT_COMMUNITY_ACCESS, "POLICY_VIOLATION"), correlationId);

		var payloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(outboxStmt).param(eq("payload"), payloadCaptor.capture());

		JsonNode json = objectMapper.readTree(payloadCaptor.getValue());
		assertThat(json.path("eventType").asText()).isEqualTo("community.user.suspended");
		assertThat(json.path("action").asText()).isEqualTo("COMMUNITY_USER_SUSPENDED");
		assertThat(json.path("targetAccountId").asText()).isEqualTo(authorAccountSubject.toString());
		assertThat(json.path("targetIdentifier").asText()).isEqualTo("account:" + authorAccountSubject);
		assertThat(json.path("correlationId").asText()).isEqualTo(correlationId.toString());
	}

	@Test
	void noActionProducesModerationCaseResolvedFact() throws Exception {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorProfileId = UUID.randomUUID();
		var authorAccountSubject = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		var outboxStmt = setupMocksForAction(actor, caseId, postId, authorProfileId, authorAccountSubject,
				TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.NO_ACTION, "REVIEW_COMPLETED"), correlationId);

		var payloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(outboxStmt).param(eq("payload"), payloadCaptor.capture());

		JsonNode json = objectMapper.readTree(payloadCaptor.getValue());
		assertThat(json.path("eventType").asText()).isEqualTo("community.moderation.case-resolved");
		assertThat(json.path("action").asText()).isEqualTo("MODERATION_CASE_RESOLVED");
		assertThat(json.path("correlationId").asText()).isEqualTo(correlationId.toString());
		assertThat(json.path("targetAccountId").asText()).isEqualTo(authorAccountSubject.toString());
		assertThat(json.path("targetIdentifier").asText()).isEqualTo("account:" + authorAccountSubject);
	}

	@Test
	void hideActionProducesModerationActionAppliedFact() throws Exception {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var postId = UUID.randomUUID();
		var authorProfileId = UUID.randomUUID();
		var authorAccountSubject = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		var outboxStmt = setupMocksForAction(actor, caseId, postId, authorProfileId, authorAccountSubject,
				TargetType.POST, idempotencyKey, "ACTIVE");

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.HIDE, "SPAM"), correlationId);

		var payloadCaptor = ArgumentCaptor.forClass(String.class);
		verify(outboxStmt).param(eq("payload"), payloadCaptor.capture());

		JsonNode json = objectMapper.readTree(payloadCaptor.getValue());
		assertThat(json.path("eventType").asText()).isEqualTo("community.moderation.action-applied");
		assertThat(json.path("action").asText()).isEqualTo("MODERATION_ACTION_APPLIED");
		assertThat(json.path("reasonCode").asText()).isEqualTo("SPAM");
		assertThat(json.path("correlationId").asText()).isEqualTo(correlationId.toString());
		assertThat(json.path("targetAccountId").asText()).isEqualTo(authorAccountSubject.toString());
		assertThat(json.path("targetIdentifier").asText()).isEqualTo("account:" + authorAccountSubject);
	}

	@Test
	void idempotentReplayDoesNotProduceDuplicateAuditEvent() {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();
		var idempotencyKey = UUID.randomUUID().toString();

		var replayStmt = mock(JdbcClient.StatementSpec.class);
		var replayQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select request_fingerprint, case_id from community_moderation_action")))
				.thenReturn(replayStmt);
		when(replayStmt.param(any(String.class), any())).thenReturn(replayStmt);
		when(replayStmt.query(any(RowMapper.class))).thenReturn(replayQuery);

		String fingerprint = service.fingerprint(caseId.toString(), "REMOVE", "HARASSMENT");
		when(replayQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.Replay(fingerprint, caseId)));

		var detailStmt = mock(JdbcClient.StatementSpec.class);
		var detailQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select * from community_moderation_case where id = :id")))
				.thenReturn(detailStmt);
		when(detailStmt.param(any(String.class), any())).thenReturn(detailStmt);
		when(detailStmt.query(any(RowMapper.class))).thenReturn(detailQuery);
		when(detailQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.CaseRow(
				caseId, TargetType.POST, UUID.randomUUID(), CaseState.RESOLVED, Priority.NORMAL, "evidence", "ACTIVE", 1L, NOW, NOW, 1L)));

		var reportStmt = mock(JdbcClient.StatementSpec.class);
		var reportQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select distinct reason from community_report")))
				.thenReturn(reportStmt);
		when(reportStmt.param(any(String.class), any())).thenReturn(reportStmt);
		when(reportStmt.query(String.class)).thenReturn(reportQuery);
		when(reportQuery.list()).thenReturn(List.of());

		var contextStmt = mock(JdbcClient.StatementSpec.class);
		var contextQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select details from community_report")))
				.thenReturn(contextStmt);
		when(contextStmt.param(any(String.class), any())).thenReturn(contextStmt);
		when(contextStmt.query(String.class)).thenReturn(contextQuery);
		when(contextQuery.list()).thenReturn(List.of());

		var actionStmt = mock(JdbcClient.StatementSpec.class);
		var actionQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select * from community_moderation_action where case_id = :id")))
				.thenReturn(actionStmt);
		when(actionStmt.param(any(String.class), any())).thenReturn(actionStmt);
		when(actionStmt.query(any(RowMapper.class))).thenReturn(actionQuery);
		when(actionQuery.list()).thenReturn(List.of());

		service.act(actor, caseId, idempotencyKey, new CreateModerationActionRequest(Action.REMOVE, "HARASSMENT"), UUID.randomUUID());

		verify(jdbc, never()).sql(contains("insert into community_interaction_outbox"));
	}

	@Test
	void invalidReasonCodeThrowsAndDoesNotEmit() {
		var actor = UUID.randomUUID();
		var caseId = UUID.randomUUID();

		assertThatThrownBy(() -> service.act(actor, caseId, UUID.randomUUID().toString(),
				new CreateModerationActionRequest(Action.REMOVE, "invalid reason with spaces!"), UUID.randomUUID()))
				.isInstanceOf(CommunityApiException.class);

		verify(jdbc, never()).sql(contains("insert into community_interaction_outbox"));
	}

	@SuppressWarnings("unchecked")
	private JdbcClient.StatementSpec setupMocksForAction(
			UUID actor, UUID caseId, UUID postId, UUID authorProfileId, UUID authorAccountSubject,
			TargetType targetType, String idempotencyKey, String state) {

		// 1. Replay lookup -> empty
		var replayStmt = mock(JdbcClient.StatementSpec.class);
		var replayQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select request_fingerprint, case_id from community_moderation_action")))
				.thenReturn(replayStmt);
		when(replayStmt.param(any(String.class), any())).thenReturn(replayStmt);
		when(replayStmt.query(any(RowMapper.class))).thenReturn(replayQuery);
		when(replayQuery.optional()).thenReturn(Optional.empty());

		// 2. Case target lookup -> returns Target(type, targetId, authorProfileId)
		var targetStmt = mock(JdbcClient.StatementSpec.class);
		var targetQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("from community_moderation_case where id = :id for update")))
				.thenReturn(targetStmt);
		when(targetStmt.param(any(String.class), any())).thenReturn(targetStmt);
		when(targetStmt.query(any(RowMapper.class))).thenReturn(targetQuery);
		when(targetQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.Target(targetType, postId, authorProfileId)));

		// 3. Snapshot / currentTarget lookup
		var postStmt = mock(JdbcClient.StatementSpec.class);
		var postQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("from community_post where id = :id"))).thenReturn(postStmt);
		when(postStmt.param(any(String.class), any())).thenReturn(postStmt);
		when(postStmt.query(any(RowMapper.class))).thenReturn(postQuery);
		when(postQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.Snapshot("Sample content", state, 1L, authorProfileId, null)));

		var commentStmt = mock(JdbcClient.StatementSpec.class);
		var commentQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("from community_comment where id = :id"))).thenReturn(commentStmt);
		when(commentStmt.param(any(String.class), any())).thenReturn(commentStmt);
		when(commentStmt.query(any(RowMapper.class))).thenReturn(commentQuery);
		when(commentQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.Snapshot("Sample content", state, 1L, authorProfileId, null)));

		// 4. Mutation updates/inserts
		var updateStmt = mock(JdbcClient.StatementSpec.class);
		when(updateStmt.param(any(String.class), any())).thenReturn(updateStmt);
		when(updateStmt.update()).thenReturn(1);
		when(jdbc.sql(contains("update community_post"))).thenReturn(updateStmt);
		when(jdbc.sql(contains("update community_comment"))).thenReturn(updateStmt);
		when(jdbc.sql(contains("insert into community_moderation_action"))).thenReturn(updateStmt);
		when(jdbc.sql(contains("update community_moderation_case set state = 'RESOLVED'"))).thenReturn(updateStmt);

		// 5. Account subject lookup from community_profile
		var profileStmt = mock(JdbcClient.StatementSpec.class);
		var profileQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select account_subject from community_profile where id = :profileId")))
				.thenReturn(profileStmt);
		when(profileStmt.param(any(String.class), any())).thenReturn(profileStmt);
		when(profileStmt.query(UUID.class)).thenReturn(profileQuery);
		when(profileQuery.optional()).thenReturn(Optional.ofNullable(authorAccountSubject));

		// 6. Outbox insert
		var outboxStmt = mock(JdbcClient.StatementSpec.class);
		when(jdbc.sql(contains("insert into community_interaction_outbox"))).thenReturn(outboxStmt);
		when(outboxStmt.param(any(String.class), any())).thenReturn(outboxStmt);
		when(outboxStmt.update()).thenReturn(1);

		// 7. Final get(caseId) queries
		var getCaseStmt = mock(JdbcClient.StatementSpec.class);
		var getCaseQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select * from community_moderation_case where id = :id"))).thenReturn(getCaseStmt);
		when(getCaseStmt.param(any(String.class), any())).thenReturn(getCaseStmt);
		when(getCaseStmt.query(any(RowMapper.class))).thenReturn(getCaseQuery);
		when(getCaseQuery.optional()).thenReturn(Optional.of(new CommunityModerationService.CaseRow(
				caseId, targetType, postId, CaseState.RESOLVED, Priority.NORMAL, "evidence", state, 1L, NOW, NOW, 1L)));

		var reportStmt = mock(JdbcClient.StatementSpec.class);
		var reportQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select distinct reason from community_report"))).thenReturn(reportStmt);
		when(reportStmt.param(any(String.class), any())).thenReturn(reportStmt);
		when(reportStmt.query(String.class)).thenReturn(reportQuery);
		when(reportQuery.list()).thenReturn(List.of());

		var detailsStmt = mock(JdbcClient.StatementSpec.class);
		var detailsQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select details from community_report"))).thenReturn(detailsStmt);
		when(detailsStmt.param(any(String.class), any())).thenReturn(detailsStmt);
		when(detailsStmt.query(String.class)).thenReturn(detailsQuery);
		when(detailsQuery.list()).thenReturn(List.of());

		var actionStmt = mock(JdbcClient.StatementSpec.class);
		var actionQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql(contains("select * from community_moderation_action where case_id = :id"))).thenReturn(actionStmt);
		when(actionStmt.param(any(String.class), any())).thenReturn(actionStmt);
		when(actionStmt.query(any(RowMapper.class))).thenReturn(actionQuery);
		when(actionQuery.list()).thenReturn(List.of());

		return outboxStmt;
	}
}
