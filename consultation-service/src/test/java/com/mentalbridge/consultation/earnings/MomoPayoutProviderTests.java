package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Cipher;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

class MomoPayoutProviderTests {

	@Test
	void submitsTheServerAuthoritativeAmountWithEncryptedWalletDetails() throws Exception {
		var keys = KeyPairGenerator.getInstance("RSA");
		keys.initialize(2048);
		var keyPair = keys.generateKeyPair();
		var properties = properties(Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
		var json = new ObjectMapper();
		var destinationCipher = new PayoutDestinationCipher(properties);
		var transport = mock(MomoDisbursementTransport.class);
		var request = ArgumentCaptor.forClass(MomoPayoutProvider.MomoRequest.class);
		when(transport.send(eq("https://test-payment.momo.vn"), request.capture()))
				.thenAnswer(invocation -> {
					var sent = request.getValue();
					return new MomoPayoutProvider.MomoResponse("MOMO_SANDBOX", sent.orderId(), sent.requestId(),
							sent.amount(), 123456789L, System.currentTimeMillis(), 0, "Successful.", 999999L);
				});
		var details = json.writeValueAsString(new SpecialistPayoutService.PayoutDestinationDetails(
				"0912345678", "Nguyen Thu Ha", ""));
		var payoutId = UUID.randomUUID();
		var provider = new MomoPayoutProvider(properties, destinationCipher, json, transport);

		var result = provider.submit(new PayoutProvider.Command(payoutId, UUID.randomUUID(), payoutId + ":1",
				210_000, "VND", "MOMO_WALLET", destinationCipher.encrypt(details)));

		assertThat(result.status()).isEqualTo("SUCCEEDED");
		assertThat(result.providerReference()).isEqualTo("123456789");
		assertThat(request.getValue().amount()).isEqualTo(210_000);
		assertThat(request.getValue().requestType()).isEqualTo("disburseToWallet");
		assertThat(request.getValue().signature()).hasSize(64);
		var rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
		rsa.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
		var method = json.readTree(new String(rsa.doFinal(
				Base64.getDecoder().decode(request.getValue().disbursementMethod())), StandardCharsets.UTF_8));
		assertThat(method.get("walletId").asText()).isEqualTo("0912345678");
		assertThat(method.get("walletName").asText()).isEqualTo("Nguyen Thu Ha");
	}

	@Test
	void productionGateKeepsRealSubmissionOffWithoutExplicitApproval() {
		var properties = properties("unused");
		properties.setProductionApproved(false);
		var transport = mock(MomoDisbursementTransport.class);
		var provider = new MomoPayoutProvider(properties, new PayoutDestinationCipher(properties),
				new ObjectMapper(), transport);

		var result = provider.submit(new PayoutProvider.Command(UUID.randomUUID(), UUID.randomUUID(), "request-1",
				210_000, "VND", "MOMO_WALLET", "not-read"));

		assertThat(result.status()).isEqualTo("FAILED");
		assertThat(result.failureCode()).isEqualTo("REAL_PAYOUT_DISABLED");
	}

	private PayoutProperties properties(String publicKey) {
		var properties = new PayoutProperties();
		properties.setMode("MOMO");
		properties.setProductionApproved(true);
		properties.setEncryptionKey(Base64.getEncoder().encodeToString(new byte[32]));
		properties.setMomoBaseUrl("https://test-payment.momo.vn");
		properties.setMomoIpnUrl("https://staging.example.com/api/v1/payouts/momo/ipn");
		properties.setMomoPartnerCode("MOMO_SANDBOX");
		properties.setMomoAccessKey("access-key");
		properties.setMomoSecretKey("secret-key");
		properties.setMomoStoreId("mentalbridge");
		properties.setMomoPublicKey(publicKey);
		return properties;
	}
}
