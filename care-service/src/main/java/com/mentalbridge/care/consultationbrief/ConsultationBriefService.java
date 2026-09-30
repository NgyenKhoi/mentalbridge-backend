package com.mentalbridge.care.consultationbrief;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.shared.ApiException;

@Service
public class ConsultationBriefService {

	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
	private static final TypeReference<List<ScreeningContext>> SCREENING_LIST = new TypeReference<>() { };

	private final AppointmentContextClient appointments;
	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;
	private final ObjectMapper json;
	private final Clock clock;

	public ConsultationBriefService(AppointmentContextClient appointments, JdbcClient jdbc,
			TransactionTemplate transactions, ObjectMapper json, Clock clock) {
		this.appointments = appointments;
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.json = json;
		this.clock = clock;
	}

	public BriefView saveDraft(UUID userId, String bearerToken, UUID correlationId, UUID appointmentId,
			Long expectedVersion, SaveDraftCommand command) {
		var context = appointments.get(appointmentId, bearerToken, correlationId);
		requireOwnerConfirmed(context, userId);
		return transactions.execute(status -> saveDraft(context, userId, correlationId, expectedVersion, command));
	}

	public ScreeningContextList screeningContexts(UUID userId) {
		var choices = jdbc.sql("""
				select id,evaluated_at from support_evaluation_v2 where user_id=:userId
				order by evaluated_at desc,id desc limit 20
				""").param("userId", userId).query((rs, row) -> new EvaluationReference(
					rs.getObject("id", UUID.class), rs.getTimestamp("evaluated_at").toInstant())).list()
				.stream().map(reference -> new ScreeningContextChoice(reference.id(), reference.evaluatedAt(),
						screening(userId, reference.id()))).toList();
		return new ScreeningContextList(choices, choices.size());
	}

	public BriefView own(UUID userId, String bearerToken, UUID correlationId, UUID appointmentId) {
		var context = appointments.get(appointmentId, bearerToken, correlationId);
		if (!context.userAccountId().equals(userId)) throw notFound();
		return transactions.execute(status -> ownView(appointmentId, userId));
	}

	public BriefView approve(UUID userId, String bearerToken, UUID correlationId, UUID appointmentId,
			long expectedVersion) {
		var context = appointments.get(appointmentId, bearerToken, correlationId);
		requireOwnerConfirmed(context, userId);
		return transactions.execute(status -> approve(context, userId, correlationId, expectedVersion));
	}

	public BriefView revoke(UUID userId, String bearerToken, UUID correlationId, UUID appointmentId,
			long expectedVersion) {
		var context = appointments.get(appointmentId, bearerToken, correlationId);
		if (!context.userAccountId().equals(userId)) throw notFound();
		return transactions.execute(status -> revoke(context, userId, correlationId, expectedVersion));
	}

	public void delete(UUID userId, String bearerToken, UUID correlationId, UUID appointmentId,
			long expectedVersion) {
		var context = appointments.get(appointmentId, bearerToken, correlationId);
		if (!context.userAccountId().equals(userId)) throw notFound();
		transactions.executeWithoutResult(status -> delete(context, userId, correlationId, expectedVersion));
	}

	public SpecialistBriefView specialist(UUID specialistId, String bearerToken, UUID correlationId,
			UUID appointmentId) {
		AppointmentContext context;
		try {
			context = appointments.get(appointmentId, bearerToken, correlationId);
		}
		catch (ApiException exception) {
			transactions.executeWithoutResult(status -> audit(appointmentId, null, specialistId, "SPECIALIST", "READ",
					"DENIED", exception.code().equals("APPOINTMENT_CONTEXT_UNAVAILABLE")
							? "APPOINTMENT_AUTHORITY_UNAVAILABLE" : "APPOINTMENT_AUTHORITY_DENIED",
					correlationId, clock.instant()));
			throw exception;
		}
		if (!context.specialistAccountId().equals(specialistId)) {
			transactions.executeWithoutResult(status -> audit(appointmentId, null, specialistId, "SPECIALIST", "READ",
					"DENIED", "APPOINTMENT_ACTOR_MISMATCH", correlationId, clock.instant()));
			throw notFound();
		}
		var decision = transactions.execute(status -> specialistDecision(context, specialistId, correlationId));
		if (decision.error() != null) throw decision.error();
		return decision.view();
	}

