package com.mentalbridge.care.consultationbrief;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.consultationbrief.ConsultationBriefAiDraftClient.DraftProviderRequest;
import com.mentalbridge.care.consultationbrief.ConsultationBriefService.AiDraftSource;

import feign.FeignException;

@Component
public class ConsultationBriefAiDraftWorker {

	private final ConsultationBriefAiDraftClient client;
	private final JdbcClient jdbc;
	private final TransactionTemplate transactions;
	private final ObjectMapper json;
	private final Clock clock;

	public ConsultationBriefAiDraftWorker(ConsultationBriefAiDraftClient client, JdbcClient jdbc,
			TransactionTemplate transactions, ObjectMapper json, Clock clock) {
		this.client = client;
		this.jdbc = jdbc;
		this.transactions = transactions;
		this.json = json;
		this.clock = clock;
	}

	@Async
	public void dispatch(UUID jobId, AiDraftSource source, String bearerToken, UUID correlationId) {
		var request = new DraftProviderRequest(source.appointmentId(), source.briefId(), source.briefVersion(),
				source.supportEvaluationId(), source.currentSituation(), source.userGoals(), source.screeningContext(),
				ConsultationBriefAiDraftService.SOURCE_SET_VERSION);
		for (int attempt = 1; attempt <= 2; attempt++) {
			markAttempt(jobId, attempt);
			try {
				var result = client.draft(request, bearerToken, correlationId);
				complete(jobId, source, result);
				return;
			}
			catch (RuntimeException exception) {
				if (attempt < 2 && retryable(exception)) continue;
				fail(jobId, reason(exception));
				return;
			}
		}
	}

	private void markAttempt(UUID jobId, int attempt) {
		jdbc.sql("update consultation_brief_ai_draft_job set attempt_count=:attempt,updated_at=:now where id=:id and status='RUNNING'")
				.param("attempt", attempt).param("now", Timestamp.from(clock.instant())).param("id", jobId).update();
	}

	private void complete(UUID jobId, AiDraftSource source,
			ConsultationBriefAiDraftClient.DraftProviderResponse result) {
		transactions.executeWithoutResult(status -> {
			var current = jdbc.sql("select status,version from consultation_brief where id=:id for update")
					.param("id", source.briefId()).query((rs, row) -> new SourceState(rs.getString("status"),
						rs.getLong("version"))).optional();
			if (current.isEmpty() || !current.orElseThrow().status().equals("DRAFT")
					|| current.orElseThrow().version() != source.briefVersion()) {
				fail(jobId, current.isPresent() && current.orElseThrow().status().equals("DELETED")
						? "SOURCE_DELETED" : "SOURCE_CHANGED");
				return;
			}
			var now = clock.instant();
			jdbc.sql("""
					update consultation_brief_ai_draft_job set status='SUCCEEDED',terminal_reason=null,
					 suggested_current_situation=:situation,suggested_user_goals=cast(:goals as jsonb),
					 consent_policy_version=:consentPolicy,service_plan=:servicePlan,
					 entitlement_source=:entitlementSource,entitlement_policy_version=:entitlementPolicy,
					 entitlement_version=:entitlementVersion,routing_policy_version=:routingPolicy,
					 provider_approval_version=:providerApproval,provider=:provider,model=:model,
					 prompt_version=:promptVersion,schema_version=:schemaVersion,completed_at=:now,updated_at=:now
					where id=:id and status='RUNNING'
					""").param("situation", result.currentSituation()).param("goals", encode(result.userGoals()))
					.param("consentPolicy", result.consentPolicyVersion()).param("servicePlan", result.servicePlan())
					.param("entitlementSource", result.entitlementSource())
					.param("entitlementPolicy", result.entitlementPolicyVersion())
					.param("entitlementVersion", result.entitlementVersion())
					.param("routingPolicy", result.routingPolicyVersion())
					.param("providerApproval", result.providerApprovalVersion()).param("provider", result.provider())
					.param("model", result.model()).param("promptVersion", result.promptVersion())
					.param("schemaVersion", result.schemaVersion()).param("now", Timestamp.from(now))
					.param("id", jobId).update();
		});
	}

	private void fail(UUID jobId, String reason) {
		var now = clock.instant();
		jdbc.sql("""
				update consultation_brief_ai_draft_job set status='FAILED',terminal_reason=:reason,
				 suggested_current_situation=null,suggested_user_goals=null,completed_at=:now,updated_at=:now
				where id=:id and status='RUNNING'
				""").param("reason", reason).param("now", Timestamp.from(now)).param("id", jobId).update();
	}

	private boolean retryable(RuntimeException error) {
		return !(error instanceof FeignException exception) || exception.status() < 0 || exception.status() >= 500;
	}

	private String reason(RuntimeException error) {
		if (error instanceof ConsultationBriefAiDraftClient.InvalidDraftProviderResponseException) {
			return "INVALID_PROVIDER_RESULT";
		}
		if (error instanceof FeignException exception) {
			if (exception.status() == 403) return "CONSENT_REQUIRED";
			if (exception.status() == 401) return "AUTHORIZATION_REJECTED";
		}
		return "PROVIDER_UNAVAILABLE";
	}

	private String encode(Object value) {
		try { return json.writeValueAsString(value); }
		catch (JsonProcessingException exception) { throw new IllegalStateException("Could not encode AI draft", exception); }
	}

	private record SourceState(String status, long version) { }
}
