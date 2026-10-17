package com.mentalbridge.care.entitlement;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.EntitlementSource;
import com.mentalbridge.care.entitlement.CurrentEntitlementResponse.ServicePackage;
import com.mentalbridge.care.shared.ApiException;

@Component
public class EntitlementClient {

	private static final String POLICY_VERSION = "service-entitlement-v1";

	private final ConsultationEntitlementHttpClient httpClient;

	public EntitlementClient(ConsultationEntitlementHttpClient httpClient) {
		this.httpClient = httpClient;
	}

	public CurrentEntitlementResponse current(UUID userId, String bearerToken, UUID correlationId) {
		if (bearerToken == null || bearerToken.isBlank() || correlationId == null) {
			throw new IllegalArgumentException("Authenticated entitlement request context is required");
		}
		try {
			var response = httpClient.current("Bearer " + bearerToken, correlationId.toString());
			if (!valid(userId, response)) {
				throw unavailable();
			}
			return response;
		}
		catch (ApiException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private boolean valid(UUID userId, CurrentEntitlementResponse response) {
		if (response == null || !userId.equals(response.accountId()) || response.packageCode() == null
				|| response.source() == null || !POLICY_VERSION.equals(response.policyVersion())
				|| response.version() < 0 || response.decidedAt() == null) {
			return false;
		}
		if (response.packageCode() == ServicePackage.FREE) {
			return response.source() == EntitlementSource.DEFAULT_FREE && response.sourceReference() == null
					&& response.effectiveFrom() == null && response.effectiveUntil() == null;
		}
		return response.source() != EntitlementSource.DEFAULT_FREE && response.sourceReference() != null
				&& !response.sourceReference().isBlank() && response.effectiveFrom() != null
				&& response.effectiveUntil() != null && response.effectiveUntil().isAfter(response.decidedAt());
	}

	private ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ENTITLEMENT_UNAVAILABLE",
				"Current service entitlement could not be verified");
	}
}
