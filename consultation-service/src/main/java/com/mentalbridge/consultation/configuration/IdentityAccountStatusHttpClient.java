package com.mentalbridge.consultation.configuration;

import java.util.UUID;

import feign.Logger;
import feign.Request;
import feign.Retryer;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;

@FeignClient(name = "identity-service", contextId = "consultationIdentityAccountStatus",
		url = "${mentalbridge.consultation.identity-account-status.base-url:}",
		configuration = IdentityAccountStatusHttpClient.Configuration.class)
public interface IdentityAccountStatusHttpClient {
	@GetMapping("/internal/v1/accounts/{accountId}/authorization")
	Result authorize(@PathVariable UUID accountId, @RequestHeader("X-MentalBridge-Service-Id") String serviceId,
			@RequestHeader("X-MentalBridge-Service-Token") String serviceToken,
			@RequestHeader("X-Correlation-Id") String correlationId);

	record Result(Boolean active) { }

	class Configuration {
		@Bean Request.Options identityAccountStatusDeadlines(IdentityAccountStatusProperties properties) {
			return new Request.Options(properties.connectTimeout(), properties.readTimeout(), false);
		}
		@Bean Retryer identityAccountStatusRetryer() { return Retryer.NEVER_RETRY; }
		@Bean Logger.Level identityAccountStatusLogLevel() { return Logger.Level.NONE; }
	}
}
