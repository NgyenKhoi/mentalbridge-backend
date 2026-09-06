package com.mentalbridge.care;

import java.security.KeyPairGenerator;
import java.util.Base64;

import org.springframework.boot.SpringApplication;

import com.mentalbridge.care.e2e.CareE2eConfiguration;

public class TestCareServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(CareServiceApplication::main)
				.with(TestcontainersConfiguration.class, CareE2eConfiguration.class)
				.run(arguments(args));
	}

	private static String[] arguments(String[] supplied) {
		var defaults = new String[] {
				"--mentalbridge.care.jwt.issuer=https://identity.e2e.mentalbridge",
				"--mentalbridge.care.jwt.audience=mentalbridge-e2e-api",
				"--mentalbridge.care.jwt.public-key=" + publicKey(),
				"--mentalbridge.care.assessment.anonymous-session-ttl=PT30M",
				"--mentalbridge.care.assessment.anonymous-session-maximum-lifetime=PT2H",
				"--mentalbridge.care.assessment.phq9-safety-policy-version=MB-SAFETY-PHQ9-001-e2e-v1",
				"--mentalbridge.care.assessment.idempotency-hmac-key=e2e-only-idempotency-hmac-key-at-least-32-characters",
				"--spring.liquibase.enabled=true",
				"--spring.cloud.discovery.enabled=false",
				"--eureka.client.enabled=false",
				"--springdotenv.enabled=false"
		};
		var arguments = new String[defaults.length + supplied.length];
		System.arraycopy(defaults, 0, arguments, 0, defaults.length);
		System.arraycopy(supplied, 0, arguments, defaults.length, supplied.length);
		return arguments;
	}

	private static String publicKey() {
		try {
			var generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(2048);
			return Base64.getEncoder().encodeToString(generator.generateKeyPair().getPublic().getEncoded());
		}
		catch (Exception exception) {
			throw new IllegalStateException("Unable to create the E2E public key", exception);
		}
	}

}
