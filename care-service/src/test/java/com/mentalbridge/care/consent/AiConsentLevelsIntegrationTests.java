package com.mentalbridge.care.consent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.mentalbridge.care.CareTestProperties;
import com.mentalbridge.care.TestcontainersConfiguration;
import com.mentalbridge.care.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class AiConsentLevelsIntegrationTests extends CareTestProperties {
	private static final Instant NOW = Instant.parse("2026-11-06T04:00:00Z");
	@MockitoSpyBean ConsentService service;
	@Autowired JdbcClient jdbc;
	@MockitoBean Clock clock;

	@BeforeEach
	void freezeClock() {
		when(clock.instant()).thenReturn(NOW);
	}

	@Test
	void legacyReplayPrecedesPolicyValidationAndNeverRestoresOrRewritesHistory() throws Exception {
		var owner = profile();
		var key = "synthetic-historical-ai-key";
		var command = new ConsentService.DecisionCommand("AI_PROCESSING", "ai-processing-capstone-v1", true);
		var hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
				"AI_PROCESSING|ai-processing-capstone-v1|true".getBytes(StandardCharsets.UTF_8)));
		var oldId = UUID.randomUUID();
		jdbc.sql("""
				insert into consent_decision(id,user_id,consent_type,policy_version,granted,idempotency_key,request_hash,decided_at)
				values(:id,:owner,'AI_PROCESSING','ai-processing-capstone-v1',true,:key,:hash,:at)
				""").param("id", oldId).param("owner", owner).param("key", key).param("hash", hash)
				.param("at", Timestamp.from(NOW.minusSeconds(100))).update();
		var original = jdbc.sql("select row_to_json(c)::text from consent_decision c where id=:id").param("id", oldId).query(String.class).single();
		assertThat(service.record(owner, key, command).decisionId()).isEqualTo(oldId);
		assertThat(service.authorizeAiPurposes(owner).chat().reason()).isEqualTo("POLICY_OUTDATED");
		assertThatThrownBy(() -> service.record(owner, "synthetic-new-legacy-key", command))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_PROCESSING_DISCLOSURE_REQUIRED"));
		assertThatThrownBy(() -> service.record(owner, key, chat(true)))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
		service.record(owner, "synthetic-current-chat-grant", chat(true));
		service.record(owner, "synthetic-current-chat-revoke", chat(false));
		assertThat(service.record(owner, key, command).policyVersion()).isEqualTo("ai-processing-capstone-v1");
		assertThat(service.authorizeAiPurposes(owner).chat().reason()).isEqualTo("REVOKED");
		assertThat(jdbc.sql("select row_to_json(c)::text from consent_decision c where id=:id").param("id", oldId).query(String.class).single()).isEqualTo(original);
		assertThat(count(owner)).isEqualTo(3);
	}

	@Test
	void suspensionAndRegrantKeepTheOriginalContextDecisionAndExactReplayDoesNotRegrant() {
		var owner = profile();
		assertThatThrownBy(() -> service.record(owner, "synthetic-context-before-chat", context(true)))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("AI_PROCESSING_CONSENT_REQUIRED"));
		assertThat(count(owner)).isZero();
		service.record(owner, "synthetic-chat-first-grant", chat(true));
		var context = service.record(owner, "synthetic-context-first-grant", context(true));
		var before = jdbc.sql("select row_to_json(c)::text from consent_decision c where id=:id").param("id", context.decisionId()).query(String.class).single();
		service.record(owner, "synthetic-chat-withdrawal", chat(false));
		var suspended = service.authorizeAiPurposes(owner);
		assertThat(suspended.chat().reason()).isEqualTo("REVOKED");
		assertThat(suspended.context().reason()).isEqualTo("DEPENDENCY_REQUIRED");
		assertThat(service.record(owner, "synthetic-context-first-grant", context(true))).isEqualTo(context);
		assertThat(service.authorizeAiContextProcessing(owner).authorized()).isFalse();
		when(clock.instant()).thenReturn(NOW.minusSeconds(60));
		service.record(owner, "synthetic-chat-regrant", chat(true));
		assertThat(service.authorizeAiContextProcessing(owner).authorized()).isTrue();
		assertThat(service.authorizeAiContextProcessing(owner).decidedAt()).isEqualTo(context.decidedAt());
		assertThat(jdbc.sql("select row_to_json(c)::text from consent_decision c where id=:id").param("id", context.decisionId()).query(String.class).single()).isEqualTo(before);
		service.record(owner, "synthetic-context-withdrawal", context(false));
		assertThat(service.authorizeAiPurposes(owner).context().reason()).isEqualTo("REVOKED");
		assertThat(service.record(owner, "synthetic-context-first-grant", context(true))).isEqualTo(context);
		assertThat(service.authorizeAiContextProcessing(owner).authorized()).isFalse();
		var times = jdbc.sql("select decided_at from consent_decision where user_id=:owner order by decided_at")
				.param("owner", owner).query((rs, row) -> rs.getTimestamp(1).toInstant()).list();
		assertThat(times).containsExactly(NOW, NOW.plusNanos(1_000), NOW.plusNanos(2_000), NOW.plusNanos(3_000), NOW.plusNanos(4_000));
	}

	@Test
	void concurrentExactRetriesAppendOneDecisionAndChangedIntentConflicts() throws Exception {
		var owner = profile();
		try (var pool = Executors.newFixedThreadPool(6)) {
			var calls = new ArrayList<Callable<ConsentService.DecisionView>>();
			for (int i = 0; i < 6; i++) calls.add(() -> service.record(owner, "synthetic-parallel-retry-key", chat(true)));
			var results = pool.invokeAll(calls);
			var first = results.getFirst().get();
			for (var result : results) assertThat(result.get()).isEqualTo(first);
		}
		assertThat(count(owner)).isEqualTo(1);
		assertThatThrownBy(() -> service.record(owner, "synthetic-parallel-retry-key", chat(false)))
				.isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
	}

	@Test
	void concurrentContextGrantAndChatWithdrawalCannotLeaveEffectiveContext() throws Exception {
		var owner = profile();
		service.record(owner, "synthetic-race-initial-chat", chat(true));
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(2)) {
			var grant = pool.submit(() -> {
				start.await();
				try {
					service.record(owner, "synthetic-race-context-grant", context(true));
					return "GRANTED";
				}
				catch (ApiException error) { return error.code(); }
			});
			var revoke = pool.submit(() -> {
				start.await();
				return service.record(owner, "synthetic-race-chat-revoke", chat(false));
			});
			start.countDown();
			assertThat(grant.get(15, TimeUnit.SECONDS)).isIn("GRANTED", "AI_PROCESSING_CONSENT_REQUIRED");
			assertThat(revoke.get(15, TimeUnit.SECONDS).granted()).isFalse();
		}
		var result = service.authorizeAiPurposes(owner);
		assertThat(result.chat().reason()).isEqualTo("REVOKED");
		assertThat(result.context().authorized()).isFalse();
		assertThat(result.context().reason()).isIn("MISSING", "DEPENDENCY_REQUIRED");
		assertThat(count(owner)).isBetween(2L, 3L);
	}

	@Test
	void concurrentGrantRevokeWithBackwardClockKeepEveryDecisionAndAtomicSnapshots() throws Exception {
		var owner = profile();
		service.record(owner, "synthetic-snapshot-chat-initial", chat(true));
		service.record(owner, "synthetic-snapshot-context-initial", context(true));
		when(clock.instant()).thenReturn(NOW.minusSeconds(120));
		var start = new CountDownLatch(1);
		try (var pool = Executors.newFixedThreadPool(3)) {
			var writes = new ArrayList<java.util.concurrent.Future<?>>();
			for (int index = 0; index < 2; index++) {
				var worker = index;
				writes.add(pool.submit(() -> {
					start.await();
					for (int i = 0; i < 12; i++) service.record(owner, "synthetic-snapshot-" + worker + "-" + i, chat(i % 2 == 0));
					return null;
				}));
			}
			var snapshots = pool.submit(() -> {
				start.await();
				for (int i = 0; i < 60; i++) {
					var snapshot = service.authorizeAiPurposes(owner);
					assertThat(snapshot.context().authorized()).isEqualTo(snapshot.chat().authorized());
					assertThat(snapshot.context().reason()).isIn("GRANTED", "DEPENDENCY_REQUIRED");
				}
				return null;
			});
			start.countDown();
			for (var write : writes) write.get(30, TimeUnit.SECONDS);
			snapshots.get(30, TimeUnit.SECONDS);
		}
		assertThat(count(owner)).isEqualTo(26);
		assertThat(jdbc.sql("select count(distinct decided_at) from consent_decision where user_id=:owner").param("owner", owner).query(Long.class).single()).isEqualTo(26);
		var latest = jdbc.sql("select granted from consent_decision where user_id=:owner and consent_type='AI_PROCESSING' order by decided_at desc,id desc limit 1")
				.param("owner", owner).query(Boolean.class).single();
		assertThat(service.authorizeAiProcessing(owner).authorized()).isEqualTo(latest);
	}

	@Test
	void snapshotRetainsOneDatabaseViewWhenBothLevelsChangeBetweenItsQueries() throws Exception {
		var owner = profile();
		service.record(owner, "synthetic-atomic-chat-initial", chat(true));
		var originalContext = service.record(owner, "synthetic-atomic-context-initial", context(true));
		var chatRead = new CountDownLatch(1);
		var allowContextRead = new CountDownLatch(1);
		doAnswer(invocation -> {
			var result = invocation.callRealMethod();
			chatRead.countDown();
			if (!allowContextRead.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Synthetic snapshot synchronization timed out");
			return result;
		}).when(service).authorizeAiProcessing(owner);
		try (var pool = Executors.newSingleThreadExecutor()) {
			var snapshot = pool.submit(() -> service.authorizeAiPurposes(owner));
			try {
				assertThat(chatRead.await(15, TimeUnit.SECONDS)).isTrue();
				service.record(owner, "synthetic-atomic-chat-revoked", chat(false));
				service.record(owner, "synthetic-atomic-context-revoked", context(false));
			}
			finally { allowContextRead.countDown(); }
			var originalSnapshot = snapshot.get(15, TimeUnit.SECONDS);
			assertThat(originalSnapshot.chat().authorized()).isTrue();
			assertThat(originalSnapshot.context().authorized()).isTrue();
			assertThat(originalSnapshot.context().decidedAt()).isEqualTo(originalContext.decidedAt());
		}
		var current = service.authorizeAiPurposes(owner);
		assertThat(current.chat().reason()).isEqualTo("REVOKED");
		assertThat(current.context().reason()).isEqualTo("REVOKED");
	}

	private UUID profile() {
		var owner = UUID.randomUUID();
		jdbc.sql("insert into user_profile(account_id,display_name) values(:owner,'Synthetic consent user')").param("owner", owner).update();
		return owner;
	}

	private long count(UUID owner) {
		return jdbc.sql("select count(*) from consent_decision where user_id=:owner").param("owner", owner).query(Long.class).single();
	}

	private ConsentService.DecisionCommand chat(boolean granted) {
		return new ConsentService.DecisionCommand("AI_PROCESSING", AiProcessingDisclosureService.VERSION, granted);
	}

	private ConsentService.DecisionCommand context(boolean granted) {
		return new ConsentService.DecisionCommand("AI_CONTEXT_PROCESSING", AiContextProcessingDisclosureService.VERSION, granted);
	}
}
