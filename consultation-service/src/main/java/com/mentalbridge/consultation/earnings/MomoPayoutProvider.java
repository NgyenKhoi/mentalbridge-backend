package com.mentalbridge.consultation.earnings;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.LinkedHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;

@Component
class MomoPayoutProvider {

	private final PayoutProperties properties;
	private final PayoutDestinationCipher cipher;
	private final ObjectMapper json;
	private final MomoDisbursementTransport transport;

	MomoPayoutProvider(PayoutProperties properties, PayoutDestinationCipher cipher, ObjectMapper json,
			MomoDisbursementTransport transport) {
		this.properties = properties;
		this.cipher = cipher;
		this.json = json;
		this.transport = transport;
	}

	PayoutProvider.Result submit(PayoutProvider.Command command) {
		if (!properties.momoReady()) return failed("REAL_PAYOUT_DISABLED");
		if (!"VND".equals(command.currency()) || command.amountVnd() <= 0) return failed("PAYOUT_AMOUNT_INVALID");
		try {
			var details = json.readValue(cipher.decrypt(command.destinationCiphertext()),
					SpecialistPayoutService.PayoutDestinationDetails.class);
			var requestType = command.destinationType().equals("MOMO_WALLET")
					? "disburseToWallet" : "disburseToBank";
			var method = new LinkedHashMap<String, Object>();
			if (requestType.equals("disburseToWallet")) {
				method.put("walletId", details.accountReference());
				method.put("walletName", details.accountHolderName());
				method.put("personalId", "");
			}
			else {
				method.put("bankAccountNo", details.accountReference());
				method.put("bankAccountHolderName", details.accountHolderName());
				method.put("bankCode", details.bankCode());
			}
			var encryptedMethod = rsaEncrypt(json.writeValueAsString(method));
			var extraData = "";
			var orderInfo = "MentalBridge specialist payout";
			var rawSignature = "accessKey=" + properties.getMomoAccessKey()
					+ "&amount=" + command.amountVnd()
					+ "&disbursementMethod=" + encryptedMethod
					+ "&extraData=" + extraData
					+ "&orderId=" + command.payoutId()
					+ "&orderInfo=" + orderInfo
					+ "&partnerCode=" + properties.getMomoPartnerCode()
					+ "&requestId=" + command.requestId()
					+ "&requestType=" + requestType;
			var request = new MomoRequest(properties.getMomoPartnerCode(), properties.getMomoStoreId(),
					command.payoutId().toString(), command.amountVnd(), command.requestId(), requestType,
					encryptedMethod, properties.getMomoIpnUrl(), extraData, orderInfo, "vi", hmac(rawSignature));
			var response = transport.send(properties.getMomoBaseUrl(), request);
			if (response == null || !properties.getMomoPartnerCode().equals(response.partnerCode())
					|| !command.payoutId().toString().equals(response.orderId())
					|| !command.requestId().equals(response.requestId()) || command.amountVnd() != response.amount()) {
				return new PayoutProvider.Result("UNKNOWN", null, "MOMO_RESPONSE_MISMATCH");
			}
			var reference = response.transId() == null ? null : response.transId().toString();
			if (response.resultCode() == 0) return new PayoutProvider.Result("SUCCEEDED", reference, null);
			if (response.resultCode() == 7000 || response.resultCode() == 7002) {
				return new PayoutProvider.Result("PROCESSING", reference, null);
			}
			return new PayoutProvider.Result("FAILED", reference, "MOMO_" + response.resultCode());
		}
		catch (Exception exception) {
			// The request may have reached MoMo. Keep funds reserved until an IPN or operator reconciliation.
			return new PayoutProvider.Result("UNKNOWN", null, "MOMO_SUBMISSION_UNKNOWN");
		}
	}

	private PayoutProvider.Result failed(String code) {
		return new PayoutProvider.Result("FAILED", null, code);
	}

	private String rsaEncrypt(String value) throws Exception {
		var pem = properties.getMomoPublicKey().replace("-----BEGIN PUBLIC KEY-----", "")
				.replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
		var key = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(pem)));
		var rsa = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding");
		rsa.init(javax.crypto.Cipher.ENCRYPT_MODE, key);
		return Base64.getEncoder().encodeToString(rsa.doFinal(value.getBytes(StandardCharsets.UTF_8)));
	}

	private String hmac(String value) throws Exception {
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(properties.getMomoSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
	}

	record MomoRequest(String partnerCode, String storeId, String orderId, long amount, String requestId,
			String requestType, String disbursementMethod, String ipnUrl, String extraData, String orderInfo,
			String lang, String signature) {
	}

	record MomoResponse(String partnerCode, String orderId, String requestId, long amount, Long transId,
			long responseTime, int resultCode, String message, Long balance) {
	}
}
