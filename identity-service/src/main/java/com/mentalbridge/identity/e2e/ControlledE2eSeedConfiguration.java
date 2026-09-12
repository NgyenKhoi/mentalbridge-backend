package com.mentalbridge.identity.e2e;

import java.time.Instant;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.security.BCryptPasswordHasher;

/**
 * Seeds only synthetic accounts when the explicit local E2E profile is enabled.
 */
@Configuration
@Profile("e2e")
public class ControlledE2eSeedConfiguration {

	@Bean
	CommandLineRunner seedControlledAccounts(AccountRepository accounts, BCryptPasswordHasher hasher,
			PlatformTransactionManager transactionManager,
			@Value("${IDENTITY_E2E_SEED:false}") boolean seedEnabled,
			@Value("${IDENTITY_E2E_USER_A_EMAIL:e2e-user-a@synthetic.invalid}") String userAEmail,
			@Value("${IDENTITY_E2E_USER_B_EMAIL:e2e-user-b@synthetic.invalid}") String userBEmail,
			@Value("${IDENTITY_E2E_PASSWORD:}") String password) {
		return ignored -> {
			if (!seedEnabled) {
				return;
			}
			if (password.isBlank()) {
				throw new IllegalStateException("IDENTITY_E2E_PASSWORD is required when IDENTITY_E2E_SEED=true");
			}

			var transaction = new TransactionTemplate(transactionManager);
			transaction.executeWithoutResult(status -> {
				seedAccount(accounts, hasher, userAEmail, password);
				seedAccount(accounts, hasher, userBEmail, password);
			});
		};
	}

	private void seedAccount(AccountRepository accounts, BCryptPasswordHasher hasher, String email,
			String password) {
		String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
		if (normalizedEmail.isBlank() || !normalizedEmail.endsWith("@synthetic.invalid")) {
			throw new IllegalArgumentException("Controlled E2E accounts must use the @synthetic.invalid domain");
		}

		if (accounts.findByEmailForUpdate(normalizedEmail).isPresent()) {
			return;
		}

		Instant now = Instant.now();
		AccountEntity account = AccountEntity.pending(normalizedEmail, hasher.hash(password), RoleCode.USER, now);
		account.activate(now);
		if (account.status() != AccountStatus.ACTIVE) {
			throw new IllegalStateException("Synthetic account activation failed");
		}
		accounts.save(account);
	}
}