	private BriefView saveDraft(AppointmentContext context, UUID userId, UUID correlationId,
			Long expectedVersion, SaveDraftCommand command) {
		var screening = screening(userId, command.supportEvaluationId());
		var existing = briefForUpdate(context.appointmentId());
		var now = clock.instant();
		if (existing == null) {
			if (expectedVersion != null) throw versionMismatch();
			var id = UUID.randomUUID();
			jdbc.sql("""
					insert into consultation_brief (
					 id,appointment_id,user_id,specialist_id,appointment_start_at,appointment_end_at,
					 appointment_version,status,current_situation,support_evaluation_id,user_goals,
					 version,created_at,updated_at
					) values (:id,:appointmentId,:userId,:specialistId,:startAt,:endAt,:appointmentVersion,
					 'DRAFT',:situation,:evaluationId,cast(:goals as jsonb),0,:now,:now)
					""").param("id", id).param("appointmentId", context.appointmentId())
					.param("userId", userId).param("specialistId", context.specialistAccountId())
					.param("startAt", Timestamp.from(context.scheduledStartAt()))
					.param("endAt", Timestamp.from(context.scheduledEndAt()))
					.param("appointmentVersion", context.version()).param("situation", command.currentSituation().trim())
					.param("evaluationId", command.supportEvaluationId()).param("goals", encode(command.userGoals()))
					.param("now", Timestamp.from(now)).update();
			audit(context.appointmentId(), null, userId, "USER", "DRAFT_SAVED", "ALLOWED", "DRAFT_CREATED",
					correlationId, now);
			return view(id, screening);
		}
		if (existing.status().equals("DELETED")) throw new ApiException(HttpStatus.CONFLICT,
				"CONSULTATION_BRIEF_DELETED", "Deleted consultation brief cannot be restored");
		if (existing.status().equals("APPROVED") && activeGrant(existing.id()) != null) {
			throw new ApiException(HttpStatus.CONFLICT, "CONSULTATION_BRIEF_ACCESS_ACTIVE",
					"Revoke specialist access before editing the approved brief");
		}
		requireVersion(existing.version(), expectedVersion);
		var changed = jdbc.sql("""
				update consultation_brief set specialist_id=:specialistId,appointment_start_at=:startAt,
				 appointment_end_at=:endAt,appointment_version=:appointmentVersion,status='DRAFT',
				 current_situation=:situation,support_evaluation_id=:evaluationId,
				 user_goals=cast(:goals as jsonb),version=version+1,updated_at=:now
				where id=:id and version=:version
				""").param("specialistId", context.specialistAccountId())
				.param("startAt", Timestamp.from(context.scheduledStartAt()))
				.param("endAt", Timestamp.from(context.scheduledEndAt()))
				.param("appointmentVersion", context.version()).param("situation", command.currentSituation().trim())
				.param("evaluationId", command.supportEvaluationId()).param("goals", encode(command.userGoals()))
				.param("now", Timestamp.from(now)).param("id", existing.id()).param("version", existing.version()).update();
		if (changed != 1) throw versionMismatch();
		audit(context.appointmentId(), null, userId, "USER", "DRAFT_SAVED", "ALLOWED", "DRAFT_UPDATED",
				correlationId, now);
		return view(existing.id(), screening);
	}

