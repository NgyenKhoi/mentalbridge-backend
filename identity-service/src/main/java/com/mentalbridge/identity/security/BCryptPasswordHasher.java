package com.mentalbridge.identity.security;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

@Component
public class BCryptPasswordHasher {

	private static final int COST_FACTOR = 12;

	private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(COST_FACTOR);

	public String hash(String password) {
		return encoder.encode(password);
	}

	public boolean matches(String password, String encodedPassword) {
		try {
			return encoder.matches(password, encodedPassword);
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}

}
