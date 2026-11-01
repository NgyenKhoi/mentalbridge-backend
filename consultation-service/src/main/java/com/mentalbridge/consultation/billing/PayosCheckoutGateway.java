package com.mentalbridge.consultation.billing;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

import vn.payos.PayOS;
import vn.payos.core.ClientOptions;
import vn.payos.model.v2.paymentRequests.CreatePaymentLinkRequest;
import okhttp3.OkHttpClient;

@Component
class PayosCheckoutGateway {

	private static final long MAX_ORDER_CODE = 9_007_199_254_740_991L;
	private final BillingProperties properties;
	private final ObjectMapper json;
	private final PayOS client;

	@Autowired
	PayosCheckoutGateway(BillingProperties properties, ObjectMapper json) {
		this(properties, json, new PayOS(sdkOptions(properties)));
	}

	static ClientOptions sdkOptions(BillingProperties properties) {
		var http = new OkHttpClient.Builder().connectTimeout(Duration.ofSeconds(5))
				.readTimeout(Duration.ofSeconds(10)).writeTimeout(Duration.ofSeconds(10))
				.callTimeout(Duration.ofSeconds(10)).retryOnConnectionFailure(false)
				.followRedirects(false).followSslRedirects(false).build();
		return ClientOptions.builder().clientId(properties.getPayosClientId()).apiKey(properties.getPayosApiKey())
				.checksumKey(properties.getPayosChecksumKey()).baseURL(properties.getPayosBaseUrl())
				.timeoutMs(10_000).maxRetries(0).logLevel(ClientOptions.LogLevel.NONE).httpClient(http).build();
	}

	PayosCheckoutGateway(BillingProperties properties, ObjectMapper json, PayOS client) {
		this.properties = properties;
		this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
		this.client = client;
	}

	CollectPaymentGateway.Checkout create(long orderCode, long amount, Instant expiresAt) {
		requireReady("PAYOS_CHECKOUT_UNAVAILABLE");
		validateOrder(orderCode);
		var description = "MB" + String.format(java.util.Locale.ROOT, "%07d", orderCode % 10_000_000L);
		try {
			var response = client.paymentRequests().create(CreatePaymentLinkRequest.builder()
					.orderCode(orderCode).amount(amount).description(description)
					.returnUrl(properties.getPayosReturnUrl()).cancelUrl(properties.getPayosCancelUrl())
					.expiredAt(expiresAt.getEpochSecond()).build());
			if (response.getOrderCode() == null || response.getOrderCode() != orderCode
					|| response.getAmount() == null || response.getAmount() != amount
					|| !"VND".equals(response.getCurrency()) || !description.equals(response.getDescription())
					|| !present(response.getPaymentLinkId(), 128) || !present(response.getQrCode(), 8192)
					|| !present(response.getBin(), 16) || !present(response.getAccountNumber(), 64)
					|| !present(response.getAccountName(), 256)
					|| (response.getExpiredAt() != null && response.getExpiredAt() != expiresAt.getEpochSecond())) {
				throw unavailable("PAYOS_CHECKOUT_UNAVAILABLE");
			}
			return new CollectPaymentGateway.Checkout(safeCheckoutUrl(response.getCheckoutUrl()), null,
					response.getPaymentLinkId(), response.getQrCode(), response.getBin(),
					response.getAccountNumber(), response.getAccountName(), response.getDescription());
		}
		catch (Exception exception) {
			throw unavailable("PAYOS_CHECKOUT_UNAVAILABLE");
		}
	}

	Status query(long orderCode) {
		requireReady("PAYOS_STATUS_UNAVAILABLE");
		validateOrder(orderCode);
		try {
			var response = client.paymentRequests().get(orderCode);
			if (response.getOrderCode() == null || response.getAmount() == null || response.getAmountPaid() == null
					|| response.getAmountRemaining() == null || response.getStatus() == null
					|| !present(response.getId(), 128) || response.getTransactions() == null) {
				throw unavailable("PAYOS_STATUS_UNAVAILABLE");
			}
			var transactions = response.getTransactions().stream().map(transaction -> {
				if (transaction.getAmount() == null || !present(transaction.getReference(), 128)) {
					throw unavailable("PAYOS_STATUS_UNAVAILABLE");
				}
				return new Transaction(transaction.getAmount(), transaction.getReference());
			}).toList();
			return new Status(response.getOrderCode(), response.getId(), response.getAmount(), response.getAmountPaid(),
					response.getAmountRemaining(), response.getStatus().name(), transactions);
		}
		catch (Exception exception) {
			throw unavailable("PAYOS_STATUS_UNAVAILABLE");
		}
	}

	void cancel(long orderCode) {
		requireReady("PAYOS_STATUS_UNAVAILABLE");
		validateOrder(orderCode);
		try {
			client.paymentRequests().cancel(orderCode, "Checkout expired");
		}
		catch (Exception exception) {
			throw unavailable("PAYOS_STATUS_UNAVAILABLE");
		}
	}

