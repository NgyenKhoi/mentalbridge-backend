package com.mentalbridge.community.moderation;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import com.mentalbridge.community.moderation.CommunityModerationModels.Action;
import com.mentalbridge.community.moderation.CommunityModerationModels.ActionRecord;
import com.mentalbridge.community.moderation.CommunityModerationModels.CaseState;
import com.mentalbridge.community.moderation.CommunityModerationModels.CreateModerationActionRequest;
import com.mentalbridge.community.moderation.CommunityModerationModels.CreateReportRequest;
import com.mentalbridge.community.moderation.CommunityModerationModels.Evidence;
import com.mentalbridge.community.moderation.CommunityModerationModels.ModerationCase;
import com.mentalbridge.community.moderation.CommunityModerationModels.Priority;
import com.mentalbridge.community.moderation.CommunityModerationModels.ReportReason;
import com.mentalbridge.community.moderation.CommunityModerationModels.TargetType;
import com.mentalbridge.community.shared.CommunityApiException;

@Service
class CommunityModerationService {

	private static final String DEFAULT_DISPLAY_NAME = "Thành viên MentalBridge";
	private static final int MAX_DETAILS_CODE_POINTS = 1000;
	private static final String[] REPORT_REASONS = { "HARASSMENT", "PRIVACY_OR_DOXXING",
			"MEDICAL_MISINFORMATION", "SELF_HARM_OR_CRISIS_CONCERN", "SPAM",
			"SEXUAL_OR_VIOLENT_CONTENT", "OTHER" };

	private final JdbcClient jdbc;
	private final Clock clock;
	private final ObjectMapper objectMapper;

	CommunityModerationService(JdbcClient jdbc, Clock clock) {
		this(jdbc, clock, new ObjectMapper().findAndRegisterModules());
	}

	@Autowired
	public CommunityModerationService(JdbcClient jdbc, Clock clock, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.objectMapper = objectMapper;
	}

	@Transactional
	void report(UUID subject, String idempotencyKey, CreateReportRequest request) {
		validateKey(idempotencyKey);
		if (request == null || request.targetType() == null || request.targetId() == null || request.reason() == null) {
			throw invalid("Report target and reason are required");
		}
		var details = normalizeDetails(request.details());
		var fingerprint = fingerprint(request.targetType().name(), request.targetId().toString(),
				request.reason().name(), details == null ? "" : details);
		var reporterId = ensureProfile(subject);
		var replay = jdbc.sql("""
				select request_fingerprint from community_report
				where reporter_profile_id = :reporter and idempotency_key = :key
				""").param("reporter", reporterId).param("key", idempotencyKey).query(String.class).optional();
		if (replay.isPresent()) {
			if (!replay.orElseThrow().equals(fingerprint)) throw CommunityApiException.idempotencyKeyReused();
			return;
		}
		var snapshot = visibleSnapshot(request.targetType(), request.targetId(), reporterId);
		var now = clock.instant();
		var caseId = jdbc.sql("""
				select id from community_moderation_case where target_type = :type and target_id = :target
				""").param("type", request.targetType().name()).param("target", request.targetId())
				.query(UUID.class).optional().orElseGet(() -> createCase(request, snapshot, now));
		final int inserted;
		try {
			inserted = jdbc.sql("""
					insert into community_report
					(id, reporter_profile_id, target_type, target_id, reason, details, idempotency_key, request_fingerprint, created_at)
					values (:id, :reporter, :type, :target, :reason, :details, :key, :fingerprint, :now)
					on conflict (reporter_profile_id, target_type, target_id) do nothing
					""").param("id", UUID.randomUUID()).param("reporter", reporterId)
					.param("type", request.targetType().name()).param("target", request.targetId())
					.param("reason", request.reason().name()).param("details", details)
					.param("key", idempotencyKey).param("fingerprint", fingerprint).param("now", timestamp(now)).update();
		}
		catch (DataIntegrityViolationException exception) {
			throw CommunityApiException.idempotencyKeyReused();
		}
		if (inserted == 1) {
			jdbc.sql("update community_moderation_case set state = 'OPEN', updated_at = :now, version = version + 1 where id = :id and state = 'RESOLVED'")
					.param("now", timestamp(now)).param("id", caseId).update();
			if (request.reason() == ReportReason.SELF_HARM_OR_CRISIS_CONCERN) {
				jdbc.sql("""
						update community_moderation_case set priority = 'HIGH', updated_at = :now, version = version + 1
						where id = :id and priority <> 'HIGH'
						""").param("now", timestamp(now)).param("id", caseId).update();
			}
		}
	}