	private BriefView approve(AppointmentContext context, UUID userId, UUID correlationId, long expectedVersion) {
		var brief = requiredBriefForUpdate(context.appointmentId(), userId);
		if (brief.status().equals("DELETED")) throw notFound();
		requireVersion(brief.version(), expectedVersion);
		if (!brief.status().equals("DRAFT")) throw new ApiException(HttpStatus.CONFLICT,
				"CONSULTATION_BRIEF_DRAFT_REQUIRED", "Only the current saved draft can be approved");
		var screening = screening(userId, brief.supportEvaluationId());
		var now = clock.instant();
		var oldGrant = activeGrant(brief.id());
		if (oldGrant != null) {
			jdbc.sql("update consultation_brief_grant set status='REVOKED',revoked_at=:now,version=version+1 where id=:id")
					.param("now", Timestamp.from(now)).param("id", oldGrant.id()).update();
		}
		var nextVersion = brief.version() + 1;
		var snapshotId = UUID.randomUUID();
		jdbc.sql("""
				insert into consultation_brief_snapshot (
				 id,brief_id,snapshot_version,appointment_id,user_id,specialist_id,current_situation,
				 support_evaluation_id,screening_context,user_goals,created_at
				) values (:id,:briefId,:snapshotVersion,:appointmentId,:userId,:specialistId,:situation,
				 :evaluationId,cast(:screening as jsonb),cast(:goals as jsonb),:now)
				""").param("id", snapshotId).param("briefId", brief.id()).param("snapshotVersion", nextVersion)
				.param("appointmentId", context.appointmentId()).param("userId", userId)
				.param("specialistId", context.specialistAccountId()).param("situation", brief.currentSituation())
				.param("evaluationId", brief.supportEvaluationId()).param("screening", encode(screening))
				.param("goals", encode(brief.userGoals())).param("now", Timestamp.from(now)).update();
		var grantId = UUID.randomUUID();
		jdbc.sql("""
				insert into consultation_brief_grant (
				 id,brief_id,snapshot_id,appointment_id,user_id,specialist_id,purpose,status,
				 access_start_at,access_end_at,approved_at,version
				) values (:id,:briefId,:snapshotId,:appointmentId,:userId,:specialistId,
				 'APPOINTMENT_PREPARATION','ACTIVE',:accessStart,:accessEnd,:now,0)
				""").param("id", grantId).param("briefId", brief.id()).param("snapshotId", snapshotId)
				.param("appointmentId", context.appointmentId()).param("userId", userId)
				.param("specialistId", context.specialistAccountId())
				.param("accessStart", Timestamp.from(context.scheduledStartAt().minus(24, ChronoUnit.HOURS)))
				.param("accessEnd", Timestamp.from(context.scheduledStartAt().plus(24, ChronoUnit.HOURS)))
				.param("now", Timestamp.from(now)).update();
		jdbc.sql("""
				update consultation_brief set status='APPROVED',specialist_id=:specialistId,
				 appointment_start_at=:startAt,appointment_end_at=:endAt,appointment_version=:appointmentVersion,
				 version=:nextVersion,updated_at=:now where id=:id and version=:version
				""").param("specialistId", context.specialistAccountId())
				.param("startAt", Timestamp.from(context.scheduledStartAt()))
				.param("endAt", Timestamp.from(context.scheduledEndAt()))
				.param("appointmentVersion", context.version()).param("nextVersion", nextVersion)
				.param("now", Timestamp.from(now)).param("id", brief.id()).param("version", brief.version()).update();
		audit(context.appointmentId(), grantId, userId, "USER", "APPROVED", "ALLOWED",
				"EXACT_SNAPSHOT_APPROVED", correlationId, now);
		return view(brief.id(), screening);
	}

	private BriefView revoke(AppointmentContext context, UUID userId, UUID correlationId, long expectedVersion) {
		var brief = requiredBriefForUpdate(context.appointmentId(), userId);
		requireVersion(brief.version(), expectedVersion);
		var grant = activeGrant(brief.id());
		if (grant == null) throw new ApiException(HttpStatus.CONFLICT, "CONSULTATION_BRIEF_ACCESS_NOT_ACTIVE",
				"Specialist access is not active");
		var now = clock.instant();
		jdbc.sql("update consultation_brief_grant set status='REVOKED',revoked_at=:now,version=version+1 where id=:id")
				.param("now", Timestamp.from(now)).param("id", grant.id()).update();
		jdbc.sql("update consultation_brief set version=version+1,updated_at=:now where id=:id and version=:version")
				.param("now", Timestamp.from(now)).param("id", brief.id()).param("version", brief.version()).update();
		audit(context.appointmentId(), grant.id(), userId, "USER", "REVOKED", "ALLOWED", "USER_REVOKED",
				correlationId, now);
		return view(brief.id(), screening(userId, brief.supportEvaluationId()));
	}

