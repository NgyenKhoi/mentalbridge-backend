package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.mentalbridge.care.configuration.SupportPlanCompanionAdmissionProperties;
import com.mentalbridge.care.supportplan.SupportPlanCompanionAdmissionGuard.Decision;
import com.mentalbridge.care.supportplan.SupportPlanCompanionAdmissionGuard.Permit;

@Testcontainers
class SupportPlanCompanionAdmissionGuardTests {

	@Container
	private static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
			.withExposedPorts(6379);

	private static LettuceConnectionFactory connectionFactory;
	private StringRedisTemplate redis;
	private SupportPlanCompanionAdmissionProperties properties;

	@BeforeEach
	void setUp() {
		if (connectionFactory == null) {
			var configuration = new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
			connectionFactory = new LettuceConnectionFactory(configuration);
			connectionFactory.afterPropertiesSet();
			connectionFactory.start();
		}
		redis = new StringRedisTemplate(connectionFactory);
		redis.afterPropertiesSet();
		redis.getConnectionFactory().getConnection().serverCommands().flushAll();
		properties = new SupportPlanCompanionAdmissionProperties(true, "companion-admission-v1", 6,
				Duration.ofMinutes(1), Duration.ofSeconds(12), "test:companion");
	}

	@AfterAll
	static void closeConnection() {
		if (connectionFactory != null) connectionFactory.destroy();
	}

	@Test
	void admitsOnlyOneConcurrentRequestAcrossGuardInstances() throws Exception {
		UUID userId = UUID.randomUUID();
		var firstInstance = new SupportPlanCompanionAdmissionGuard(redis, properties);
		var secondInstance = new SupportPlanCompanionAdmissionGuard(redis, properties);
		try (var executor = Executors.newFixedThreadPool(10)) {
			var tasks = IntStream.range(0, 10)
					.mapToObj(index -> (Callable<Permit>) () ->
							(index % 2 == 0 ? firstInstance : secondInstance).acquire(userId))
					.toList();
			var permits = executor.invokeAll(tasks).stream().map(future -> {
				try {
					return future.get();
				}
				catch (Exception exception) {
					throw new IllegalStateException(exception);
				}
			}).toList();
			assertThat(permits).extracting(Permit::decision).containsExactlyInAnyOrder(
					Decision.ADMITTED, Decision.PENDING, Decision.PENDING, Decision.PENDING, Decision.PENDING,
					Decision.PENDING, Decision.PENDING, Decision.PENDING, Decision.PENDING, Decision.PENDING);
			permits.forEach(Permit::close);
		}
	}

	@Test
	void enforcesTheSharedPerUserBurstCapWithoutBlockingAnotherUser() {
		var guard = new SupportPlanCompanionAdmissionGuard(redis, properties);
		UUID firstUser = UUID.randomUUID();
		for (int index = 0; index < 6; index++) {
			try (var permit = guard.acquire(firstUser)) {
				assertThat(permit.decision()).isEqualTo(Decision.ADMITTED);
			}
		}
		assertThat(guard.acquire(firstUser).decision()).isEqualTo(Decision.RATE_LIMITED);
		try (var permit = guard.acquire(UUID.randomUUID())) {
			assertThat(permit.decision()).isEqualTo(Decision.ADMITTED);
		}
	}
}
