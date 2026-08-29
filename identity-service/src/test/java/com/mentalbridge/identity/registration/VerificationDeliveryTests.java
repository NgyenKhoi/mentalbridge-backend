package com.mentalbridge.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.mock.env.MockEnvironment;

import com.mentalbridge.identity.configuration.VerificationDeliveryConfigurationValidator;
import com.mentalbridge.identity.configuration.VerificationDeliveryProperties;
import com.mentalbridge.identity.configuration.VerificationDeliveryProperties.Mode;

@ExtendWith(OutputCaptureExtension.class)
class VerificationDeliveryTests {

	@TempDir
	private Path directory;

	@Test
	void localDeliveryWritesOnlyTheVerificationUrlToTheConfiguredPrivateDirectory() throws Exception {
		var accountId = UUID.randomUUID();
		var challenge = "synthetic-challenge-value-that-is-long-enough";
		var delivery = new LocalFileVerificationDelivery(properties(Mode.LOCAL_FILE));

		delivery.requestDelivery(accountId, "synthetic@example.test", challenge, UUID.randomUUID());

		var output = Files.readString(directory.resolve(accountId + ".verification-url"));
		assertThat(output).startsWith("http://localhost:3000/verify-email?challenge=").contains("synthetic-challenge-value")
				.doesNotContain("synthetic@example.test");
	}

	@Test
	void localDeliveryRequiresAnExclusiveDevelopmentProfile() {
		var local = new MockEnvironment();
		local.setActiveProfiles("local");
		assertThatCode(() -> new VerificationDeliveryConfigurationValidator(properties(Mode.LOCAL_FILE), local))
				.doesNotThrowAnyException();

		var noProfile = new MockEnvironment();
		assertThatThrownBy(
				() -> new VerificationDeliveryConfigurationValidator(properties(Mode.LOCAL_FILE), noProfile))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("local or dev profile");

		var production = new MockEnvironment();
		production.setActiveProfiles("prod");
		assertThatThrownBy(
				() -> new VerificationDeliveryConfigurationValidator(properties(Mode.LOCAL_FILE), production))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("local or dev profile");

		var mixed = new MockEnvironment();
		mixed.setActiveProfiles("local", "prod");
		assertThatThrownBy(() -> new VerificationDeliveryConfigurationValidator(properties(Mode.LOCAL_FILE), mixed))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("local or dev profile");

		var ambiguous = new MockEnvironment();
		ambiguous.setActiveProfiles("local", "staging");
		assertThatThrownBy(() -> new VerificationDeliveryConfigurationValidator(properties(Mode.LOCAL_FILE), ambiguous))
				.isInstanceOf(IllegalStateException.class).hasMessageContaining("local or dev profile");
	}

	@Test
	void localDeliveryModeBindsAndStartsOnlyWithTheLocalProfile() {
		context("local").run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context).hasSingleBean(LocalFileVerificationDelivery.class);
			assertThat(context).hasSingleBean(RegistrationDeliveryListener.class);
		});

		context("prod").run(context -> assertThat(context).hasFailed()
				.getFailure().rootCause().hasMessageContaining("local or dev profile"));
	}

	@Test
	void registrationDeliveryFailuresDoNotLogRecipientOrChallenge(CapturedOutput output) {
		var accountId = UUID.randomUUID();
		var correlationId = UUID.randomUUID();
		var requested = new RegistrationRequested(accountId, "private@example.test", "private-challenge", correlationId);
		var listener = new RegistrationDeliveryListener((ignoredAccountId, ignoredEmail, ignoredChallenge,
				ignoredCorrelationId) -> {
			throw new IllegalStateException("provider unavailable");
		});

		listener.deliver(requested);

		assertThat(requested.toString()).contains(accountId.toString(), correlationId.toString(), "[REDACTED]")
				.doesNotContain("private@example.test", "private-challenge");
		assertThat(output).contains(accountId.toString(), correlationId.toString())
				.doesNotContain("private@example.test", "private-challenge", "provider unavailable");
	}

	private VerificationDeliveryProperties properties(Mode mode) {
		return new VerificationDeliveryProperties(mode, URI.create("https://api.brevo.com"), "test-key",
				"no-reply@example.test", "MentalBridge", URI.create("http://localhost:3000/verify-email"), directory);
	}

	private ApplicationContextRunner context(String profile) {
		return new ApplicationContextRunner().withUserConfiguration(DeliveryTestConfiguration.class)
				.withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
				.withPropertyValues("mentalbridge.identity.verification-delivery.mode=local-file",
						"mentalbridge.identity.verification-delivery.verification-url=http://localhost:3000/verify-email",
						"mentalbridge.identity.verification-delivery.local-directory=" + directory);
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(VerificationDeliveryProperties.class)
	@Import({ VerificationDeliveryConfigurationValidator.class, LocalFileVerificationDelivery.class,
			RegistrationDeliveryListener.class })
	static class DeliveryTestConfiguration {
	}

}
