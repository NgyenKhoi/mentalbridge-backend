package com.mentalbridge.care.consultationbrief;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.consent.ConsentService;
import com.mentalbridge.care.consultationbrief.ConsultationBriefService.AiDraftSource;
import com.mentalbridge.care.shared.ApiException;

@Service
public class ConsultationBriefAiDraftService {

	public static final String SOURCE_SET_VERSION = "consultation-brief-ai-source-v1";
	private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
	private final ConsultationBriefService briefs;
	private final ConsentService consents;
	private final ConsultationBriefAiDraftWorker worker;
	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;
	private final ObjectMapper json;
	private final Clock clock;

	public ConsultationBriefAiDraftService(ConsultationBriefService briefs, ConsentService consents,
			ConsultationBriefAiDraftWorker worker, JdbcClient jdbc, TransactionTemplate transactions,
			ObjectMapper json, Clock clock) {
		this.briefs = briefs;
		this.consents = consents;
		this.worker = worker;
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.json = json;
		this.clock = clock;
	}

	public JobView create(UUID userId, String bearerToken, UUID correlationId, UUID appointmentId,
			long expectedVersion, String idempotencyKey) {
		if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID",
					"Idempotency-Key must contain 8 to 128 safe characters");
		}
		var authorization = consents.authorizeAiProcessing(userId);
		if (!authorization.authorized()) {
			throw new ApiException(HttpStatus.CONFLICT, "AI_PROCESSING_CONSENT_REQUIRED",
					"Current AI processing consent is required");
		}
		var source = briefs.aiDraftSource(userId, bearerToken, correlationId, appointmentId, expectedVersion);
		var fingerprint = fingerprint(source);
		var existing = findByKey(userId, idempotencyKey);
		if (existing != null) {
			if (!existing.requestFingerprint().equals(fingerprint)) {
				throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
						"Idempotency-Key was reused for a different consultation brief version");
			}
			return view(existing.id());
		}
		var jobId = UUID.randomUUID();
		var now = clock.instant();
		transactions.executeWithoutResult(status -> jdbc.sql("""
				insert into consultation_brief_ai_draft_job (
				 id,appointment_id,brief_id,user_id,brief_version,support_evaluation_id,idempotency_key,
				 request_fingerprint,source_set_version,status,attempt_count,created_at,updated_at
				) values (:id,:appointmentId,:briefId,:userId,:briefVersion,:evaluationId,:key,
				 :fingerprint,:sourceSet,'RUNNING',0,:now,:now)
				""").param("id", jobId).param("appointmentId", appointmentId).param("briefId", source.briefId())
				.param("userId", userId).param("briefVersion", source.briefVersion())
				.param("evaluationId", source.supportEvaluationId()).param("key", idempotencyKey)
				.param("fingerprint", fingerprint).param("sourceSet", SOURCE_SET_VERSION)
				.param("now", Timestamp.from(now)).update());
		worker.dispatch(jobId, source, bearerToken, correlationId);
		return view(jobId);
	}

	public JobView get(UUID userId, UUID appointmentId, UUID jobId) {
		var owner = jdbc.sql("select user_id from consultation_brief_ai_draft_job where id=:id and appointment_id=:appointmentId")
				.param("id", jobId).param("appointmentId", appointmentId).query(UUID.class).optional();
		if (owner.isEmpty() || !owner.orElseThrow().equals(userId)) throw notFound();
		return view(jobId);
	}

	private JobView view(UUID id) {
		return jdbc.sql("""
				select id,appointment_id,brief_id,brief_version,support_evaluation_id,source_set_version,status,
				 attempt_count,terminal_reason,suggested_current_situation,suggested_user_goals::text,
				 consent_policy_version,service_plan,entitlement_source,entitlement_policy_version,
				 entitlement_version,routing_policy_version,provider_approval_version,provider,model,
				 prompt_version,schema_version,created_at,updated_at,completed_at
				from consultation_brief_ai_draft_job where id=:id
				""").param("id", id).query((rs, row) -> new JobView(rs.getObject("id", UUID.class),
				rs.getObject("appointment_id", UUID.class), rs.getObject("brief_id", UUID.class),
				rs.getLong("brief_version"), rs.getObject("support_evaluation_id", UUID.class),
				rs.getString("source_set_version"), rs.getString("status"), rs.getInt("attempt_count"),
				rs.getString("terminal_reason"), rs.getString("suggested_current_situation"),
				rs.getString("suggested_user_goals") == null ? null : decode(rs.getString("suggested_user_goals")),
				rs.getString("consent_policy_version"), rs.getString("service_plan"),
				rs.getString("entitlement_source"), rs.getString("entitlement_policy_version"),
				(Long) rs.getObject("entitlement_version"), rs.getString("routing_policy_version"),
				rs.getString("provider_approval_version"), rs.getString("provider"), rs.getString("model"),
				rs.getString("prompt_version"), (Integer) rs.getObject("schema_version"),
				rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
				rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant())).single();
	}

	private JobRow findByKey(UUID userId, String key) {
		return jdbc.sql("select id,request_fingerprint from consultation_brief_ai_draft_job where user_id=:userId and idempotency_key=:key")
				.param("userId", userId).param("key", key)
				.query((rs, row) -> new JobRow(rs.getObject("id", UUID.class), rs.getString("request_fingerprint")))
				.optional().orElse(null);
	}

	private String fingerprint(AiDraftSource source) {
		try {
			var value = source.appointmentId() + "|" + source.briefId() + "|" + source.briefVersion()
					+ "|" + source.supportEvaluationId() + "|" + SOURCE_SET_VERSION;
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private List<String> decode(String value) {
		try { return json.readValue(value, STRING_LIST); }
		catch (JsonProcessingException exception) { throw new IllegalStateException("Stored AI draft goals are invalid", exception); }
	}

	private ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "CONSULTATION_BRIEF_AI_DRAFT_NOT_FOUND",
				"The AI consultation brief draft job was not found");
	}

	public record JobView(UUID jobId, UUID appointmentId, UUID consultationBriefId, long consultationBriefVersion,
			UUID supportEvaluationId, String sourceSetVersion, String status, int attemptCount, String terminalReason,
			String currentSituation, List<String> userGoals, String consentPolicyVersion, String servicePlan,
			String entitlementSource, String entitlementPolicyVersion, Long entitlementVersion,
			String routingPolicyVersion, String providerApprovalVersion, String provider, String model,
			String promptVersion, Integer schemaVersion, Instant createdAt, Instant updatedAt, Instant completedAt) { }
	private record JobRow(UUID id, String requestFingerprint) { }
}
