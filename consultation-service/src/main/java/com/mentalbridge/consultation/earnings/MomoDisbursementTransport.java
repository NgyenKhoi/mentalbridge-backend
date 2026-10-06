package com.mentalbridge.consultation.earnings;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class MomoDisbursementTransport {

	private final RestClient.Builder http;

	MomoDisbursementTransport(RestClient.Builder http) {
		this.http = http;
	}

	MomoPayoutProvider.MomoResponse send(String baseUrl, MomoPayoutProvider.MomoRequest request) {
		return http.baseUrl(baseUrl).build().post().uri("/v2/gateway/api/disbursement/pay")
				.body(request).retrieve().body(MomoPayoutProvider.MomoResponse.class);
	}
}