	private void delete(AppointmentContext context, UUID userId, UUID correlationId, long expectedVersion) {
		var brief = requiredBriefForUpdate(context.appointmentId(), userId);
		requireVersion(brief.version(), expectedVersion);
		var now = clock.instant();
		var grant = activeGrant(brief.id());
		if (grant != null) {
			jdbc.sql("update consultation_brief_grant set status='REVOKED',revoked_at=:now,version=version+1 where id=:id")
					.param("now", Timestamp.from(now)).param("id", grant.id()).update();
		}
		jdbc.sql("""
				update consultation_brief set status='DELETED',current_situation=null,support_evaluation_id=null,
				 user_goals=null,deleted_at=:now,updated_at=:now,version=version+1
				where id=:id and version=:version
				""").param("now", Timestamp.from(now)).param("id", brief.id()).param("version", brief.version()).update();
		jdbc.sql("""
				update consultation_brief_snapshot set current_situation=null,support_evaluation_id=null,
				 screening_context=null,user_goals=null,deleted_at=:now
				where brief_id=:briefId and deleted_at is null
				""").param("now", Timestamp.from(now)).param("briefId", brief.id()).update();
		audit(context.appointmentId(), grant == null ? null : grant.id(), userId, "USER", "DELETED", "ALLOWED",
				"USER_DELETED", correlationId, now);
	}

	private ReadDecision specialistDecision(AppointmentContext context, UUID specialistId, UUID correlationId) {
		var now = clock.instant();
		if (!List.of("CONFIRMED", "IN_PROGRESS").contains(context.status())) {
			audit(context.appointmentId(), null, specialistId, "SPECIALIST", "READ", "DENIED",
					"APPOINTMENT_NOT_ACTIVE", correlationId, now);
			return denied(HttpStatus.FORBIDDEN, "CONSULTATION_BRIEF_APPOINTMENT_NOT_ACTIVE",
					"The appointment is not active");
		}
		var grant = grantForAppointmentForUpdate(context.appointmentId(), specialistId);
		if (grant == null || !grant.status().equals("ACTIVE")) {
			audit(context.appointmentId(), grant == null ? null : grant.id(), specialistId, "SPECIALIST", "READ",
					"DENIED", "ACCESS_REVOKED_OR_MISSING", correlationId, now);
			return denied(HttpStatus.FORBIDDEN, "CONSULTATION_BRIEF_ACCESS_DENIED",
					"Consultation brief access is unavailable");
		}
		if (!matchesApprovedAppointment(grant.id(), context)) {
			audit(context.appointmentId(), grant.id(), specialistId, "SPECIALIST", "READ", "DENIED",
					"APPOINTMENT_CHANGED", correlationId, now);
			return denied(HttpStatus.FORBIDDEN, "CONSULTATION_BRIEF_APPOINTMENT_CHANGED",
					"The appointment changed after this consultation brief was approved");
		}
		if (now.isBefore(grant.accessStartAt())) {
			audit(context.appointmentId(), grant.id(), specialistId, "SPECIALIST", "READ", "DENIED",
					"ACCESS_WINDOW_NOT_STARTED", correlationId, now);
			return denied(HttpStatus.FORBIDDEN, "CONSULTATION_BRIEF_ACCESS_TOO_EARLY",
					"Consultation brief access has not started");
		}
		if (now.isAfter(grant.accessEndAt())) {
			audit(context.appointmentId(), grant.id(), specialistId, "SPECIALIST", "READ", "DENIED",
					"ACCESS_WINDOW_EXPIRED", correlationId, now);
			return denied(HttpStatus.FORBIDDEN, "CONSULTATION_BRIEF_ACCESS_EXPIRED",
					"Consultation brief access has expired");
		}
		var view = snapshot(grant.snapshotId());
		if (view == null) {
			audit(context.appointmentId(), grant.id(), specialistId, "SPECIALIST", "READ", "DENIED",
					"SNAPSHOT_OR_SOURCE_MISSING", correlationId, now);
			return denied(HttpStatus.GONE, "CONSULTATION_BRIEF_UNAVAILABLE",
					"The approved consultation brief is unavailable");
		}
		audit(context.appointmentId(), grant.id(), specialistId, "SPECIALIST", "READ", "ALLOWED",
				"APPROVED_SNAPSHOT_READ", correlationId, now);
		return new ReadDecision(view, null);
	}