	void confirmWebhook() {
		requireReady("PAYOS_WEBHOOK_UNAVAILABLE");
		try {
			client.webhooks().confirm(properties.getPayosWebhookUrl());
		}
		catch (Exception exception) {
			throw unavailable("PAYOS_WEBHOOK_UNAVAILABLE");
		}
	}

	VerifiedWebhook verify(String originalBody) {
		if (!properties.payosVerificationReady()) throw unavailable("PAYOS_WEBHOOK_UNAVAILABLE");
		if (originalBody == null || originalBody.length() > 65_536) throw invalid();
		try {
			client.webhooks().verify(originalBody);
		}
		catch (Exception exception) {
			throw verificationFailure(originalBody);
		}
		try {
			var body = json.readTree(originalBody);
			var data = body.path("data");
			if (!body.isObject() || !data.isObject() || !body.path("success").isBoolean()
					|| !text(body, "code", 32) || !string(body, "desc", 1024) || !text(body, "signature", 128)
					|| !data.path("orderCode").isIntegralNumber() || !data.path("orderCode").canConvertToLong()
					|| !data.path("amount").isIntegralNumber() || !data.path("amount").canConvertToLong()
					|| !text(data, "paymentLinkId", 128) || !text(data, "reference", 128)
					|| !text(data, "code", 32) || !text(data, "currency", 3)
					|| !string(data, "description", 1024) || !string(data, "accountNumber", 64)
					|| !string(data, "transactionDateTime", 64) || !string(data, "desc", 1024)
					|| data.path("orderCode").longValue() < 0 || data.path("orderCode").longValue() > MAX_ORDER_CODE
					|| data.path("amount").longValue() < 0) throw invalid();
			Map<String, Object> completeData = json.convertValue(data, new TypeReference<>() { });
			var expected = client.getCrypto().createSignatureFromObj(completeData, properties.getPayosChecksumKey());
			if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
					body.path("signature").textValue().getBytes(StandardCharsets.UTF_8))) {
				throw new ApiException(HttpStatus.UNAUTHORIZED, "PAYOS_SIGNATURE_INVALID", "Invalid payment signature");
			}
			return new VerifiedWebhook(data.path("orderCode").longValue(), data.path("amount").longValue(),
					data.path("paymentLinkId").textValue(), data.path("reference").textValue(),
					body.path("success").booleanValue() && "00".equals(body.path("code").textValue())
					&& "00".equals(data.path("code").textValue()) && "VND".equals(data.path("currency").textValue()));
		}
		catch (ApiException exception) { throw exception; }
		catch (Exception exception) { throw invalid(); }
	}

	private boolean text(JsonNode object, String key, int maxLength) {
		return object.path(key).isTextual() && present(object.path(key).textValue(), maxLength);
	}

	private boolean string(JsonNode object, String key, int maxLength) {
		return object.path(key).isTextual() && object.path(key).textValue().length() <= maxLength;
	}

	private ApiException verificationFailure(String originalBody) {
		try {
			var body = json.readTree(originalBody);
			if (body == null || !body.isObject() || !body.path("data").isObject()
					|| !body.path("success").isBoolean() || !text(body, "code", 32)
					|| !string(body, "desc", 1024) || !text(body, "signature", 128)) return invalid();
		}
		catch (Exception exception) { return invalid(); }
		return new ApiException(HttpStatus.UNAUTHORIZED, "PAYOS_SIGNATURE_INVALID", "Invalid payment signature");
	}

	private boolean present(String value, int maxLength) {
		return value != null && !value.isBlank() && value.length() <= maxLength;
	}

	private String safeCheckoutUrl(String value) {
		if (value == null) return null;
		var uri = URI.create(value);
		if (!"https".equalsIgnoreCase(uri.getScheme()) || !"pay.payos.vn".equalsIgnoreCase(uri.getHost())
				|| uri.getUserInfo() != null || uri.getFragment() != null
				|| (uri.getPort() != -1 && uri.getPort() != 443)) throw unavailable("PAYOS_CHECKOUT_UNAVAILABLE");
		return value;
	}

	private void requireReady(String code) {
		if (!properties.payosReady()) throw unavailable(code);
	}

	private void validateOrder(long orderCode) {
		if (orderCode <= 0 || orderCode > MAX_ORDER_CODE) throw unavailable("PAYOS_CHECKOUT_UNAVAILABLE");
	}

	private ApiException unavailable(String code) {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, code, "Payment provider is unavailable");
	}

	private ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "PAYOS_WEBHOOK_INVALID", "Invalid payment notification");
	}

	record Transaction(long amount, String reference) { }
	record Status(long orderCode, String paymentLinkId, long amount, long amountPaid, long amountRemaining,
			String status, List<Transaction> transactions) { }
	record VerifiedWebhook(long orderCode, long amount, String paymentLinkId, String reference, boolean successful) { }
}
