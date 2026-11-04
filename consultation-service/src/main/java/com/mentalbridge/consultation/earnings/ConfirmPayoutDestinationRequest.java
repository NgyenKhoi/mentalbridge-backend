package com.mentalbridge.consultation.earnings;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record ConfirmPayoutDestinationRequest(@NotNull @Min(0) Long expectedVersion,
		@NotNull @Pattern(regexp = "ACCOUNT_HOLDER_CHECKED") String reasonCode) {

	@JsonAnySetter
	public void rejectUnknownProperty(String name, Object value) {
		throw new IllegalArgumentException("Unsupported payout confirmation field");
	}
}
