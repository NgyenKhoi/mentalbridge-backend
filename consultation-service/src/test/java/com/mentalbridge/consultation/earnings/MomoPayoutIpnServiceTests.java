package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.shared.ApiException;

class MomoPayoutIpnServiceTests {

	private PayoutProperties properties;
	private PayoutTransactions transactions;
	private MomoPayoutIpnService service;

	@BeforeEach
	void setUp() {
		properties = new PayoutProperties();
		properties.setMomoPartnerCode("MOMO_SANDBOX");
		properties.setMomoAccessKey("sandbox-access");
		properties.setMomoSecretKey("sandbox-secret");
		transactions = mock(PayoutTransactions.class);
		service = new MomoPayoutIpnService(properties, transactions, new ObjectMapper());
	}

	@Test
	void validSignedCallbackIsForwardedForIdempotentReconciliation() throws Exception {
		var unsigned = request("");
		var signed = request(sign(unsigned));

		service.receive(signed);

		verify(transactions).reconcileMomo(eq(signed), matches("[0-9a-f]{64}"));
	}

	@Test
	void invalidSignatureFailsClosedBeforeReconciliation() {
		var request = request("not-a-valid-signature");

		assertThatThrownBy(() -> service.receive(request)).isInstanceOf(ApiException.class)
				.satisfies(error -> org.assertj.core.api.Assertions.assertThat(((ApiException) error).code())
						.isEqualTo("MOMO_PAYOUT_SIGNATURE_INVALID"));
		verify(transactions, never()).reconcileMomo(eq(request), matches(".*"));
	}

	private MomoPayoutIpnRequest request(String signature) {
		return new MomoPayoutIpnRequest("MOMO_SANDBOX", "payout-order", "payout-request", 210_000,
				0, 123456789L, 1791277200000L, "Successful.", "Specialist payout",
				"disburseToWallet", "", signature);
	}

	private String sign(MomoPayoutIpnRequest request) throws Exception {
		var raw = "accessKey=" + properties.getMomoAccessKey()
				+ "&amount=" + request.amount()
				+ "&extraData=" + request.extraData()
				+ "&message=" + request.message()
				+ "&orderId=" + request.orderId()
				+ "&orderInfo=" + request.orderInfo()
				+ "&orderType=" + request.orderType()
				+ "&partnerCode=" + request.partnerCode()
				+ "&requestId=" + request.requestId()
				+ "&responseTime=" + request.responseTime()
				+ "&resultCode=" + request.resultCode()
				+ "&transId=" + request.transId();
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(properties.getMomoSecretKey().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		return HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8)));
	}
}
