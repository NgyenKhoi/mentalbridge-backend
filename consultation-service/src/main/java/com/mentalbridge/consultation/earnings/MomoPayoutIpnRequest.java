package com.mentalbridge.consultation.earnings;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record MomoPayoutIpnRequest(@NotBlank String partnerCode, @NotBlank String orderId,
		@NotBlank String requestId, long amount, @NotNull Integer resultCode, long transId,
		long responseTime, String message, String orderInfo, String orderType, String extraData,
		@NotBlank String signature) {
}
