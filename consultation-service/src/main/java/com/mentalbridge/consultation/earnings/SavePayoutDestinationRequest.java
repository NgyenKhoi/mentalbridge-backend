package com.mentalbridge.consultation.earnings;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SavePayoutDestinationRequest(
		@NotBlank @Pattern(regexp = "MOMO_WALLET|BANK_ACCOUNT") String destinationType,
		@NotBlank @Size(min = 6, max = 128) String accountReference,
		@NotBlank @Size(max = 100) String accountHolderName,
		@Size(max = 32) String bankCode) {

	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		throw new IllegalArgumentException("Unsupported payout destination field");
	}
}
