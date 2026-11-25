package com.mentalbridge.consultation.billing;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MomoPaymentIpnController {

	private final MomoPaymentIpnService ipn;

	public MomoPaymentIpnController(MomoPaymentIpnService ipn) {
		this.ipn = ipn;
	}

	@PostMapping("/internal/v1/payments/momo/ipn")
	ResponseEntity<Void> receive(@RequestBody Map<String, Object> payload) {
		ipn.receive(payload);
		return ResponseEntity.noContent().build();
	}
}
