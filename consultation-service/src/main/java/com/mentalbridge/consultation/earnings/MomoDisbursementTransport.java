package com.mentalbridge.consultation.earnings;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
class MomoDisbursementTransport {

	MomoPayoutProvider.MomoResponse send(String baseUrl, MomoPayoutProvider.MomoRequest request) {
		return RestClient.create(baseUrl).post().uri("/v2/gateway/api/disbursement/pay")
				.body(request).retrieve().body(MomoPayoutProvider.MomoResponse.class);
	}
}
