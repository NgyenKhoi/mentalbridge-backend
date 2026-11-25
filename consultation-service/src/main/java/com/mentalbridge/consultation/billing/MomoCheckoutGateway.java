package com.mentalbridge.consultation.billing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

@Component
class MomoCheckoutGateway {

	private static final String CREATE_PATH = "/v2/gateway/api/create";
	private final BillingProperties properties;
	private final ObjectMapper json;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

	MomoCheckoutGateway(BillingProperties properties, ObjectMapper json) {
		this.properties = properties;
		this.json = json;
	}

	Checkout create(String orderId, String requestId, long amount, String orderInfo) {
		if (properties.fake()) {
			var encoded = java.net.URLEncoder.encode(orderId, StandardCharsets.UTF_8);
			return new Checkout("https://test-payment.momo.vn/v2/gateway/pay?t=" + encoded,
					"https://test-payment.momo.vn/v2/gateway/qr/" + encoded);
		}
		if (!properties.momoReady()) throw unavailable();
		var extraData = "";
		var raw = "accessKey=" + properties.getMomoAccessKey()
				+ "&amount=" + amount
				+ "&extraData=" + extraData
				+ "&ipnUrl=" + properties.getMomoIpnUrl()
				+ "&orderId=" + orderId
				+ "&orderInfo=" + orderInfo
				+ "&partnerCode=" + properties.getMomoPartnerCode()
				+ "&redirectUrl=" + properties.getMomoRedirectUrl()
				+ "&requestId=" + requestId
				+ "&requestType=captureWallet";
		var body = new LinkedHashMap<String, Object>();
		body.put("partnerCode", properties.getMomoPartnerCode());
		body.put("partnerName", "MentalBridge");
		body.put("storeId", "MentalBridge");
		body.put("requestId", requestId);
		body.put("amount", amount);
		body.put("orderId", orderId);
		body.put("orderInfo", orderInfo);
		body.put("redirectUrl", properties.getMomoRedirectUrl());
		body.put("ipnUrl", properties.getMomoIpnUrl());
		body.put("lang", "vi");
		body.put("requestType", "captureWallet");
		body.put("autoCapture", true);
		body.put("extraData", extraData);
		body.put("signature", hmac(raw));
		try {
			var endpoint = URI.create(properties.getMomoBaseUrl()).resolve(CREATE_PATH);
			var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body))).build();
			var response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
			if (response.statusCode() / 100 != 2 || response.body().length > 64 * 1024) throw unavailable();
			@SuppressWarnings("unchecked") var value = json.readValue(response.body(), Map.class);
			if (!properties.getMomoPartnerCode().equals(value.get("partnerCode"))
					|| !orderId.equals(value.get("orderId")) || !requestId.equals(value.get("requestId"))
					|| number(value.get("amount")) != amount || number(value.get("resultCode")) != 0) throw unavailable();
			return new Checkout(safeProviderUrl(value.get("payUrl")), safeProviderUrl(value.get("qrCodeUrl")));
		}
		catch (ApiException exception) {
			throw exception;
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw unavailable();
		}
		catch (Exception exception) {
			throw unavailable();
		}
	}

	private long number(Object value) {
		return value instanceof Number number ? number.longValue() : Long.MIN_VALUE;
	}

	private String safeProviderUrl(Object value) {
		if (!(value instanceof String text)) throw unavailable();
		try {
			var uri = URI.create(text);
			var configured = URI.create(properties.getMomoBaseUrl());
			if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
					|| uri.getHost() == null || !uri.getHost().equalsIgnoreCase(configured.getHost())) throw unavailable();
			return uri.toString();
		}
		catch (IllegalArgumentException exception) {
			throw unavailable();
		}
	}

	private String hmac(String raw) {
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.getMomoSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to sign MoMo checkout request", exception);
		}
	}

	private ApiException unavailable() {
		return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MOMO_CHECKOUT_UNAVAILABLE",
				"MoMo checkout is unavailable");
	}

	record Checkout(String payUrl, String qrCodeUrl) { }
}
