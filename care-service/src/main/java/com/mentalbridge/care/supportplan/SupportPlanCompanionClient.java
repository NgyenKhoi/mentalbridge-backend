package com.mentalbridge.care.supportplan;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.configuration.SupportPlanCompanionClientProperties;

import feign.FeignException;
import feign.RetryableException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

@Component
public class SupportPlanCompanionClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(SupportPlanCompanionClient.class);
	private static final Set<String> PROVIDERS = Set.of("DETERMINISTIC_FAKE", "GEMINI", "OPENAI", "BEDROCK");
	private static final Set<String> FALLBACK_REASONS = Set.of("CONSENT_REQUIRED", "CONSENT_UNAVAILABLE",
			"PROVIDER_DISABLED", "PROVIDER_TIMEOUT", "PROVIDER_QUOTA_LIMITED", "PROVIDER_UNAVAILABLE",
			"MALFORMED_OUTPUT", "POLICY_REJECTED");

	private final JournalSupportPlanCompanionHttpClient httpClient;
	private final SupportPlanCompanionClientProperties properties;
	private final CircuitBreaker circuitBreaker;

	public SupportPlanCompanionClient(JournalSupportPlanCompanionHttpClient httpClient,
			SupportPlanCompanionClientProperties properties) {
		this.httpClient = httpClient;
		this.properties = properties;
		this.circuitBreaker = CircuitBreaker.of("journalSupportPlanCompanion", CircuitBreakerConfig.custom()
				.slidingWindowSize(properties.circuitWindowSize())
				.minimumNumberOfCalls(properties.circuitMinimumCalls())
				.failureRateThreshold(properties.circuitFailureRate())
				.waitDurationInOpenState(properties.circuitOpenDuration())
				.permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
				.recordException(SupportPlanCompanionClient::countsForCircuit)
				.build());
	}

	public CompanionResponse message(CompanionRequest request, String bearerToken, UUID correlationId) {
		if (!properties.enabled()) return fallback(request, "PROVIDER_DISABLED");
		try {
			Supplier<CompanionResponse> remote = CircuitBreaker.decorateSupplier(circuitBreaker,
					() -> httpClient.message("Bearer " + bearerToken, correlationId.toString(),
							properties.serviceToken(), request));
			var response = remote.get();
			if (!valid(response, request)) throw new InvalidCompanionResponseException();
			return response;
		}
		catch (RuntimeException exception) {
			String reason = fallbackReason(exception);
			LOGGER.warn("support_plan_companion_failed correlationId={} intent={} surface={} category={} httpStatus={}",
					correlationId, request.intent(), request.surface(), reason, httpStatus(exception));
			return fallback(request, reason);
		}
	}

	private static boolean countsForCircuit(Throwable error) {
		if (error instanceof InvalidCompanionResponseException) return true;
		if (error instanceof FeignException exception) return exception.status() < 0 || exception.status() >= 500;
		return true;
	}

	private boolean valid(CompanionResponse response, CompanionRequest request) {
		if (response == null || !"support-plan-companion-message-v1".equals(response.schemaVersion())
				|| !request.intent().equals(response.intent()) || !request.surface().equals(response.surface())
				|| response.text() == null || response.text().isBlank() || response.text().length() > 480
				|| !Set.of("GENERATED", "TEMPLATE").contains(response.delivery()) || response.navigation() != null
				|| !"support-plan-companion-policy-v1".equals(response.policyVersion())
				|| !"support-plan-companion-prompt-v1".equals(response.promptVersion())
				|| !"support-plan-companion-template-v1".equals(response.templateVersion())) return false;
		if (response.provider() != null && !PROVIDERS.contains(response.provider())) return false;
		if (response.model() != null && (response.model().isBlank() || response.model().length() > 2048)) return false;
		return "GENERATED".equals(response.delivery())
				? response.fallbackReason() == null && response.provider() != null && response.model() != null
				: response.fallbackReason() != null && FALLBACK_REASONS.contains(response.fallbackReason());
	}

	private CompanionResponse fallback(CompanionRequest request, String reason) {
		String title = request.context().action().title();
		String text;
		if (!"AVAILABLE".equals(request.context().action().availability())) {
			text = "Thông tin cho “" + title + "” hiện chưa sẵn sàng. Bạn có thể thử lại sau.";
		}
		else {
			String primary = title + " là một hoạt động trong kế hoạch của bạn.";
			String rationale = request.context().action().rationale();
			String combined = rationale == null ? primary : primary + " " + rationale;
			text = combined.length() <= 480 ? combined : primary;
		}
		return new CompanionResponse("support-plan-companion-message-v1", request.intent(), request.surface(), text,
				"TEMPLATE", null, "support-plan-companion-policy-v1", "support-plan-companion-prompt-v1",
				"support-plan-companion-template-v1", null, null, reason);
	}

	private static String fallbackReason(Throwable error) {
		if (error instanceof RetryableException) return "PROVIDER_TIMEOUT";
		if (error instanceof FeignException exception && exception.status() == 429) return "PROVIDER_QUOTA_LIMITED";
		if (error instanceof InvalidCompanionResponseException) return "MALFORMED_OUTPUT";
		return "PROVIDER_UNAVAILABLE";
	}

	private static Integer httpStatus(Throwable error) {
		return error instanceof FeignException exception ? exception.status() : null;
	}

	public record CompanionRequest(String schemaVersion, String policyVersion, String requestSource, String surface,
			String intent, CompanionContext context) { }
	public record CompanionContext(String schemaVersion, String locale, String contextKind, ActionContext action) { }
	public record ActionContext(String actionType, String title, String rationale, String availability) { }
	public record CompanionResponse(String schemaVersion, String intent, String surface, String text, String delivery,
			Object navigation, String policyVersion, String promptVersion, String templateVersion,
			String provider, String model, String fallbackReason) { }

	private static final class InvalidCompanionResponseException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
