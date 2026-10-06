package com.mentalbridge.consultation.earnings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SavePayoutDestinationRequest(
		@NotBlank @Pattern(regexp = "MOMO_WALLET|BANK_ACCOUNT") String destinationType,
		@NotBlank @Size(min = 6, max = 128) String accountReference) {
}
