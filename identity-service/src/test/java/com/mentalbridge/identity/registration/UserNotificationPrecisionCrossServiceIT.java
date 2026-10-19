package com.mentalbridge.identity.registration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.account.AccountEntity;
import com.mentalbridge.identity.account.AccountRepository;
import com.mentalbridge.identity.account.AccountStateChangeRequest;
import com.mentalbridge.identity.account.AccountStateReasonCode;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.account.AdminAccountService;
import com.mentalbridge.identity.account.RoleCode;
import com.mentalbridge.identity.account.SecurityAuditEventRepository;
import com.mentalbridge.identity.authentication.AuthenticationPersistence;
import com.mentalbridge.identity.configuration.OutboxRelayProperties;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserNotificationPrecisionCrossServiceIT extends IdentityTestProperties {

	private static final String TOPIC = "mentalbridge.identity.account-lifecycle.v1";

	@Autowired RegistrationPersistence registration;
	@Autowired OutboxEventRepository outbox;
	@Autowired OutboxRelayPersistence persistence;
	@Autowired AccountRepository accounts;
	@Autowired AuthenticationPersistence authentication;
	@Autowired SecurityAuditEventRepository audit;
	@Autowired TransactionTemplate transactions;
	@Autowired KafkaContainer kafkaContainer;
	@Autowired KafkaTemplate<String, String> kafka;
	@Autowired ObjectMapper json;
	@Autowired JdbcClient jdbc;

	@Test
	void activationAndRestorationSurvivePostgresRelayKafkaAndStrictContentValidation() throws Exception {
		try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				kafkaContainer.getBootstrapServers()))) {
			admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1))).all().get(30, TimeUnit.SECONDS);
		}
		var adminId = transactions.execute(status -> accounts.saveAndFlush(AccountEntity.pending(
				"admin-precision@example.test", "test-hash", RoleCode.ADMIN,
				Instant.parse("2026-10-19T07:00:00Z"))).id());
		for (var time : List.of("2026-10-19T08:00:00.123456789Z",
				"2026-10-19T08:00:00.123456999Z", "2026-10-19T08:00:00.999999999Z")) {
			var original = Instant.parse(time);
			var accountId = transactions.execute(status -> {
				var tokenHash = UUID.randomUUID().toString().replace("-", "").repeat(2);
				var account = registration.create(UUID.randomUUID() + "@example.test", "test-hash",
						RoleCode.USER, tokenHash, original.plusSeconds(3600), UUID.randomUUID(), original.minusSeconds(1));
				var verification = registration.findVerificationByTokenHash(tokenHash).orElseThrow();
				registration.activate(account.accountId(), verification.tokenId(), UUID.randomUUID(), original);
				return account.accountId();
			});
			var administration = new AdminAccountService(accounts, authentication, audit, outbox,
					Clock.fixed(original, ZoneOffset.UTC));
			transactions.executeWithoutResult(status -> administration.changeAccountState(adminId, accountId,
					new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.ACCOUNT_REVIEW_REQUIRED),
					1, UUID.randomUUID()));
			transactions.executeWithoutResult(status -> administration.changeAccountState(adminId, accountId,
					new AccountStateChangeRequest(AccountStatus.ACTIVE, AccountStateReasonCode.REVIEW_COMPLETED),
					2, UUID.randomUUID()));
			var persisted = jdbc.sql("""
					select occurred_at from outbox_event
					where aggregate_id = :id and message_type = 'identity.user-notification.fact'
					""").param("id", accountId).query((row, index) -> row.getTimestamp(1).toInstant()).list();
			assertThat(persisted).hasSize(2).containsOnly(original.truncatedTo(ChronoUnit.MICROS));
		}
		var properties = new OutboxRelayProperties(true, TOPIC, 100, Duration.ofSeconds(5),
				Duration.ofSeconds(5), Duration.ofSeconds(1), Duration.ofSeconds(5));
		new OutboxRelay(persistence, properties, kafka, json).publishDue();
		assertThat(jdbc.sql("""
				select count(*) from outbox_event
				where message_type = 'identity.user-notification.fact' and published_at is not null
				""").query(Long.class).single()).isEqualTo(6);
		verifyContent(kafkaContainer.getBootstrapServers(), "identity", 6);
	}

	private void verifyContent(String broker, String owner, int expected) throws Exception {
		var directory = Path.of("..", "content-notification-service").toAbsolutePath().normalize();
		var log = Files.createTempFile("mb565-precision-", ".log");
		var process = new ProcessBuilder("node", "scripts/verify-user-notification-precision.mjs",
				owner, broker, Integer.toString(expected)).directory(directory.toFile())
				.redirectErrorStream(true).redirectOutput(log.toFile()).start();
		try {
			assertThat(process.waitFor(180, TimeUnit.SECONDS)).as("Content verification timeout").isTrue();
			assertThat(process.exitValue()).withFailMessage(Files.readString(log)).isZero();
		}
		finally {
			if (process.isAlive()) process.destroyForcibly();
			Files.deleteIfExists(log);
		}
	}

}
