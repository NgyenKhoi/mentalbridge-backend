package com.mentalbridge.identity.account;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

public record AccountStateChangeRequest(
		@NotNull AccountStatus status,
		@NotNull AccountStateReasonCode reasonCode) {

	@AssertTrue(message = "status must be ACTIVE or DISABLED")
	public boolean isSupportedStatus() {
		return status == null || status == AccountStatus.ACTIVE || status == AccountStatus.DISABLED;
	}
}
