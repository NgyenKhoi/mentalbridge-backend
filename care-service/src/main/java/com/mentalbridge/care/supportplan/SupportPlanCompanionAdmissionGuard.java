package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.configuration.SupportPlanCompanionAdmissionProperties;

@Component
public class SupportPlanCompanionAdmissionGuard {

	private static final Logger LOGGER = LoggerFactory.getLogger(SupportPlanCompanionAdmissionGuard.class);
	private static final DefaultRedisScript<Long> ACQUIRE = new DefaultRedisScript<>("""
			if redis.call('EXISTS', KEYS[1]) == 1 then return 2 end
			local count = tonumber(redis.call('GET', KEYS[2]) or '0')
			if count >= tonumber(ARGV[3]) then return 3 end
			redis.call('SET', KEYS[1], ARGV[1], 'PX', ARGV[2])
			if count == 0 then
			  redis.call('SET', KEYS[2], '1', 'PX', ARGV[4])
			else
			  redis.call('INCR', KEYS[2])
			end
			return 1
			""", Long.class);
	private static final DefaultRedisScript<Long> RELEASE = new DefaultRedisScript<>("""
			if redis.call('GET', KEYS[1]) == ARGV[1] then
			  return redis.call('DEL', KEYS[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;
	private final SupportPlanCompanionAdmissionProperties properties;

	public SupportPlanCompanionAdmissionGuard(StringRedisTemplate redis,
			SupportPlanCompanionAdmissionProperties properties) {
		this.redis = redis;
		this.properties = properties;
	}

	public Permit acquire(UUID userId) {
		if (!properties.enabled()) return Permit.unavailable();
		String token = UUID.randomUUID().toString();
		String userPrefix = properties.keyPrefix() + ":" + properties.policyVersion() + ":{" + userId + "}:";
		long windowMillis = properties.window().toMillis();
		long bucket = System.currentTimeMillis() / windowMillis;
		String pendingKey = userPrefix + "pending";
		String rateKey = userPrefix + "window:" + bucket;
		try {
			Long result = redis.execute(ACQUIRE, List.of(pendingKey, rateKey), token,
					Long.toString(properties.pendingTtl().toMillis()), Integer.toString(properties.maximumRequests()),
					Long.toString(windowMillis));
			if (Long.valueOf(1).equals(result)) return Permit.admitted(() -> release(pendingKey, token));
			if (Long.valueOf(2).equals(result)) return Permit.pending();
			if (Long.valueOf(3).equals(result)) return Permit.rateLimited();
			return Permit.unavailable();
		}
		catch (RuntimeException exception) {
			LOGGER.warn("support_plan_companion_admission_unavailable policyVersion={}", properties.policyVersion());
			return Permit.unavailable();
		}
	}

	private void release(String pendingKey, String token) {
		try {
			redis.execute(RELEASE, List.of(pendingKey), token);
		}
		catch (RuntimeException ignored) {
			LOGGER.warn("support_plan_companion_admission_release_failed policyVersion={}", properties.policyVersion());
		}
	}

	public enum Decision {
		ADMITTED, PENDING, RATE_LIMITED, UNAVAILABLE
	}

	public static final class Permit implements AutoCloseable {
		private final Decision decision;
		private final Runnable release;

		private Permit(Decision decision, Runnable release) {
			this.decision = decision;
			this.release = release;
		}

		public static Permit admitted(Runnable release) {
			return new Permit(Decision.ADMITTED, release);
		}

		public static Permit pending() {
			return new Permit(Decision.PENDING, () -> { });
		}

		public static Permit rateLimited() {
			return new Permit(Decision.RATE_LIMITED, () -> { });
		}

		public static Permit unavailable() {
			return new Permit(Decision.UNAVAILABLE, () -> { });
		}

		public Decision decision() {
			return decision;
		}

		@Override
		public void close() {
			release.run();
		}
	}
}
