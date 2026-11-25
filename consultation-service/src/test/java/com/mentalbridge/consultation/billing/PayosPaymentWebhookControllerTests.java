package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.DelegatingServletInputStream;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiExceptionHandler;

import jakarta.servlet.ServletInputStream;

class PayosPaymentWebhookControllerTests {

    private static final int MAX_BODY_BYTES = 65_536;
    private final BillingProperties properties = BillingPropertiesTests.payosProperties();
    private final ObjectMapper json = new ObjectMapper();
    private PayosPaymentWebhookService webhook;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        webhook = mock(PayosPaymentWebhookService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PayosPaymentWebhookController(webhook))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void declaredOversizeIsRejectedBeforeOpeningTheBody() throws Exception {
        var request = new TrackedRequest("sensitive-provider-payload".getBytes(StandardCharsets.UTF_8), MAX_BODY_BYTES + 1);
        mvc.perform(context -> request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYOS_WEBHOOK_INVALID"))
                .andExpect(jsonPath("$.detail").value("Invalid payment notification"));
        assertThat(request.streamsOpened).isZero();
        assertThat(request.bytesRead()).isZero();
        verifyNoInteractions(webhook);
    }

    @ParameterizedTest
    @ValueSource(longs = { -1, 1 })
    void streamedOversizeStopsAfterTheFirstOverflowByteWithoutInvokingSettlement(long declaredLength) throws Exception {
        var request = new TrackedRequest(new byte[MAX_BODY_BYTES * 4], declaredLength);
        mvc.perform(context -> request).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PAYOS_WEBHOOK_INVALID"))
                .andExpect(jsonPath("$.detail").value("Invalid payment notification"));
        assertThat(request.bytesRead()).isEqualTo(MAX_BODY_BYTES + 1);
        verifyNoInteractions(webhook);
    }

    @ParameterizedTest
    @ValueSource(booleans = { true, false })
    void atLimitCompleteValidPayloadReachesSdkVerificationUnchanged(boolean contentLengthPresent) throws Exception {
        var payload = signedPayloadAtLimit();
        var bytes = payload.getBytes(StandardCharsets.UTF_8);
        assertThat(bytes).hasSize(MAX_BODY_BYTES);
        assertThat(payload.length()).isLessThan(MAX_BODY_BYTES);
        var gateway = new PayosCheckoutGateway(properties, json);
        doAnswer(invocation -> {
            var originalBody = invocation.getArgument(0, String.class);
            assertThat(originalBody).isEqualTo(payload);
            assertThat(gateway.verify(originalBody)).isEqualTo(new PayosCheckoutGateway.VerifiedWebhook(
                    123, 5_000, "synthetic-link", "synthetic-reference", true));
            return null;
        }).when(webhook).receive(anyString());
        var request = new TrackedRequest(bytes, contentLengthPresent ? bytes.length : -1);
        mvc.perform(context -> request).andExpect(status().isNoContent());
        assertThat(request.bytesRead()).isEqualTo(MAX_BODY_BYTES);
        verify(webhook).receive(payload);
    }

    private String signedPayloadAtLimit() throws Exception {
        var root = json.createObjectNode().put("code", "00").put("desc", "Đã nhận").put("success", true);
        var data = root.putObject("data").put("orderCode", 123).put("amount", 5_000)
                .put("description", "MB0000123").put("accountNumber", "SYNTHETIC")
                .put("reference", "synthetic-reference").put("transactionDateTime", "2026-10-30 10:00:00")
                .put("currency", "VND").put("paymentLinkId", "synthetic-link").put("code", "00").put("desc", "success");
        for (var field : List.of("counterAccountBankId", "counterAccountBankName", "counterAccountName",
                "counterAccountNumber", "virtualAccountName", "virtualAccountNumber")) data.put(field, "");
        var values = new TreeMap<String, String>();
        data.fields().forEachRemaining(entry -> values.put(entry.getKey(), entry.getValue().asText()));
        var canonical = values.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(java.util.stream.Collectors.joining("&"));
        var mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(properties.getPayosChecksumKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        root.put("signature", HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        var payload = json.writeValueAsString(root);
        return payload + " ".repeat(MAX_BODY_BYTES - payload.getBytes(StandardCharsets.UTF_8).length);
    }

    private static class TrackedRequest extends MockHttpServletRequest {

        private final ByteArrayInputStream body;
        private final int bodySize;
        private final long declaredLength;
        private int streamsOpened;

        TrackedRequest(byte[] body, long declaredLength) {
            this.body = new ByteArrayInputStream(body);
            this.bodySize = body.length;
            this.declaredLength = declaredLength;
            setMethod("POST");
            setRequestURI("/internal/v1/payments/payos/webhook");
            setContentType("application/json");
            if (declaredLength < 0) addHeader("Transfer-Encoding", "chunked");
        }

        @Override
        public int getContentLength() { return (int) declaredLength; }

        @Override
        public long getContentLengthLong() { return declaredLength; }

        @Override
        public ServletInputStream getInputStream() {
            streamsOpened++;
            return new DelegatingServletInputStream(body);
        }

        int bytesRead() { return bodySize - body.available(); }
    }
}
