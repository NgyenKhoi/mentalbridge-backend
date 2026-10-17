package com.mentalbridge.consultation.billing;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record CheckoutRequest(@NotNull UUID planVersionId) { }
