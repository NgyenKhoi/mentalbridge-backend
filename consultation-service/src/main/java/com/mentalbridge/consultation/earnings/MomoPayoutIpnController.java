package com.mentalbridge.consultation.earnings;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

@RestController
public class MomoPayoutIpnController {

	private final MomoPayoutIpnService ipn;

	public MomoPayoutIpnController(MomoPayoutIpnService ipn) {
		this.ipn = ipn;
	}

	@PostMapping("/internal/v1/payouts/momo/ipn")
	ResponseEntity<Void> receive(@Valid @RequestBody MomoPayoutIpnRequest request) {
		ipn.receive(request);
		return ResponseEntity.noContent().build();
	}
}
