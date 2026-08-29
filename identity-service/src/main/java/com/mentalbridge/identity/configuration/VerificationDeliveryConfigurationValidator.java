package com.mentalbridge.identity.configuration;

import java.util.Set;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import com.mentalbridge.identity.configuration.VerificationDeliveryProperties.Mode;

@Component
public class VerificationDeliveryConfigurationValidator {

	public VerificationDeliveryConfigurationValidator(VerificationDeliveryProperties properties,
			Environment environment) {
		var activeProfiles = Set.of(environment.getActiveProfiles());
		var exclusivelyDevelopment = activeProfiles.equals(Set.of("local")) || activeProfiles.equals(Set.of("dev"));
		if (properties.mode() == Mode.LOCAL_FILE && !exclusivelyDevelopment) {
			throw new IllegalStateException("Local verification delivery requires an exclusive local or dev profile");
		}
	}

}
