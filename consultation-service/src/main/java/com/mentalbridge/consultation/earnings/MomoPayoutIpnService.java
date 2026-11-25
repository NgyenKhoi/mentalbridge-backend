package com.mentalbridge.consultation.earnings;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

@Service
public class MomoPayoutIpnService {

	private final PayoutProperties properties;
	private final PayoutTransactions transactions;
	private final ObjectMapper json;

	public MomoPayoutIpnService(PayoutProperties properties, PayoutTransactions transactions, ObjectMapper json) {
		this.properties = properties;
		this.transactions = transactions;
		this.json = json;
	}

	public void receive(MomoPayoutIpnRequest request) {
		if (properties.getMomoPartnerCode().isBlank() || properties.getMomoAccessKey().isBlank()
				|| properties.getMomoSecretKey().isBlank()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MOMO_PAYOUT_CONFIGURATION_UNAVAILABLE",
					"MoMo payout callback is not configured");
		}
		if (!properties.getMomoPartnerCode().equals(request.partnerCode())
				|| !MessageDigest.isEqual(signature(request).getBytes(StandardCharsets.US_ASCII),
						request.signature().toLowerCase().getBytes(StandardCharsets.US_ASCII))) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "MOMO_PAYOUT_SIGNATURE_INVALID",
					"MoMo payout callback signature is invalid");
		}
		transactions.reconcileMomo(request, sha256(request));
	}

	private String signature(MomoPayoutIpnRequest request) {
		var raw = "accessKey=" + properties.getMomoAccessKey()
				+ "&amount=" + request.amount()
				+ "&extraData=" + safe(request.extraData())
				+ "&message=" + safe(request.message())
				+ "&orderId=" + request.orderId()
				+ "&orderInfo=" + safe(request.orderInfo())
				+ "&orderType=" + safe(request.orderType())
				+ "&partnerCode=" + request.partnerCode()
				+ "&requestId=" + request.requestId()
				+ "&responseTime=" + request.responseTime()
				+ "&resultCode=" + request.resultCode()
				+ "&transId=" + request.transId();
		try {
			var mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(properties.getMomoSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to verify MoMo payout callback", exception);
		}
	}

	private String sha256(MomoPayoutIpnRequest request) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(request)));
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to hash MoMo payout callback", exception);
		}
	}

	private String safe(String value) {
		return value == null ? "" : value;
	}
}
