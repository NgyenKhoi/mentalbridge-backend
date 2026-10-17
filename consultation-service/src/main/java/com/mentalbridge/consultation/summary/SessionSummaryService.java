package com.mentalbridge.consultation.summary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.shared.ApiException;

@Service
public class SessionSummaryService {

	private static final String SCHEMA_VERSION = "session-summary-v1";
	private static final Duration BRIEF_ACCESS_WINDOW = Duration.ofHours(24);
	private final JdbcClient jdbc;
	private final ObjectMapper json;
	private final Clock clock;

	public SessionSummaryService(JdbcClient jdbc, ObjectMapper json, Clock clock) {
		this.jdbc = jdbc;
		this.json = json;
		this.clock = clock;
	}

	@Transactional
	public SessionSummaryResponse publish(UUID specialistId, UUID appointmentId, String idempotencyKey,
			Long expectedVersion, PublishSessionSummaryRequest request) {
		validateResourceSteps(request.agreedNextSteps());
		var requestHash = hash(request);
		var replay = jdbc.sql("""
				select id, appointment_id, request_hash from session_summary
				where specialist_account_id=:specialistId and idempotency_key=:key
				""").param("specialistId", specialistId).param("key", idempotencyKey)
				.query((rs, row) -> new Command(rs.getObject("id", UUID.class),
						rs.getObject("appointment_id", UUID.class), rs.getString("request_hash")))
				.optional();
		if (replay.isPresent()) {
			if (!appointmentId.equals(replay.get().appointmentId())
					|| !MessageDigest.isEqual(replay.get().requestHash().getBytes(StandardCharsets.UTF_8),
					requestHash.getBytes(StandardCharsets.UTF_8))) {
				throw new ApiException(HttpStatus.CONFLICT, "SESSION_SUMMARY_IDEMPOTENCY_CONFLICT",
						"The idempotency key was already used for different summary content");
			}
			return readById(replay.get().id(), false);
		}

		var appointment = jdbc.sql("""
				select id, user_account_id, specialist_account_id, status, completion_fact_id
				from appointment where id=:appointmentId and specialist_account_id=:specialistId
				for update
				""").param("appointmentId", appointmentId).param("specialistId", specialistId)
				.query((rs, row) -> new AppointmentAuthority(rs.getObject("user_account_id", UUID.class),
						rs.getString("status"), rs.getObject("completion_fact_id", UUID.class)))
				.optional().orElseThrow(() -> notFound());
		if (!"COMPLETED".equals(appointment.status()) || appointment.completionFactId() == null) {
			throw new ApiException(HttpStatus.CONFLICT, "SESSION_SUMMARY_REQUIRES_COMPLETED_APPOINTMENT",
					"A session summary can be published only after evidence-backed completion");
		}

		var previous = jdbc.sql("""
				select id, summary_version from session_summary
				where appointment_id=:appointmentId order by summary_version desc limit 1
				""").param("appointmentId", appointmentId)
				.query((rs, row) -> new PreviousSummary(rs.getObject("id", UUID.class), rs.getLong("summary_version")))
				.optional();
		if (previous.isPresent() && expectedVersion == null) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "SESSION_SUMMARY_VERSION_REQUIRED",
					"If-Match must contain the quoted current summary version for an amendment");
		}
		if (previous.isPresent() && expectedVersion.longValue() != previous.get().version()) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "SESSION_SUMMARY_VERSION_MISMATCH",
					"The session summary changed before this amendment was published");
		}
		if (previous.isEmpty() && expectedVersion != null) {
			throw new ApiException(HttpStatus.PRECONDITION_FAILED, "SESSION_SUMMARY_VERSION_MISMATCH",
					"No published session summary exists for the supplied version");
		}

		var summaryId = UUID.randomUUID();
		var version = previous.map(item -> item.version() + 1).orElse(1L);
		var now = clock.instant();
		jdbc.sql("""
				insert into session_summary (
				 id, appointment_id, user_account_id, specialist_account_id, summary_version,
				 schema_version, topics_discussed, progress_summary, specialist_note_for_user,
				 follow_up_suggested, amends_summary_id, idempotency_key, request_hash, published_at
				) values (:id, :appointmentId, :userId, :specialistId, :version,
				 :schemaVersion, cast(:topics as jsonb), :progressSummary, :noteForUser,
				 :followUp, :amendsId, :key, :requestHash, :publishedAt)
				""").param("id", summaryId).param("appointmentId", appointmentId)
				.param("userId", appointment.userId()).param("specialistId", specialistId)
				.param("version", version).param("schemaVersion", SCHEMA_VERSION)
				.param("topics", writeJson(request.topicsDiscussed()))
				.param("progressSummary", clean(request.progressSummary()))
				.param("noteForUser", clean(request.specialistNoteForUser()))
				.param("followUp", request.followUpSuggested())
				.param("amendsId", previous.map(PreviousSummary::id).orElse(null))
				.param("key", idempotencyKey).param("requestHash", requestHash)
				.param("publishedAt", Timestamp.from(now)).update();
		for (int index = 0; index < request.agreedNextSteps().size(); index++) {
			var step = request.agreedNextSteps().get(index);
			var stepId = UUID.randomUUID();
			jdbc.sql("""
					insert into agreed_next_step (
					 id, summary_id, ordinal, step_type, title, details, resource_id, resource_version,
					 resource_proposal_reason_code, created_at
					) values (:id, :summaryId, :ordinal, :type, :title, :details, :resourceId, :resourceVersion,
					 :reasonCode, :now)
					""").param("id", stepId).param("summaryId", summaryId).param("ordinal", index)
					.param("type", step.type().name()).param("title", step.title().trim())
					.param("details", clean(step.details())).param("resourceId", step.resourceId())
					.param("resourceVersion", clean(step.resourceVersion()))
					.param("reasonCode", step.resourceProposalReasonCode() == null ? null
							: step.resourceProposalReasonCode().name())
					.param("now", Timestamp.from(now)).update();
			jdbc.sql("""
					insert into agreed_next_step_state (next_step_id, user_account_id, updated_at)
					values (:stepId, :userId, :now)
					""").param("stepId", stepId).param("userId", appointment.userId())
					.param("now", Timestamp.from(now)).update();
		}
		jdbc.sql("""
				insert into session_summary_reuse_consent (summary_id, user_account_id, updated_at)
				values (:summaryId, :userId, :now)
				""").param("summaryId", summaryId).param("userId", appointment.userId())
				.param("now", Timestamp.from(now)).update();
		return readById(summaryId, false);
	}

	@Transactional(readOnly = true)
	public SessionSummaryResponse.ListResponse specialistHistory(UUID specialistId, UUID appointmentId) {
		requireAppointmentActor(appointmentId, specialistId, false);
		return history(appointmentId, false);
	}

	@Transactional(readOnly = true)
	public SessionSummaryResponse.ListResponse userHistory(UUID userId, UUID appointmentId) {
		requireAppointmentActor(appointmentId, userId, true);
		return history(appointmentId, true);
	}

	@Transactional
	public SessionSummaryResponse updateConsent(UUID userId, UUID summaryId, long expectedVersion,
			boolean approved) {
		var current = jdbc.sql("""
				select c.version, c.approved from session_summary_reuse_consent c
				join session_summary s on s.id=c.summary_id
				where c.summary_id=:summaryId and s.user_account_id=:userId for update
				""").param("summaryId", summaryId).param("userId", userId)
				.query((rs, row) -> new Consent(rs.getLong("version"), rs.getBoolean("approved")))
				.optional().orElseThrow(() -> notFound());
		if (current.version() != expectedVersion) throw versionMismatch();
		if (current.approved() != approved) {
			var now = clock.instant();
			jdbc.sql("""
					update session_summary_reuse_consent
					set approved=:approved, version=version+1, updated_at=:now,
					    approved_at=case when :approved then cast(:now as timestamptz) else null end,
					    revoked_at=case when :approved then null else cast(:now as timestamptz) end
					where summary_id=:summaryId
					""").param("approved", approved).param("now", Timestamp.from(now))
					.param("summaryId", summaryId).update();
		}
		return readById(summaryId, true);
	}

	@Transactional
	public SessionSummaryResponse updateNextStep(UUID userId, UUID nextStepId, long expectedVersion,
			AgreedNextStepState state, boolean hidden) {
		var owner = jdbc.sql("""
				select s.id as summary_id, st.version
				from agreed_next_step_state st
				join agreed_next_step ns on ns.id=st.next_step_id
				join session_summary s on s.id=ns.summary_id
				where st.next_step_id=:stepId and st.user_account_id=:userId for update
				""").param("stepId", nextStepId).param("userId", userId)
				.query((rs, row) -> new StepOwner(rs.getObject("summary_id", UUID.class), rs.getLong("version")))
				.optional().orElseThrow(() -> notFound());
		if (owner.version() != expectedVersion) throw versionMismatch();
		jdbc.sql("""
				update agreed_next_step_state
				set state=:state, hidden=:hidden, version=version+1, updated_at=:now
				where next_step_id=:stepId
				""").param("state", state.name()).param("hidden", hidden)
				.param("now", Timestamp.from(clock.instant())).param("stepId", nextStepId).update();
		return readById(owner.summaryId(), true);
	}

	@Transactional(readOnly = true)
	public SessionSummaryResponse reusable(UUID actorId, boolean user, boolean specialist,
			UUID targetAppointmentId, UUID summaryId, long version) {
		var target = jdbc.sql("""
				select user_account_id, status, scheduled_start_at from appointment
				where id=:appointmentId and ((:userRole and user_account_id=:actorId)
				 or (:specialistRole and specialist_account_id=:actorId))
				""").param("appointmentId", targetAppointmentId).param("userRole", user)
				.param("specialistRole", specialist).param("actorId", actorId)
				.query((rs, row) -> new ReuseTarget(rs.getObject("user_account_id", UUID.class),
						rs.getString("status"), rs.getTimestamp("scheduled_start_at").toInstant()))
				.optional().orElseThrow(() -> notFound());
		if (!List.of("CONFIRMED", "IN_PROGRESS").contains(target.status())) throw reuseNotEligible();
		var now = clock.instant();
		if (user && !now.isBefore(target.scheduledStartAt())) throw reuseNotEligible();
		if (specialist && (now.isBefore(target.scheduledStartAt().minus(BRIEF_ACCESS_WINDOW))
				|| now.isAfter(target.scheduledStartAt().plus(BRIEF_ACCESS_WINDOW)))) throw reuseNotEligible();

		var source = jdbc.sql("""
				select s.appointment_id, s.user_account_id, s.summary_version, c.approved,
				       source.scheduled_end_at
				from session_summary s
				join session_summary_reuse_consent c on c.summary_id=s.id
				join appointment source on source.id=s.appointment_id
				where s.id=:summaryId
				""").param("summaryId", summaryId)
				.query((rs, row) -> new ReuseSource(rs.getObject("appointment_id", UUID.class),
						rs.getObject("user_account_id", UUID.class), rs.getLong("summary_version"),
						rs.getBoolean("approved"), rs.getTimestamp("scheduled_end_at").toInstant()))
				.optional().orElseThrow(() -> notFound());
		if (source.version() != version || !source.approved()) {
			throw new ApiException(HttpStatus.FORBIDDEN, "SESSION_SUMMARY_REUSE_NOT_APPROVED",
				"The exact session summary version is not approved for reuse");
		}
		if (!source.userId().equals(target.userId()) || source.appointmentId().equals(targetAppointmentId)
				|| !source.scheduledEndAt().isBefore(target.scheduledStartAt())) throw reuseNotEligible();
		return readById(summaryId, false);
	}

	@Transactional(readOnly = true)
	public ResourceProposalResponse resourceProposal(UUID actorId, UUID proposalId) {
		var proposal = jdbc.sql("""
				select ns.id, ns.summary_id, ns.resource_id, ns.resource_version,
				       ns.resource_proposal_reason_code, ns.title, ns.details,
				       s.appointment_id, s.user_account_id, s.specialist_account_id,
				       s.summary_version, s.schema_version, s.published_at,
				       a.status, a.completion_fact_id,
				       st.state, st.hidden,
				       (select max(latest.summary_version) from session_summary latest
				        where latest.appointment_id=s.appointment_id) as latest_summary_version
				from agreed_next_step ns
				join session_summary s on s.id=ns.summary_id
				join appointment a on a.id=s.appointment_id
				join agreed_next_step_state st on st.next_step_id=ns.id
				where ns.id=:proposalId and ns.step_type='PLATFORM_RESOURCE'
				  and (s.user_account_id=:actorId or s.specialist_account_id=:actorId)
				""").param("proposalId", proposalId).param("actorId", actorId)
				.query((rs, row) -> new ProposalRow(
						rs.getObject("id", UUID.class), rs.getObject("summary_id", UUID.class),
						rs.getObject("appointment_id", UUID.class), rs.getObject("user_account_id", UUID.class),
						rs.getObject("specialist_account_id", UUID.class), rs.getLong("summary_version"),
						rs.getLong("latest_summary_version"), rs.getString("schema_version"),
						rs.getTimestamp("published_at").toInstant(), rs.getString("status"),
						rs.getObject("completion_fact_id", UUID.class), rs.getObject("resource_id", UUID.class),
						rs.getString("resource_version"), rs.getString("resource_proposal_reason_code"),
						rs.getString("title"), rs.getString("details"), rs.getString("state"),
						rs.getBoolean("hidden")))
				.optional().orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
						"RESOURCE_PROPOSAL_NOT_FOUND", "The resource proposal was not found"));
		if (!"COMPLETED".equals(proposal.appointmentStatus()) || proposal.completionFactId() == null
				|| proposal.summaryVersion() != proposal.latestSummaryVersion()
				|| "SKIPPED".equals(proposal.state()) || proposal.hidden()) {
			throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_PROPOSAL_STALE",
					"The resource proposal is no longer eligible for review");
		}
		return new ResourceProposalResponse(proposal.id(), proposal.summaryVersion(), proposal.appointmentId(),
				proposal.userId(), proposal.specialistId(), proposal.summaryId(), proposal.summaryVersion(),
				proposal.completionFactId(), proposal.resourceId(), proposal.resourceVersion(),
				ResourceProposalReasonCode.valueOf(proposal.reasonCode()), proposal.title(), proposal.details(),
				proposal.schemaVersion(), proposal.proposedAt());
	}

	private SessionSummaryResponse.ListResponse history(UUID appointmentId, boolean includeUserState) {
		var ids = jdbc.sql("""
				select id from session_summary where appointment_id=:appointmentId
				order by summary_version desc
				""").param("appointmentId", appointmentId).query(UUID.class).list();
		var items = ids.stream().map(id -> readById(id, includeUserState)).toList();
		return new SessionSummaryResponse.ListResponse(items, items.size(), clock.instant());
	}

	private SessionSummaryResponse readById(UUID id, boolean includeUserState) {
		var summary = jdbc.sql("""
				select s.*, c.approved, c.version as consent_version, c.updated_at as consent_updated_at
				from session_summary s join session_summary_reuse_consent c on c.summary_id=s.id
				where s.id=:id
				""").param("id", id).query((rs, row) -> mapSummary(rs)).single();
		var steps = jdbc.sql("""
				select ns.*, st.state, st.hidden, st.version as state_version, st.updated_at as state_updated_at
				from agreed_next_step ns join agreed_next_step_state st on st.next_step_id=ns.id
				where ns.summary_id=:id order by ns.ordinal
				""").param("id", id).query((rs, row) -> new SessionSummaryResponse.AgreedNextStep(
					rs.getObject("id", UUID.class), AgreedNextStepType.valueOf(rs.getString("step_type")),
					rs.getString("title"), rs.getString("details"), rs.getObject("resource_id", UUID.class),
					rs.getString("resource_version"), rs.getString("resource_proposal_reason_code") == null ? null
							: ResourceProposalReasonCode.valueOf(rs.getString("resource_proposal_reason_code")),
					includeUserState ? AgreedNextStepState.valueOf(rs.getString("state")) : null,
					includeUserState && rs.getBoolean("hidden"), includeUserState ? rs.getLong("state_version") : null,
					includeUserState ? rs.getTimestamp("state_updated_at").toInstant() : null)).list();
		return new SessionSummaryResponse(summary.id(), summary.appointmentId(), summary.userId(), summary.specialistId(),
				summary.version(), summary.schemaVersion(), summary.topics(), summary.progressSummary(), summary.noteForUser(),
				summary.followUpSuggested(), summary.amendsId(), summary.publishedAt(),
				includeUserState ? summary.consent() : null, steps);
	}

	private SummaryRow mapSummary(ResultSet rs) throws SQLException {
		return new SummaryRow(rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class),
				rs.getObject("user_account_id", UUID.class), rs.getObject("specialist_account_id", UUID.class),
				rs.getLong("summary_version"), rs.getString("schema_version"), readTopics(rs.getString("topics_discussed")),
				rs.getString("progress_summary"), rs.getString("specialist_note_for_user"),
				rs.getBoolean("follow_up_suggested"), rs.getObject("amends_summary_id", UUID.class),
				rs.getTimestamp("published_at").toInstant(), new SessionSummaryResponse.ReuseConsent(
					rs.getBoolean("approved"), rs.getLong("consent_version"),
					rs.getTimestamp("consent_updated_at").toInstant()));
	}

	private void requireAppointmentActor(UUID appointmentId, UUID actorId, boolean user) {
		var column = user ? "user_account_id" : "specialist_account_id";
		if (jdbc.sql("select count(*) from appointment where id=:id and " + column + "=:actorId")
				.param("id", appointmentId).param("actorId", actorId).query(Long.class).single() != 1) throw notFound();
	}

	private void validateResourceSteps(List<PublishSessionSummaryRequest.NextStep> steps) {
		for (var step : steps) {
			var resource = step.type() == AgreedNextStepType.PLATFORM_RESOURCE;
			var hasProposal = step.resourceId() != null && clean(step.resourceVersion()) != null
					&& step.resourceProposalReasonCode() != null;
			var hasAnyProposalField = step.resourceId() != null || clean(step.resourceVersion()) != null
					|| step.resourceProposalReasonCode() != null;
			if ((resource && !hasProposal) || (!resource && hasAnyProposalField)) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AGREED_NEXT_STEP",
						"Platform resources require an exact resource id, version, and reason; other steps must omit them");
			}
		}
	}

	private String hash(PublishSessionSummaryRequest request) {
		try {
			var digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(writeJson(request).getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private String writeJson(Object value) {
		try { return json.writeValueAsString(value); }
		catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
	}

	private List<String> readTopics(String value) {
		try { return json.readValue(value, new TypeReference<>() { }); }
		catch (JsonProcessingException exception) { throw new IllegalStateException(exception); }
	}

	private String clean(String value) {
		if (value == null || value.isBlank()) return null;
		return value.trim();
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "SESSION_SUMMARY_NOT_FOUND", "The session summary was not found");
	}

	private ApiException versionMismatch() {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "SESSION_SUMMARY_VERSION_MISMATCH",
				"The session summary state changed before this command was applied");
	}

	private ApiException reuseNotEligible() {
		return new ApiException(HttpStatus.FORBIDDEN, "SESSION_SUMMARY_REUSE_NOT_ELIGIBLE",
				"The session summary is not eligible for this later appointment");
	}

	private record AppointmentAuthority(UUID userId, String status, UUID completionFactId) { }
	private record PreviousSummary(UUID id, long version) { }
	private record Command(UUID id, UUID appointmentId, String requestHash) { }
	private record Consent(long version, boolean approved) { }
	private record StepOwner(UUID summaryId, long version) { }
	private record ReuseTarget(UUID userId, String status, Instant scheduledStartAt) { }
	private record ReuseSource(UUID appointmentId, UUID userId, long version, boolean approved,
			Instant scheduledEndAt) { }
	private record ProposalRow(UUID id, UUID summaryId, UUID appointmentId, UUID userId,
			UUID specialistId, long summaryVersion, long latestSummaryVersion, String schemaVersion,
			Instant proposedAt, String appointmentStatus, UUID completionFactId, UUID resourceId,
			String resourceVersion, String reasonCode, String title, String details, String state,
			boolean hidden) { }
	private record SummaryRow(UUID id, UUID appointmentId, UUID userId, UUID specialistId, long version,
			String schemaVersion, List<String> topics, String progressSummary, String noteForUser,
			boolean followUpSuggested, UUID amendsId, Instant publishedAt,
			SessionSummaryResponse.ReuseConsent consent) { }
}
