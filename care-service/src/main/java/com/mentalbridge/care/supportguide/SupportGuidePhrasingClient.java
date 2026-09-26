package com.mentalbridge.care.supportguide;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.configuration.SupportGuidePhrasingClientProperties;

import feign.FeignException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;

@Component
public class SupportGuidePhrasingClient {

	private static final Logger LOGGER = LoggerFactory.getLogger(SupportGuidePhrasingClient.class);
	private static final Set<String> PROVIDERS = Set.of("DETERMINISTIC_FAKE", "GEMINI", "OPENAI");

	private final JournalSupportGuidePhrasingHttpClient httpClient;
	private final CircuitBreaker circuitBreaker;

	public SupportGuidePhrasingClient(JournalSupportGuidePhrasingHttpClient httpClient,
			SupportGuidePhrasingClientProperties properties) {
		this.httpClient = httpClient;
		this.circuitBreaker = CircuitBreaker.of("journalSupportGuidePhrasing", CircuitBreakerConfig.custom()
				.slidingWindowSize(properties.circuitWindowSize())
				.minimumNumberOfCalls(properties.circuitMinimumCalls())
				.failureRateThreshold(properties.circuitFailureRate())
				.waitDurationInOpenState(properties.circuitOpenDuration())
				.permittedNumberOfCallsInHalfOpenState(properties.circuitHalfOpenCalls())
				.recordException(SupportGuidePhrasingClient::countsForCircuit)
				.build());
	}

	public Phrasing phrase(String approvedText, String bearerToken, UUID correlationId) {
		if (approvedText == null || approvedText.isBlank() || approvedText.length() > 1600
				|| bearerToken == null || bearerToken.isBlank() || correlationId == null) {
			throw new IllegalArgumentException("Approved phrasing context is required");
		}
		try {
			Supplier<PhrasingResponse> remote = CircuitBreaker.decorateSupplier(circuitBreaker,
					() -> {
						var response = httpClient.phrase("Bearer " + bearerToken, correlationId.toString(),
								new PhrasingRequest(approvedText, "vi-VN"));
						if (!valid(response)) throw new InvalidPhrasingResponseException();
						return response;
					});
			var response = remote.get();
			return new Phrasing(response.text(), "STANDARD");
		}
		catch (RuntimeException exception) {
			LOGGER.warn("support_guide_ai_phrasing_failed correlationId={} category={} httpStatus={}",
					correlationId, failureCategory(exception), httpStatus(exception));
			return new Phrasing(approvedText, "AI_UNAVAILABLE_FALLBACK");
		}
	}

	CircuitBreaker.State circuitState() {
		return circuitBreaker.getState();
	}

	private static boolean countsForCircuit(Throwable error) {
		if (error instanceof InvalidPhrasingResponseException) return true;
		if (error instanceof FeignException exception) return exception.status() < 0 || exception.status() >= 500;
		return true;
	}

	private boolean valid(PhrasingResponse response) {
		return response != null && response.text() != null && !response.text().isBlank()
				&& response.text().length() <= 1600 && PROVIDERS.contains(response.provider())
				&& response.model() != null && !response.model().isBlank() && response.model().length() <= 128
				&& "support-guide-phrasing-v1".equals(response.promptVersion())
				&& Integer.valueOf(1).equals(response.schemaVersion());
	}

	static String failureCategory(Throwable error) {
		if (error instanceof FeignException exception) {
			if (exception.status() == 401) return "AUTHENTICATION_REJECTED";
			if (exception.status() == 403) return "CONSENT_REJECTED";
			if (exception.status() >= 400 && exception.status() < 500) return "CLIENT_REQUEST_REJECTED";
		}
		if (error instanceof InvalidPhrasingResponseException) return "INVALID_PROVIDER_RESPONSE";
		return "DEPENDENCY_UNAVAILABLE";
	}

	private static Integer httpStatus(Throwable error) {
		return error instanceof FeignException exception ? exception.status() : null;
	}

	public record PhrasingRequest(String approvedText, String locale) { }
	public record PhrasingResponse(String text, String provider, String model, String promptVersion,
			Integer schemaVersion) { }
	public record Phrasing(String text, String status) { }

	private static final class InvalidPhrasingResponseException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
