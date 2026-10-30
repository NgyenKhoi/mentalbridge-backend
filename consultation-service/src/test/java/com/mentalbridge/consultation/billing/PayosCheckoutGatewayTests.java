package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;
import com.sun.net.httpserver.HttpServer;

import vn.payos.PayOS;
import vn.payos.core.ClientOptions;

class PayosCheckoutGatewayTests {

	private final BillingProperties properties = BillingPropertiesTests.payosProperties();
	private final ObjectMapper json = new ObjectMapper();

	@Test
	void noSdkOrTransportMutationRetryRedirectOrPayloadLoggingIsEnabled() {
		var options = PayosCheckoutGateway.sdkOptions(properties);
		assertThat(options.getMaxRetries()).isZero();
		assertThat(options.getLogLevel()).isEqualTo(ClientOptions.LogLevel.NONE);
		assertThat(options.getHttpClient().retryOnConnectionFailure()).isFalse();
		assertThat(options.getHttpClient().followRedirects()).isFalse();
		assertThat(options.getHttpClient().followSslRedirects()).isFalse();
		assertThat(options.getHttpClient().callTimeoutMillis()).isEqualTo(10_000);
	}

	@Test
	void sdkVerifiesFullWebhookAndOuterEnvelopeCannotAuthorizeSettlement() throws Exception {
		var client = client("https://api-merchant.payos.vn");
		var gateway = new PayosCheckoutGateway(properties, json, client);
		var data = webhookData();
		var body = webhook(client, data);
		assertThat(gateway.verify(json.writeValueAsString(body))).isEqualTo(
				new PayosCheckoutGateway.VerifiedWebhook(123, 5000, "synthetic-link", "synthetic-reference", true));
		body.put("success", false);
		assertThat(gateway.verify(json.writeValueAsString(body)).successful()).isFalse();
		body.put("success", true);
		body.put("code", "99");
		assertThat(gateway.verify(json.writeValueAsString(body)).successful()).isFalse();
		data.put("amount", 5001L);
		assertCode(() -> gateway.verify(json.writeValueAsString(body)), "PAYOS_SIGNATURE_INVALID");
		data.put("code", "99");
		var failedBody = webhook(client, data);
		assertThat(gateway.verify(json.writeValueAsString(failedBody)).successful()).isFalse();
	}

	@Test
	void rejectsMalformedCoercedDuplicateOrUnsignedExtraSignedFields() throws Exception {
		var client = client("https://api-merchant.payos.vn");
		var gateway = new PayosCheckoutGateway(properties, json, client);
		assertCode(() -> gateway.verify("{"), "PAYOS_WEBHOOK_INVALID");
		var data = webhookData();
		var body = webhook(client, data);
		var numeric = json.writeValueAsString(body);
		assertCode(() -> gateway.verify(numeric.replace("\"amount\":5000", "\"amount\":5000.0")), "PAYOS_WEBHOOK_INVALID");
		assertCode(() -> gateway.verify(numeric.replace("\"amount\":5000", "\"amount\":\"5000\"")), "PAYOS_WEBHOOK_INVALID");
		assertCode(() -> gateway.verify(numeric.replace("\"amount\":5000", "\"amount\":1,\"amount\":5000")), "PAYOS_WEBHOOK_INVALID");
		data.put("unknownSignedField", "must-be-verified");
		assertCode(() -> gateway.verify(json.writeValueAsString(body)), "PAYOS_SIGNATURE_INVALID");
	}

