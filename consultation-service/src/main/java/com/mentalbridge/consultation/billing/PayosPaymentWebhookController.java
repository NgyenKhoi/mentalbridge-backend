package com.mentalbridge.consultation.billing;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;

import jakarta.servlet.http.HttpServletRequest;

@RestController
class PayosPaymentWebhookController {

    private static final int MAX_BODY_BYTES = 65_536;
    private final PayosPaymentWebhookService webhook;

    PayosPaymentWebhookController(PayosPaymentWebhookService webhook) {
        this.webhook = webhook;
    }

    @PostMapping("/internal/v1/payments/payos/webhook")
    ResponseEntity<Void> receive(HttpServletRequest request) {
        if (request.getContentLengthLong() > MAX_BODY_BYTES) throw invalid();
        byte[] originalBody;
        try {
            originalBody = request.getInputStream().readNBytes(MAX_BODY_BYTES + 1);
        }
        catch (IOException exception) {
            throw invalid();
        }
        if (originalBody.length > MAX_BODY_BYTES) throw invalid();
        webhook.receive(new String(originalBody, StandardCharsets.UTF_8));
        return ResponseEntity.noContent().build();
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "PAYOS_WEBHOOK_INVALID", "Invalid payment notification");
    }
}
