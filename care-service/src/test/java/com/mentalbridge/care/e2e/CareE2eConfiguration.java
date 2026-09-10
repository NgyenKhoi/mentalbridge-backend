package com.mentalbridge.care.e2e;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@TestConfiguration(proxyBeanMethods = false)
public class CareE2eConfiguration {

	public static final String USER_TOKEN = "synthetic-care-e2e-access";
	public static final String OTHER_USER_TOKEN = "synthetic-care-e2e-other-access";
	public static final String FIRST_TIME_USER_TOKEN = "synthetic-resource-e2e-access";
	public static final UUID USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000004");
	public static final UUID OTHER_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000005");
	public static final UUID FIRST_TIME_USER_ID = UUID.fromString("10000000-0000-4000-8000-000000000006");

	@Bean
	@Primary
	MutableE2eClock e2eClock() {
		return new MutableE2eClock(Instant.parse("2098-01-01T00:00:00Z"));
	}

	@Bean
	@Primary
	JwtDecoder e2eJwtDecoder() {
		return token -> switch (token) {
			case USER_TOKEN -> jwt(token, USER_ID);
			case OTHER_USER_TOKEN -> jwt(token, OTHER_USER_ID);
			case FIRST_TIME_USER_TOKEN -> jwt(token, FIRST_TIME_USER_ID);
			default -> throw new BadJwtException("E2E access token is invalid");
		};
	}

	@Bean
	ProgressFaults progressFaults() {
		return new ProgressFaults();
	}

	@Bean
	FilterRegistrationBean<OncePerRequestFilter> progressFaultFilter(ProgressFaults faults) {
		var registration = new FilterRegistrationBean<OncePerRequestFilter>();
		registration.setFilter(new ProgressFaultFilter(faults));
		registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
		return registration;
	}

	@Bean
	ApplicationRunner deterministicCareFixture(JdbcClient jdbc) {
		return arguments -> {
			seedProfile(jdbc, USER_ID, "Care E2E User");
			seedProfile(jdbc, OTHER_USER_ID, "Other E2E User");
			seedConsent(jdbc, USER_ID, "30000000-0000-4000-8000-000000000004");
			seedConsent(jdbc, OTHER_USER_ID, "30000000-0000-4000-8000-000000000005");
		};
	}

	private static void seedProfile(JdbcClient jdbc, UUID userId, String displayName) {
		jdbc.sql("""
				insert into user_profile (
				    account_id, display_name, date_of_birth, gender, locale, timezone,
				    reminder_enabled, created_at, updated_at, version
				) values (:userId, :displayName, date '2000-01-01', null, 'vi-VN',
				          'Asia/Ho_Chi_Minh', false, :timestamp, :timestamp, 0)
				on conflict (account_id) do nothing
				""")
				.param("userId", userId)
				.param("displayName", displayName)
				.param("timestamp", OffsetDateTime.parse("2026-08-01T00:00:00Z"))
				.update();
	}

	private static void seedConsent(JdbcClient jdbc, UUID userId, String decisionId) {
		jdbc.sql("""
				insert into consent_decision (
				    id, user_id, consent_type, policy_version, granted, evidence,
				    idempotency_key, request_hash, decided_at, created_at
				) values (:decisionId, :userId, 'PRIVACY_POLICY', 'privacy-capstone-v3', true,
				          '{}'::jsonb, 'e2e-seeded-consent', repeat('a', 64), :timestamp, :timestamp)
				on conflict (id) do nothing
				""")
				.param("decisionId", UUID.fromString(decisionId))
				.param("userId", userId)
				.param("timestamp", OffsetDateTime.parse("2026-08-01T00:00:00Z"))
				.update();
	}

	private static Jwt jwt(String token, UUID subject) {
		var issuedAt = Instant.parse("2026-08-01T00:00:00Z");
		return Jwt.withTokenValue(token)
				.header("alg", "E2E_FIXTURE")
				.subject(subject.toString())
				.issuer("https://identity.e2e.mentalbridge")
				.audience(List.of("mentalbridge-e2e-api"))
				.issuedAt(issuedAt)
				.expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
				.claim("roles", List.of("USER"))
				.build();
	}

