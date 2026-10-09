package com.mentalbridge.identity.productjourney;

import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class ProductJourneySourceClient {

	private final RestClient care;
	private final RestClient consultation;

	ProductJourneySourceClient(ProductJourneyProperties properties) {
		this.care = client(properties.careBaseUrl().toString(), properties);
		this.consultation = client(properties.consultationBaseUrl().toString(), properties);
	}

	CareMetrics care(Instant from, Instant to, String bearerToken) {
		return care.get().uri(builder -> builder.path("/api/v1/admin/product-journey-metrics")
				.queryParam("from", from).queryParam("to", to).build())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).retrieve().body(CareMetrics.class);
	}

	ConsultationMetrics consultation(Instant from, Instant to, String bearerToken) {
		return consultation.get().uri(builder -> builder.path("/api/v1/admin/product-journey-metrics")
				.queryParam("from", from).queryParam("to", to).build())
				.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken).retrieve().body(ConsultationMetrics.class);
	}

	private RestClient client(String baseUrl, ProductJourneyProperties properties) {
		var requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(properties.connectTimeout());
		requestFactory.setReadTimeout(properties.readTimeout());
		return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
	}

	record CareMetrics(String source, String sourceVersion, Instant asOf, long completedScreeningEpisodes,
			long supportGuidesGenerated, long paidSupportPlansActivated) { }
	record ConsultationMetrics(String source, String sourceVersion, Instant asOf, long consultationsRequested,
			long consultationsConfirmed, long consultationsCompleted) { }
}