	@Transactional
	void hide(UUID subject, TargetType type, UUID targetId) {
		var profileId = ensureProfile(subject);
		visibleSnapshot(type, targetId, profileId);
		jdbc.sql("""
				insert into community_content_hide (hider_profile_id, target_type, target_id, created_at)
				values (:profile, :type, :target, :now) on conflict do nothing
				""").param("profile", profileId).param("type", type.name()).param("target", targetId)
				.param("now", timestamp(clock.instant())).update();
	}

	@Transactional
	void unhide(UUID subject, TargetType type, UUID targetId) {
		var profileId = ensureProfile(subject);
		jdbc.sql("delete from community_content_hide where hider_profile_id = :profile and target_type = :type and target_id = :target")
				.param("profile", profileId).param("type", type.name()).param("target", targetId).update();
	}

	@Transactional
	void block(UUID subject, UUID blockedProfileId) {
		var blockerId = ensureProfile(subject);
		if (blockerId.equals(blockedProfileId) || !activeProfileExists(blockedProfileId)) throw CommunityApiException.profileNotFound();
		jdbc.sql("""
				insert into community_block (blocker_profile_id, blocked_profile_id, created_at)
				values (:blocker, :blocked, :now) on conflict do nothing
				""").param("blocker", blockerId).param("blocked", blockedProfileId).param("now", timestamp(clock.instant())).update();
	}

	@Transactional
	void unblock(UUID subject, UUID blockedProfileId) {
		var blockerId = ensureProfile(subject);
		if (!activeProfileExists(blockedProfileId)) throw CommunityApiException.profileNotFound();
		jdbc.sql("delete from community_block where blocker_profile_id = :blocker and blocked_profile_id = :blocked")
				.param("blocker", blockerId).param("blocked", blockedProfileId).update();
	}

	@Transactional(readOnly = true)
	List<ModerationCase> list(CaseState state, TargetType targetType, Priority priority) {
		var sql = new StringBuilder("select id from community_moderation_case where 1 = 1");
		if (state != null) sql.append(" and state = :state");
		if (targetType != null) sql.append(" and target_type = :type");
		if (priority != null) sql.append(" and priority = :priority");
		sql.append(" order by case when priority = 'HIGH' then 0 else 1 end, created_at, id limit 100");
		var statement = jdbc.sql(sql.toString());
		if (state != null) statement = statement.param("state", state.name());
		if (targetType != null) statement = statement.param("type", targetType.name());
		if (priority != null) statement = statement.param("priority", priority.name());
		return statement.query(UUID.class).list().stream().map(this::get).toList();
	}

	@Transactional(readOnly = true)
	ModerationCase get(UUID caseId) {
		var row = jdbc.sql("select * from community_moderation_case where id = :id")
				.param("id", caseId).query((rs, rowNum) -> new CaseRow(rs.getObject("id", UUID.class),
						TargetType.valueOf(rs.getString("target_type")), rs.getObject("target_id", UUID.class),
						CaseState.valueOf(rs.getString("state")), Priority.valueOf(rs.getString("priority")),
						rs.getString("evidence_content"), rs.getString("evidence_state"), rs.getLong("evidence_version"),
						rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
						rs.getLong("version"))).optional().orElseThrow(CommunityModerationService::caseNotFound);
		var reasons = jdbc.sql("select distinct reason from community_report where target_type = :type and target_id = :target order by reason")
				.param("type", row.targetType().name()).param("target", row.targetId()).query(String.class).list()
				.stream().map(ReportReason::valueOf).toList();
		var contexts = jdbc.sql("""
				select details from community_report
				where target_type = :type and target_id = :target and details is not null
				order by created_at, id limit 20
				""").param("type", row.targetType().name()).param("target", row.targetId())
				.query(String.class).list();
		var actions = jdbc.sql("select * from community_moderation_action where case_id = :id order by created_at, id")
				.param("id", caseId).query((rs, rowNum) -> new ActionRecord(rs.getObject("id", UUID.class),
						Action.valueOf(rs.getString("action")), rs.getString("reason_code"),
						rs.getObject("actor_subject", UUID.class), rs.getString("prior_state"),
						rs.getString("resulting_state"), rs.getLong("target_version"),
						rs.getTimestamp("created_at").toInstant())).list();
		return new ModerationCase(row.id(), row.targetType(), row.targetId(), row.state(), row.priority(), reasons, contexts,
				new Evidence(row.evidenceContent(), row.evidenceState(), row.evidenceVersion()), actions,
				row.createdAt(), row.updatedAt(), row.version());
	}

