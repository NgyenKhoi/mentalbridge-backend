package com.mentalbridge.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.net.URI;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.mentalbridge.identity.configuration.VerificationDeliveryProperties;
import com.mentalbridge.identity.credential.CredentialDeliveryListener;
import com.mentalbridge.identity.credential.CredentialDeliveryRequested;

@ExtendWith(OutputCaptureExtension.class)
class VerificationDeliveryTests {

	@Test
	void brevoAdapterSendsRecoveryEmailThroughAMockedHttpServer() {
		var builder = RestClient.builder();
		var server = MockRestServiceServer.bindTo(builder).build();
		var delivery = new BrevoVerificationDelivery(builder, properties());

		server.expect(requestTo("https://api.brevo.com/v3/smtp/email"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("api-key", "test-key"))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("Đặt lại mật khẩu MentalBridge")))
				.andExpect(content().string(org.hamcrest.Matchers.containsString("reset-password?challenge=")))
				.andRespond(withSuccess());

		delivery.requestPasswordRecovery(UUID.randomUUID(), "synthetic@example.test",
				"synthetic-recovery-challenge-that-is-long-enough", UUID.randomUUID());

		server.verify();
	}

	@Test
	void brevoAdapterRejectsIncompleteConfigurationWithoutCallingTheProvider() {
		var incomplete = new VerificationDeliveryProperties(URI.create("https://api.brevo.com"), "", "", "MentalBridge",
				URI.create("http://localhost:3000/verify-email"),
				URI.create("http://localhost:3000/reset-password"));

		assertThatThrownBy(() -> new BrevoVerificationDelivery(RestClient.builder(), incomplete))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("configuration is incomplete");
	}

	@Test
	void verificationEmailMatchesTheFrontendBrandAndEscapesItsActionUrl() {
		var content = VerificationEmailTemplate
				.create("http://localhost:3000/verify-email?challenge=safe&next=<unsafe>");

		assertThat(content.subject()).isEqualTo("Xác minh tài khoản MentalBridge");
		assertThat(content.html()).contains("lang=\"vi\"", "#F1F4EB", "#1E4A43", "#3D7A6E",
				"Cây cầu đến sự cân bằng", "Xác minh email", "Liên kết bảo mật dùng một lần",
				"challenge=safe&amp;next=&lt;unsafe&gt;").doesNotContain("next=<unsafe>");
		assertThat(content.text()).contains("Xác minh email", "challenge=safe&next=<unsafe>");
	}

	@Test
	void recoveryEmailMatchesTheSameFrontendBrandAndEscapesItsActionUrl() {
		var content = PasswordRecoveryEmailTemplate
				.create("http://localhost:3000/reset-password?challenge=safe&next=<unsafe>");

		assertThat(content.subject()).isEqualTo("Đặt lại mật khẩu MentalBridge");
		assertThat(content.html()).contains("lang=\"vi\"", "#F1F4EB", "#1E4A43", "#3D7A6E",
				"Cây cầu đến sự cân bằng", "Đặt lại mật khẩu", "Liên kết có hiệu lực trong 15 phút",
				"challenge=safe&amp;next=&lt;unsafe&gt;").doesNotContain("next=<unsafe>");
		assertThat(content.text()).contains("Đặt lại mật khẩu", "challenge=safe&next=<unsafe>");
	}

	@Test
	void registrationDeliveryFailuresDoNotLogRecipientOrChallenge(CapturedOutput output) {
		var accountId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var requested = new RegistrationRequested(accountId, "private@example.test", "private-challenge", correlationId);
		var delivery = mock(VerificationDelivery.class);
		doThrow(new IllegalStateException("provider unavailable")).when(delivery)
				.requestEmailVerification(any(), any(), any(), any());

		new RegistrationDeliveryListener(delivery).deliver(requested);

		assertThat(requested.toString()).contains(accountId.toString(), correlationId.toString(), "[REDACTED]")
				.doesNotContain("private@example.test", "private-challenge");
		assertThat(output).contains(accountId.toString(), correlationId.toString())
				.doesNotContain("private@example.test", "private-challenge", "provider unavailable");
	}

	@Test
	void credentialDeliveryFailuresDoNotLogRecipientChallengeOrProviderDetail(CapturedOutput output) {
		var accountId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var requested = new CredentialDeliveryRequested(accountId, "private@example.test", "private-challenge",
				CredentialDeliveryRequested.Purpose.VERIFY_EMAIL, correlationId);
		var delivery = mock(VerificationDelivery.class);
		doThrow(new IllegalStateException("provider unavailable")).when(delivery)
				.requestEmailVerification(any(), any(), any(), any());

		new CredentialDeliveryListener(delivery).deliver(requested);

		assertThat(requested.toString()).contains(accountId.toString(), correlationId.toString(), "[REDACTED]")
				.doesNotContain("private@example.test", "private-challenge");
		assertThat(output).contains(accountId.toString(), correlationId.toString(), "VERIFY_EMAIL")
				.doesNotContain("private@example.test", "private-challenge", "provider unavailable");
	}

	private VerificationDeliveryProperties properties() {
		return new VerificationDeliveryProperties(URI.create("https://api.brevo.com"), "test-key",
				"no-reply@example.test", "MentalBridge", URI.create("http://localhost:3000/verify-email"),
				URI.create("http://localhost:3000/reset-password"));
	}

}