	static final class MutableE2eClock extends Clock {

		private final AtomicReference<Instant> current;

		MutableE2eClock(Instant initial) {
			this.current = new AtomicReference<>(initial);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			if (!ZoneOffset.UTC.equals(zone)) throw new IllegalArgumentException("E2E clock is UTC only");
			return this;
		}

		@Override
		public Instant instant() {
			return current.get();
		}

		void advance(Duration duration) {
			if (duration.isNegative() || duration.isZero()) {
				throw new IllegalArgumentException("E2E duration must be positive");
			}
			current.updateAndGet(value -> value.plus(duration));
		}
	}

	enum ProgressFault {
		NONE,
		TIMEOUT,
		UNAVAILABLE,
		MALFORMED
	}

	static final class ProgressFaults {

		private final AtomicReference<ProgressFault> next = new AtomicReference<>(ProgressFault.NONE);

		void set(ProgressFault fault) {
			next.set(fault);
		}

		ProgressFault consume() {
			return next.getAndSet(ProgressFault.NONE);
		}
	}

	@RestController
	@RequestMapping("/__test/care")
	static final class CareE2eController {

		private final MutableE2eClock clock;
		private final ProgressFaults faults;
		private final JdbcClient jdbc;

		CareE2eController(MutableE2eClock clock, ProgressFaults faults, JdbcClient jdbc) {
			this.clock = clock;
			this.faults = faults;
			this.jdbc = jdbc;
		}

		@PostMapping("/clock/advance")
		void advance(@RequestParam Duration duration) {
			clock.advance(duration);
		}

		@PostMapping("/progress-fault")
		void progressFault(@RequestParam ProgressFault mode) {
			faults.set(mode);
		}

		@PostMapping("/assessments/{assessmentId}/void")
		void voidAssessment(@PathVariable UUID assessmentId) {
			jdbc.sql("""
					update assessment_submission
					set voided_at = submitted_at, void_reason_code = 'E2E_VOID'
					where id = :assessmentId
					""")
					.param("assessmentId", assessmentId)
					.update();
		}

		@PostMapping("/assessments/{assessmentId}/restore")
		void restoreAssessment(@PathVariable UUID assessmentId) {
			jdbc.sql("""
					update assessment_submission
					set voided_at = null, void_reason_code = null
					where id = :assessmentId
					""")
					.param("assessmentId", assessmentId)
					.update();
		}

		@PostMapping("/assessments/{assessmentId}/scoring-version")
		void scoringVersion(@PathVariable UUID assessmentId, @RequestParam String value) {
			jdbc.sql("update assessment_result set scoring_version = :value where submission_id = :assessmentId")
					.param("value", value)
					.param("assessmentId", assessmentId)
					.update();
		}
	}

	static final class ProgressFaultFilter extends OncePerRequestFilter {

		private final ProgressFaults faults;

		ProgressFaultFilter(ProgressFaults faults) {
			this.faults = faults;
		}

		@Override
		protected boolean shouldNotFilter(HttpServletRequest request) {
			return !request.getRequestURI().matches("/api/v1/assessments/[^/]+/progress");
		}

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
				throws ServletException, IOException {
			var fault = faults.consume();
			if (fault == ProgressFault.NONE) {
				filterChain.doFilter(request, response);
				return;
			}
			if (fault == ProgressFault.TIMEOUT) {
				try {
					Thread.sleep(4_000);
				}
				catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					return;
				}
				filterChain.doFilter(request, response);
				return;
			}
			response.setCharacterEncoding("UTF-8");
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			if (fault == ProgressFault.UNAVAILABLE) {
				response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
				response.getWriter().write("""
						{"type":"/problems/care-unavailable","title":"Care is unavailable","status":503,"code":"CARE_UNAVAILABLE","correlationId":"62cda42f-b286-43c6-aa48-88ef64ff3361"}
						""");
				return;
			}
			response.setStatus(HttpServletResponse.SC_OK);
			response.getWriter().write("{\"instrument\":\"PHQ9\",\"unexpected\":true}");
		}
	}
}