	ModerationCase act(UUID actorSubject, UUID caseId, String idempotencyKey, CreateModerationActionRequest request) {
		return act(actorSubject, caseId, idempotencyKey, request, null);
	}

	@Transactional
	ModerationCase act(UUID actorSubject, UUID caseId, String idempotencyKey, CreateModerationActionRequest request, UUID correlationId) {
		validateKey(idempotencyKey);
		if (request == null || request.action() == null || request.reasonCode() == null
				|| !request.reasonCode().matches("[A-Z0-9_]{1,64}")) throw invalid("Moderation action is invalid");
		var fingerprint = fingerprint(caseId.toString(), request.action().name(), request.reasonCode());
		var replay = jdbc.sql("select request_fingerprint, case_id from community_moderation_action where actor_subject = :actor and idempotency_key = :key")
				.param("actor", actorSubject).param("key", idempotencyKey)
				.query((rs, rowNum) -> new Replay(rs.getString(1), rs.getObject(2, UUID.class))).optional();
		if (replay.isPresent()) {
			var value = replay.orElseThrow();
			if (!value.fingerprint().equals(fingerprint) || !value.caseId().equals(caseId)) throw CommunityApiException.idempotencyKeyReused();
			return get(caseId);
		}
		var target = jdbc.sql("select target_type, target_id, target_author_profile_id from community_moderation_case where id = :id for update")
				.param("id", caseId).query((rs, rowNum) -> new Target(TargetType.valueOf(rs.getString(1)),
						rs.getObject(2, UUID.class), rs.getObject(3, UUID.class))).optional()
				.orElseThrow(CommunityModerationService::caseNotFound);
		var prior = currentTarget(target);
		var outcome = applyAction(actorSubject, caseId, target, prior, request);
		var now = clock.instant();
		jdbc.sql("""
				insert into community_moderation_action
				(id, case_id, actor_subject, action, reason_code, prior_state, resulting_state, target_version, idempotency_key, request_fingerprint, created_at)
				values (:id, :caseId, :actor, :action, :reason, :prior, :result, :version, :key, :fingerprint, :now)
				""").param("id", UUID.randomUUID()).param("caseId", caseId).param("actor", actorSubject)
				.param("action", request.action().name()).param("reason", request.reasonCode())
				.param("prior", outcome.priorState()).param("result", outcome.resultingState())
				.param("version", outcome.target().version())
				.param("key", idempotencyKey).param("fingerprint", fingerprint).param("now", timestamp(now)).update();
		jdbc.sql("update community_moderation_case set state = 'RESOLVED', updated_at = :now, version = version + 1 where id = :id")
				.param("now", timestamp(now)).param("id", caseId).update();

		if (correlationId != null) {
			String eventType;
			String actionName;

			if (request.action() == Action.REMOVE && target.type() == TargetType.POST) {
				eventType = "community.post.removed";
				actionName = "COMMUNITY_POST_REMOVED";
			} else if (request.action() == Action.RESTRICT_COMMUNITY_ACCESS) {
				eventType = "community.user.suspended";
				actionName = "COMMUNITY_USER_SUSPENDED";
			} else if (request.action() == Action.NO_ACTION) {
				eventType = "community.moderation.case-resolved";
				actionName = "MODERATION_CASE_RESOLVED";
			} else {
				eventType = "community.moderation.action-applied";
				actionName = "MODERATION_ACTION_APPLIED";
			}

			UUID authorAccountSubject = null;
			if (target.authorId() != null) {
				authorAccountSubject = jdbc.sql("select account_subject from community_profile where id = :profileId")
						.param("profileId", target.authorId()).query(UUID.class).optional().orElse(null);
			}

			UUID targetAccountId = authorAccountSubject;
			String targetIdentifier = authorAccountSubject != null ? ("account:" + authorAccountSubject) : null;

			var auditEvent = new CommunityAdminAuditEvent(
					UUID.randomUUID(),
					eventType,
					now,
					"community-service",
					"1.0",
					"COMMUNITY",
					"COMMUNITY_MODERATION",
					actorSubject,
					"ADMIN",
					actionName,
					"SUCCEEDED",
					request.reasonCode(),
					correlationId,
					targetAccountId,
					targetIdentifier
			);

			try {
				String payloadJson = objectMapper.writeValueAsString(auditEvent);
				jdbc.sql("""
						insert into community_interaction_outbox (
						    id, deduplication_key, target_id, event_payload, occurred_at, created_at
						) values (
						    :id, :dedupKey, :targetId, :payload::jsonb, :occurredAt, :createdAt
						) on conflict (deduplication_key) do nothing
						""").param("id", auditEvent.eventId())
						.param("dedupKey", "audit:moderation:" + caseId + ":" + outcome.target().version() + ":" + request.action().name())
						.param("targetId", targetAccountId != null ? targetAccountId : caseId)
						.param("payload", payloadJson)
						.param("occurredAt", timestamp(now))
						.param("createdAt", timestamp(now))
						.update();
			}
			catch (Exception e) {
				throw new IllegalStateException("Failed to persist community admin audit outbox event", e);
			}
		}
		return get(caseId);
	}