	private BriefView ownView(UUID appointmentId, UUID userId) {
		var brief = jdbc.sql("""
				select id from consultation_brief where appointment_id=:appointmentId and user_id=:userId and status<>'DELETED'
				""").param("appointmentId", appointmentId).param("userId", userId).query(UUID.class).optional()
				.orElseThrow(this::notFound);
		return view(brief, null);
	}

	private BriefView view(UUID briefId, List<ScreeningContext> knownScreening) {
		var brief = jdbc.sql("""
				select id,appointment_id,status,current_situation,support_evaluation_id,user_goals::text,
				 version,updated_at from consultation_brief where id=:id and status<>'DELETED'
				""").param("id", briefId).query((rs, row) -> new BriefRow(rs.getObject("id", UUID.class),
					rs.getObject("appointment_id", UUID.class), rs.getString("status"), rs.getString("current_situation"),
					rs.getObject("support_evaluation_id", UUID.class), decode(rs.getString("user_goals"), STRING_LIST),
					rs.getLong("version"), rs.getTimestamp("updated_at").toInstant())).single();
		var grant = latestGrant(briefId);
		var screening = knownScreening == null ? screeningForView(brief, grant) : knownScreening;
		return new BriefView(brief.id(), brief.appointmentId(), brief.status(), brief.currentSituation(),
				brief.supportEvaluationId(), screening, brief.userGoals(), grant == null ? null : grant.snapshotId(),
				grant == null ? "NONE" : grant.status(), grant == null ? null : grant.accessStartAt(),
				grant == null ? null : grant.accessEndAt(), brief.version(), brief.updatedAt());
	}

	private List<ScreeningContext> screeningForView(BriefRow brief, GrantRow grant) {
		if (brief.status().equals("APPROVED") && grant != null) {
			var stored = jdbc.sql("select screening_context::text from consultation_brief_snapshot where id=:id")
					.param("id", grant.snapshotId()).query(String.class).optional();
			if (stored.isPresent()) return decode(stored.get(), SCREENING_LIST);
		}
		return screening(briefUser(brief.id()), brief.supportEvaluationId());
	}

