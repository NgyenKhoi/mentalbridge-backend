package com.mentalbridge.consultation.earnings;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CreatePayoutRequest(@NotNull UUID destinationId) {
}