	@Transactional(readOnly = true)
	boolean isRestricted(UUID subject) {
		return jdbc.sql("""
				select exists(select 1 from community_access_restriction restriction
				join community_profile profile on profile.id = restriction.profile_id
				where profile.account_subject = :subject and restriction.lifted_at is null)
				""").param("subject", subject).query(Boolean.class).single();
	}

	private UUID createCase(CreateReportRequest request, Snapshot snapshot, Instant now) {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into community_moderation_case
				(id, target_type, target_id, target_author_profile_id, state, priority, evidence_content, evidence_state, evidence_version, created_at, updated_at, version)
				values (:id, :type, :target, :author, 'OPEN', :priority, :content, :state, :version, :now, :now, 0)
				on conflict (target_type, target_id) do nothing
				""").param("id", id).param("type", request.targetType().name()).param("target", request.targetId())
				.param("author", snapshot.authorId()).param("priority", request.reason() == ReportReason.SELF_HARM_OR_CRISIS_CONCERN ? "HIGH" : "NORMAL")
				.param("content", snapshot.content()).param("state", snapshot.state()).param("version", snapshot.version())
				.param("now", timestamp(now)).update();
		return jdbc.sql("select id from community_moderation_case where target_type = :type and target_id = :target")
				.param("type", request.targetType().name()).param("target", request.targetId()).query(UUID.class).single();
	}

	private Snapshot visibleSnapshot(TargetType type, UUID targetId, UUID viewerId) {
		var sql = type == TargetType.POST ? """
				select post.content, post.state, post.version, post.author_profile_id, post.sensitive_content_warning
				from community_post post where post.id = :target and post.state = 'ACTIVE'
				and not exists (select 1 from community_block block where
				(block.blocker_profile_id = :viewer and block.blocked_profile_id = post.author_profile_id)
				or (block.blocker_profile_id = post.author_profile_id and block.blocked_profile_id = :viewer))
				and not exists (select 1 from community_content_hide hide where hide.hider_profile_id = :viewer and hide.target_type = 'POST' and hide.target_id = post.id)
				""" : """
				select comment.content, comment.state, comment.version, comment.author_profile_id, cast(null as varchar)
				from community_comment comment join community_post post on post.id = comment.post_id
				where comment.id = :target and comment.state = 'ACTIVE' and post.state = 'ACTIVE'
				and not exists (select 1 from community_block block where
				(block.blocker_profile_id = :viewer and block.blocked_profile_id in (comment.author_profile_id, post.author_profile_id))
				or (block.blocked_profile_id = :viewer and block.blocker_profile_id in (comment.author_profile_id, post.author_profile_id)))
				and not exists (select 1 from community_content_hide hide where hide.hider_profile_id = :viewer and ((hide.target_type = 'POST' and hide.target_id = post.id) or (hide.target_type = 'COMMENT' and hide.target_id = comment.id)))
				""";
		return jdbc.sql(sql).param("target", targetId).param("viewer", viewerId)
				.query((rs, rowNum) -> new Snapshot(rs.getString(1), rs.getString(2), rs.getLong(3),
						rs.getObject(4, UUID.class), rs.getString(5)))
				.optional().orElseThrow(type == TargetType.POST ? CommunityApiException::postNotFound : CommunityApiException::commentNotFound);
	}

	private Snapshot currentTarget(Target target) {
		var table = target.type() == TargetType.POST ? "community_post" : "community_comment";
		var warning = target.type() == TargetType.POST ? "sensitive_content_warning" : "cast(null as varchar)";
		return jdbc.sql("select content, state, version, author_profile_id, " + warning + " from " + table
				+ " where id = :id for update")
				.param("id", target.id()).query((rs, rowNum) -> new Snapshot(rs.getString(1), rs.getString(2),
						rs.getLong(3), rs.getObject(4, UUID.class), rs.getString(5))).optional()
				.orElseThrow(CommunityModerationService::caseNotFound);
	}

	private ActionOutcome applyAction(UUID actor, UUID caseId, Target target, Snapshot prior,
			CreateModerationActionRequest request) {
		if (request.action() == Action.NO_ACTION) return unchanged(prior, prior.state());
		if (request.action() == Action.APPLY_SENSITIVE_WARNING
				|| request.action() == Action.REMOVE_SENSITIVE_WARNING) {
			return applySensitiveWarning(target, prior, request.action());
		}
		if (request.action() == Action.RESTRICT_COMMUNITY_ACCESS) {
			jdbc.sql("""
					insert into community_access_restriction
					(profile_id, case_id, reason_code, restricted_by_subject, restricted_at)
					values (:profile, :caseId, :reason, :actor, :now)
					on conflict (profile_id) do update set case_id = excluded.case_id, reason_code = excluded.reason_code,
					restricted_by_subject = excluded.restricted_by_subject, restricted_at = excluded.restricted_at,
					lifted_by_subject = null, lifted_at = null
					""").param("profile", target.authorId()).param("caseId", caseId).param("reason", request.reasonCode())
					.param("actor", actor).param("now", timestamp(clock.instant())).update();
			var resulting = new Snapshot(prior.content(), prior.state(), prior.version(), prior.authorId(), prior.warning());
			return new ActionOutcome(resulting, prior.state(), "COMMUNITY_ACCESS_RESTRICTED");
		}
		var nextState = switch (request.action()) {
			case HIDE -> "MODERATION_HIDDEN";
			case REMOVE -> "MODERATION_REMOVED";
			case RESTORE -> "ACTIVE";
			default -> prior.state();
		};
		if (nextState.equals(prior.state())) return unchanged(prior, prior.state());
		if (request.action() == Action.RESTORE && !prior.state().startsWith("MODERATION_")) throw invalid("Only moderated content can be restored");
		var table = target.type() == TargetType.POST ? "community_post" : "community_comment";
		jdbc.sql("update " + table + " set state = :state, updated_at = :now, version = version + 1 where id = :id")
				.param("state", nextState).param("now", timestamp(clock.instant())).param("id", target.id()).update();
		if (target.type() == TargetType.COMMENT) {
			adjustCommentCount(target.id(), prior.state(), nextState);
			jdbc.sql("""
					insert into community_comment_revision
					(id, comment_id, changed_by_profile_id, changed_by_subject, change_type, content_snapshot, state_snapshot, comment_version, changed_at)
					values (:id, :comment, null, :actor, 'MODERATED', :content, :state, :version, :now)
					""").param("id", UUID.randomUUID()).param("comment", target.id()).param("actor", actor)
					.param("content", prior.content()).param("state", nextState).param("version", prior.version() + 1)
					.param("now", timestamp(clock.instant())).update();
		}
		var resulting = new Snapshot(prior.content(), nextState, prior.version() + 1, prior.authorId(), prior.warning());
		return new ActionOutcome(resulting, prior.state(), nextState);
	}

	private ActionOutcome applySensitiveWarning(Target target, Snapshot prior, Action action) {
		if (target.type() != TargetType.POST) {
			throw invalid("Sensitive-content warnings apply only to posts");
		}
		var desired = action == Action.APPLY_SENSITIVE_WARNING ? "SENSITIVE_CONTENT" : null;
		var priorWarning = warningState(prior.warning());
		var resultingWarning = warningState(desired);
		if (java.util.Objects.equals(prior.warning(), desired)) {
			return new ActionOutcome(prior, priorWarning, resultingWarning);
		}
		jdbc.sql("""
				update community_post
				set sensitive_content_warning = :warning, updated_at = :now, version = version + 1
				where id = :id
				""").param("warning", desired).param("now", timestamp(clock.instant())).param("id", target.id()).update();
		var resulting = new Snapshot(prior.content(), prior.state(), prior.version() + 1, prior.authorId(), desired);
		return new ActionOutcome(resulting, priorWarning, resultingWarning);
	}

	private ActionOutcome unchanged(Snapshot snapshot, String state) {
		return new ActionOutcome(snapshot, state, state);
	}

	private String warningState(String warning) {
		return warning == null ? "NONE" : warning;
	}

	private void adjustCommentCount(UUID commentId, String prior, String next) {
		if (prior.equals("ACTIVE") && !next.equals("ACTIVE")) {
			jdbc.sql("update community_post set comment_count = greatest(comment_count - 1, 0), updated_at = :now, version = version + 1 where id = (select post_id from community_comment where id = :id)")
					.param("now", timestamp(clock.instant())).param("id", commentId).update();
		}
		else if (!prior.equals("ACTIVE") && next.equals("ACTIVE")) {
			jdbc.sql("update community_post set comment_count = comment_count + 1, updated_at = :now, version = version + 1 where id = (select post_id from community_comment where id = :id)")
					.param("now", timestamp(clock.instant())).param("id", commentId).update();
		}
	}

	private UUID ensureProfile(UUID subject) {
		var existing = jdbc.sql("select id from community_profile where account_subject = :subject and status = 'ACTIVE'")
				.param("subject", subject).query(UUID.class).optional();
		if (existing.isPresent()) return existing.orElseThrow();
		var id = UUID.randomUUID();
		var now = clock.instant();
		jdbc.sql("""
				insert into community_profile (id, account_subject, display_name, status, created_at, updated_at, version)
				values (:id, :subject, :name, 'ACTIVE', :now, :now, 0) on conflict (account_subject) do nothing
				""").param("id", id).param("subject", subject).param("name", DEFAULT_DISPLAY_NAME).param("now", timestamp(now)).update();
		return jdbc.sql("select id from community_profile where account_subject = :subject and status = 'ACTIVE'")
				.param("subject", subject).query(UUID.class).optional().orElseThrow(CommunityApiException::communityAccessUnavailable);
	}

	private boolean activeProfileExists(UUID id) {
		return jdbc.sql("select exists(select 1 from community_profile where id = :id and status = 'ACTIVE')")
				.param("id", id).query(Boolean.class).single();
	}

	private Timestamp timestamp(Instant value) {
		return Timestamp.from(value);
	}

	private String normalizeDetails(String value) {
		if (value == null) return null;
		var normalized = Normalizer.normalize(value.strip(), Normalizer.Form.NFC);
		if (normalized.isEmpty() || normalized.codePointCount(0, normalized.length()) > MAX_DETAILS_CODE_POINTS
				|| normalized.codePoints().anyMatch(codePoint -> Character.getType(codePoint) == Character.CONTROL)) {
			throw invalid("Report details must contain at most 1000 visible characters");
		}
		return normalized;
	}

	private void validateKey(String value) {
		if (value == null || value.length() < 16 || value.length() > 128 || !value.matches("[!-~]+")) {
			throw CommunityApiException.invalidIdempotencyKey();
		}
	}

	String fingerprint(String... values) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			for (var value : values) {
				var bytes = value.getBytes(StandardCharsets.UTF_8);
				digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
				digest.update(bytes);
			}
			return HexFormat.of().formatHex(digest.digest());
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static CommunityApiException invalid(String message) {
		return new CommunityApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
	}

	private static CommunityApiException caseNotFound() {
		return new CommunityApiException(HttpStatus.NOT_FOUND, "COMMUNITY_MODERATION_CASE_NOT_FOUND", "Moderation case was not found");
	}

	record Snapshot(String content, String state, long version, UUID authorId, String warning) {
	}

	record ActionOutcome(Snapshot target, String priorState, String resultingState) {
	}

	record Target(TargetType type, UUID id, UUID authorId) {
	}

	record Replay(String fingerprint, UUID caseId) {
	}

	record CaseRow(UUID id, TargetType targetType, UUID targetId, CaseState state, Priority priority,
			String evidenceContent, String evidenceState, long evidenceVersion, Instant createdAt, Instant updatedAt,
			long version) {
	}
}