	private SpecialistBriefView snapshot(UUID snapshotId) {
		return jdbc.sql("""
				select s.id,s.appointment_id,s.current_situation,s.support_evaluation_id,
				 s.screening_context::text,s.user_goals::text,s.snapshot_version,s.created_at
				from consultation_brief_snapshot s
				join consultation_brief b on b.id=s.brief_id and b.status<>'DELETED'
				join support_evaluation_v2 e on e.id=s.support_evaluation_id and e.user_id=s.user_id
				where s.id=:id and s.deleted_at is null
				  and (select count(*) from support_evaluation_v2_domain d
				       where d.support_evaluation_id=e.id)=2
				""").param("id", snapshotId).query((rs, row) -> new SpecialistBriefView(
					rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class),
					rs.getString("current_situation"), rs.getObject("support_evaluation_id", UUID.class),
					decode(rs.getString("screening_context"), SCREENING_LIST),
					decode(rs.getString("user_goals"), STRING_LIST), rs.getLong("snapshot_version"),
					rs.getTimestamp("created_at").toInstant())).optional().orElse(null);
	}

	private List<ScreeningContext> screening(UUID userId, UUID evaluationId) {
		var rows = jdbc.sql("""
				select d.instrument,d.domain,d.screening_level,d.questionnaire_version,d.scoring_version,
				 e.evaluated_at,e.policy_version
				from support_evaluation_v2 e
				join support_evaluation_v2_domain d on d.support_evaluation_id=e.id
				where e.id=:evaluationId and e.user_id=:userId order by d.ordinal
				""").param("evaluationId", evaluationId).param("userId", userId)
				.query((rs, row) -> new ScreeningContext(rs.getString("instrument"), rs.getString("domain"),
						rs.getString("screening_level"), rs.getString("questionnaire_version"),
						rs.getString("scoring_version"), rs.getTimestamp("evaluated_at").toInstant(),
						rs.getString("policy_version"))).list();
		if (rows.size() != 2) throw new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_EVALUATION_NOT_FOUND",
				"The selected screening context was not found");
		return rows;
	}

	private BriefRow briefForUpdate(UUID appointmentId) {
		return jdbc.sql("""
				select id,appointment_id,user_id,status,current_situation,support_evaluation_id,user_goals::text,
				 version,updated_at from consultation_brief where appointment_id=:appointmentId for update
				""").param("appointmentId", appointmentId).query((rs, row) -> new BriefRow(
					rs.getObject("id", UUID.class), rs.getObject("appointment_id", UUID.class), rs.getString("status"),
					rs.getString("current_situation"), rs.getObject("support_evaluation_id", UUID.class),
					decode(rs.getString("user_goals"), STRING_LIST), rs.getLong("version"),
					rs.getTimestamp("updated_at").toInstant())).optional().orElse(null);
	}

	private BriefRow requiredBriefForUpdate(UUID appointmentId, UUID userId) {
		var brief = briefForUpdate(appointmentId);
		if (brief == null || !briefUser(brief.id()).equals(userId) || brief.status().equals("DELETED")) throw notFound();
		return brief;
	}

	private UUID briefUser(UUID briefId) {
		return jdbc.sql("select user_id from consultation_brief where id=:id").param("id", briefId)
				.query(UUID.class).single();
	}

	private GrantRow activeGrant(UUID briefId) {
		return jdbc.sql("""
				select id,snapshot_id,status,access_start_at,access_end_at from consultation_brief_grant
				where brief_id=:briefId and status='ACTIVE' for update
				""").param("briefId", briefId).query(this::grant).optional().orElse(null);
	}

	private GrantRow latestGrant(UUID briefId) {
		return jdbc.sql("""
				select id,snapshot_id,status,access_start_at,access_end_at from consultation_brief_grant
				where brief_id=:briefId order by approved_at desc,id desc limit 1
				""").param("briefId", briefId).query(this::grant).optional().orElse(null);
	}

	private GrantRow grantForAppointmentForUpdate(UUID appointmentId, UUID specialistId) {
		return jdbc.sql("""
				select id,snapshot_id,status,access_start_at,access_end_at from consultation_brief_grant
				where appointment_id=:appointmentId and specialist_id=:specialistId
				order by approved_at desc,id desc limit 1 for update
				""").param("appointmentId", appointmentId).param("specialistId", specialistId)
				.query(this::grant).optional().orElse(null);
	}

	private boolean matchesApprovedAppointment(UUID grantId, AppointmentContext context) {
		return jdbc.sql("""
				select exists (
				 select 1 from consultation_brief_grant g
				 join consultation_brief b on b.id=g.brief_id
				 where g.id=:grantId and g.appointment_id=:appointmentId
				   and g.user_id=:userId and g.specialist_id=:specialistId
				   and b.appointment_start_at=:startAt and b.appointment_end_at=:endAt
				)
				""").param("grantId", grantId).param("appointmentId", context.appointmentId())
				.param("userId", context.userAccountId()).param("specialistId", context.specialistAccountId())
				.param("startAt", Timestamp.from(context.scheduledStartAt()))
				.param("endAt", Timestamp.from(context.scheduledEndAt())).query(Boolean.class).single();
	}

	private GrantRow grant(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
		return new GrantRow(rs.getObject("id", UUID.class), rs.getObject("snapshot_id", UUID.class),
				rs.getString("status"), rs.getTimestamp("access_start_at").toInstant(),
				rs.getTimestamp("access_end_at").toInstant());
	}

	private void audit(UUID appointmentId, UUID grantId, UUID actorId, String actorType, String action,
			String outcome, String reason, UUID correlationId, Instant now) {
		jdbc.sql("""
				insert into consultation_brief_audit (
				 id,appointment_id,grant_id,actor_id,actor_type,action,outcome,reason_code,correlation_id,occurred_at
				) values (:id,:appointmentId,:grantId,:actorId,:actorType,:action,:outcome,:reason,:correlationId,:now)
				""").param("id", UUID.randomUUID()).param("appointmentId", appointmentId).param("grantId", grantId)
				.param("actorId", actorId).param("actorType", actorType).param("action", action)
				.param("outcome", outcome).param("reason", reason).param("correlationId", correlationId)
				.param("now", Timestamp.from(now)).update();
	}

	private void requireOwnerConfirmed(AppointmentContext context, UUID userId) {
		if (!context.userAccountId().equals(userId)) throw notFound();
		if (!context.status().equals("CONFIRMED")) throw new ApiException(HttpStatus.CONFLICT,
				"CONSULTATION_BRIEF_APPOINTMENT_NOT_CONFIRMED", "A confirmed appointment is required");
		if (!clock.instant().isBefore(context.scheduledStartAt())) throw new ApiException(HttpStatus.CONFLICT,
				"CONSULTATION_BRIEF_APPOINTMENT_ALREADY_STARTED",
				"The consultation brief must be prepared and approved before the appointment starts");
	}

	private void requireVersion(long actual, Long expected) {
		if (expected == null) throw new ApiException(HttpStatus.PRECONDITION_REQUIRED,
				"CONSULTATION_BRIEF_VERSION_REQUIRED", "If-Match must contain the current consultation brief version");
		if (actual != expected) throw versionMismatch();
	}

	private ApiException versionMismatch() {
		return new ApiException(HttpStatus.PRECONDITION_FAILED, "CONSULTATION_BRIEF_VERSION_MISMATCH",
				"The consultation brief changed; reload before retrying");
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CONSULTATION_BRIEF_NOT_FOUND",
				"The consultation brief was not found");
	}

	private ReadDecision denied(HttpStatus status, String code, String message) {
		return new ReadDecision(null, new ApiException(status, code, message));
	}

	private String encode(Object value) {
		try { return json.writeValueAsString(value); }
		catch (JsonProcessingException exception) { throw new IllegalStateException("Could not encode consultation brief", exception); }
	}

	private <T> T decode(String value, TypeReference<T> type) {
		try { return json.readValue(value, type); }
		catch (JsonProcessingException exception) { throw new IllegalStateException("Stored consultation brief is invalid", exception); }
	}

	public record SaveDraftCommand(String currentSituation, UUID supportEvaluationId, List<String> userGoals) { }
	public record ScreeningContext(String instrument, String domain, String screeningLevel,
			String questionnaireVersion, String scoringVersion, Instant evaluatedAt, String policyVersion) { }
	public record BriefView(UUID id, UUID appointmentId, String status, String currentSituation,
			UUID supportEvaluationId, List<ScreeningContext> screeningContext, List<String> userGoals,
			UUID approvedSnapshotId, String sharingStatus, Instant accessStartAt, Instant accessEndAt,
			long version, Instant updatedAt) { }
	public record SpecialistBriefView(UUID snapshotId, UUID appointmentId, String currentSituation,
			UUID supportEvaluationId, List<ScreeningContext> screeningContext, List<String> userGoals,
			long snapshotVersion, Instant approvedAt) { }
	public record ScreeningContextChoice(UUID supportEvaluationId, Instant evaluatedAt,
			List<ScreeningContext> screeningContext) { }
	public record ScreeningContextList(List<ScreeningContextChoice> items, int count) { }

	private record BriefRow(UUID id, UUID appointmentId, String status, String currentSituation,
			UUID supportEvaluationId, List<String> userGoals, long version, Instant updatedAt) { }
	private record GrantRow(UUID id, UUID snapshotId, String status, Instant accessStartAt, Instant accessEndAt) { }
	private record ReadDecision(SpecialistBriefView view, ApiException error) { }
	private record EvaluationReference(UUID id, Instant evaluatedAt) { }
}
