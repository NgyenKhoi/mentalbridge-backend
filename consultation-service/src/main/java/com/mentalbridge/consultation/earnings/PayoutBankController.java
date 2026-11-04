package com.mentalbridge.consultation.earnings;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PayoutBankController {

	private final PayoutBankDirectory banks;

	public PayoutBankController(PayoutBankDirectory banks) {
		this.banks = banks;
	}

	@GetMapping("/api/v1/specialist/payout-banks")
	ResponseEntity<PayoutBankDirectory.BankList> banks() {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(banks.banks());
	}
}