	@Test
	void sdkCreateQueryAndCancelVerifySignedHttpResponsesAndKeepChosenExpiry() throws Exception {
		var requests = new AtomicInteger();
		var requestBody = new AtomicReference<String>();
		var responseData = new AtomicReference<Map<String, Object>>();
		var tamper = new AtomicReference<>(false);
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var client = client("http://127.0.0.1:" + server.getAddress().getPort());
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			requestBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			var data = responseData.get();
			var signature = signData(data);
			var response = json.writeValueAsBytes(Map.of("code", "00", "desc", "success", "data", data,
					"signature", tamper.get() ? "invalid" : signature));
			exchange.sendResponseHeaders(200, response.length);
			exchange.getResponseBody().write(response);
			exchange.close();
		});
		server.start();
		try {
			var gateway = new PayosCheckoutGateway(properties, json, client);
			responseData.set(createResponse());
			var expiresAt = Instant.parse("2026-10-30T10:15:00Z");
			var checkout = gateway.create(123, 5000, expiresAt);
			assertThat(checkout.qrCode()).isEqualTo("synthetic-vietqr");
			assertThat(checkout.description()).isEqualTo("MB0000123");
			assertThat(checkout.paymentLinkId()).isEqualTo("synthetic-link");
			assertThat(json.readTree(requestBody.get()).path("expiredAt").longValue()).isEqualTo(expiresAt.getEpochSecond());
			assertThat(json.readTree(requestBody.get()).path("description").asText()).hasSize(9);
			responseData.set(statusResponse());
			var status = gateway.query(123);
			assertThat(status.status()).isEqualTo("PAID");
			assertThat(status.amountPaid()).isEqualTo(5000);
			assertThat(status.transactions()).containsExactly(new PayosCheckoutGateway.Transaction(5000, "synthetic-reference"));
			gateway.cancel(123);
			assertThat(requestBody.get()).contains("Checkout expired");
			tamper.set(true);
			assertCode(() -> gateway.query(123), "PAYOS_STATUS_UNAVAILABLE");
			assertThat(requests.get()).isEqualTo(4);
		}
		finally { server.stop(0); }
	}

	@Test
	void uncertainCreateNeverAutomaticallyRetriesAndProviderUrlMustBeSafe() throws Exception {
		var requests = new AtomicInteger();
		var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			requests.incrementAndGet();
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(500, -1);
			exchange.close();
		});
		server.start();
		try {
			var gateway = new PayosCheckoutGateway(properties, json, client("http://127.0.0.1:" + server.getAddress().getPort()));
			assertCode(() -> gateway.create(123, 5000, Instant.now().plusSeconds(900)), "PAYOS_CHECKOUT_UNAVAILABLE");
			assertThat(requests.get()).isOne();
		}
		finally { server.stop(0); }
		var sdk = org.mockito.Mockito.mock(PayOS.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
		var response = vn.payos.model.v2.paymentRequests.CreatePaymentLinkResponse.builder()
				.orderCode(123L).amount(5000L).description("MB0000123").currency("VND")
				.paymentLinkId("synthetic-link").qrCode("synthetic-qr").bin("000000").accountNumber("SYNTHETIC")
				.accountName("Synthetic").checkoutUrl("https://pay.payos.vn.evil.invalid/link")
				.status(vn.payos.model.v2.paymentRequests.PaymentLinkStatus.PENDING).build();
		org.mockito.Mockito.when(sdk.paymentRequests().create(org.mockito.ArgumentMatchers.any(vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest.class))).thenReturn(response);
		var gateway = new PayosCheckoutGateway(properties, json, sdk);
		assertCode(() -> gateway.create(123, 5000, Instant.now().plusSeconds(900)), "PAYOS_CHECKOUT_UNAVAILABLE");
		var noHostedUrl = org.mockito.Mockito.spy(response);
		org.mockito.Mockito.doReturn(null).when(noHostedUrl).getCheckoutUrl();
		org.mockito.Mockito.when(sdk.paymentRequests().create(org.mockito.ArgumentMatchers.any(vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest.class))).thenReturn(noHostedUrl);
		assertThat(gateway.create(123, 5000, Instant.now().plusSeconds(900)).checkoutUrl()).isNull();
	}

	@Test
	void fakeQrIsDeterministicParseableAndMakesNoProviderCall() {
		properties.setMode("FAKE");
		var sdk = org.mockito.Mockito.mock(PayosCheckoutGateway.class);
		var momo = org.mockito.Mockito.mock(MomoCheckoutGateway.class);
		var gateway = new ConfiguredCollectPaymentGateway(properties, momo, sdk);
		var expiry = Instant.parse("2026-10-30T10:15:00Z");
		var checkout = gateway.create("synthetic-order", "synthetic-request", 123L, 5000, expiry);
		assertThat(checkout).isEqualTo(gateway.create("synthetic-order", "synthetic-request", 123L, 5000, expiry));
		assertThat(checkout.qrCode()).startsWith("MENTALBRIDGE:FAKE?").contains("amount=5000", "description=MB0000123",
				"expiredAt=" + expiry.getEpochSecond());
		assertThat(java.net.URI.create(checkout.qrCode()).getScheme()).isEqualTo("MENTALBRIDGE");
		assertThat(checkout.accountNumber()).isEqualTo("SIMULATED");
		assertThat(checkout.checkoutUrl()).isNull();
		org.mockito.Mockito.verifyNoInteractions(sdk, momo);
	}

	private PayOS client(String baseUrl) {
		return new PayOS(ClientOptions.builder().clientId(properties.getPayosClientId()).apiKey(properties.getPayosApiKey())
				.checksumKey(properties.getPayosChecksumKey()).baseURL(baseUrl).maxRetries(0).timeoutMs(1000)
				.httpClient(PayosCheckoutGateway.sdkOptions(properties).getHttpClient()).build());
	}

	private Map<String, Object> webhook(PayOS client, Map<String, Object> data) throws Exception {
		var body = new LinkedHashMap<String, Object>();
		body.put("code", "00"); body.put("desc", "success"); body.put("success", true); body.put("data", data);
		body.put("signature", signData(data));
		return body;
	}

	private String signData(Map<String, Object> data) {
		try {
			var parts = new java.util.ArrayList<String>();
			for (var entry : new java.util.TreeMap<>(data).entrySet()) {
				var value = entry.getValue();
				var canonical = value == null ? "" : value instanceof List<?> list
						? json.writeValueAsString(list.stream().map(item -> new java.util.TreeMap<>((Map<?, ?>) item)).toList())
						: value.toString();
				parts.add(entry.getKey() + "=" + canonical);
			}
			var mac = javax.crypto.Mac.getInstance("HmacSHA256");
			mac.init(new javax.crypto.spec.SecretKeySpec(properties.getPayosChecksumKey()
					.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
			return java.util.HexFormat.of().formatHex(mac.doFinal(String.join("&", parts)
					.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		}
		catch (Exception exception) { throw new AssertionError(exception); }
	}

	private Map<String, Object> webhookData() {
		var data = new LinkedHashMap<String, Object>();
		data.put("orderCode", 123L); data.put("amount", 5000L); data.put("description", "MB0000123");
		data.put("accountNumber", "SYNTHETIC"); data.put("reference", "synthetic-reference");
		data.put("transactionDateTime", "2026-10-30 10:00:00"); data.put("currency", "VND");
		data.put("paymentLinkId", "synthetic-link"); data.put("code", "00"); data.put("desc", "success");
		for (var field : List.of("counterAccountBankId", "counterAccountBankName", "counterAccountName", "counterAccountNumber",
				"virtualAccountName", "virtualAccountNumber")) data.put(field, null);
		return data;
	}

	private Map<String, Object> createResponse() {
		var data = new LinkedHashMap<String, Object>();
		data.put("bin", "000000"); data.put("accountNumber", "SYNTHETIC"); data.put("accountName", "Synthetic");
		data.put("amount", 5000L); data.put("description", "MB0000123"); data.put("orderCode", 123L);
		data.put("currency", "VND"); data.put("paymentLinkId", "synthetic-link"); data.put("status", "PENDING");
		data.put("expiredAt", null); data.put("checkoutUrl", "https://pay.payos.vn/synthetic-link"); data.put("qrCode", "synthetic-vietqr");
		return data;
	}

	private Map<String, Object> statusResponse() {
		return Map.of("id", "synthetic-link", "orderCode", 123L, "amount", 5000L, "amountPaid", 5000L,
				"amountRemaining", 0L, "status", "PAID", "createdAt", "2026-10-30T10:00:00Z",
				"transactions", List.of(Map.of("reference", "synthetic-reference", "amount", 5000L,
						"accountNumber", "SYNTHETIC", "description", "MB0000123", "transactionDateTime", "2026-10-30 10:01:00")));
	}

	private void assertCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable callable, String code) {
		assertThatThrownBy(callable).isInstanceOfSatisfying(ApiException.class, exception -> assertThat(exception.code()).isEqualTo(code));
	}
}
