package com.mentalbridge.identity.productjourney;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("mentalbridge.identity.product-journey")
public record ProductJourneyProperties(URI careBaseUrl, URI consultationBaseUrl,
		Duration connectTimeout, Duration readTimeout) {

	public ProductJourneyProperties {
		if (careBaseUrl == null) careBaseUrl = URI.create("http://localhost:8081");
		if (consultationBaseUrl == null) consultationBaseUrl = URI.create("http://localhost:8082");
		if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()) connectTimeout = Duration.ofSeconds(1);
		if (readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) readTimeout = Duration.ofSeconds(2);
	}
}
