package com.mentalbridge.consultation.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@EnabledIfSystemProperty(named = "mb384.cloud", matches = "true")
@Import({TestcontainersConfiguration.class, LiveKitCloudJourneyTests.TimeFixture.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"server.address=127.0.0.1", "logging.level.root=WARN", "debug=false",
		"logging.level.org.springframework=WARN", "logging.level.org.hibernate=WARN",
		"mentalbridge.consultation.availability.video-enabled=true",
		"mentalbridge.consultation.video.enabled=true"
})
class LiveKitCloudJourneyTests extends ConsultationTestProperties {

	@LocalServerPort int port;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired JourneyClock clock;
	@Autowired VideoProvider provider;
	private final UUID userId = UUID.randomUUID();
	private final UUID specialistId = UUID.randomUUID();
	private final AtomicReference<UUID> appointmentId = new AtomicReference<>();
	private Map<String, String> tokens;

	@DynamicPropertySource
	static void liveKit(DynamicPropertyRegistry registry) throws Exception {
		var root = Path.of(System.getProperty("mb384.backend", "..")).toAbsolutePath().normalize();
		var lines = Files.readAllLines(root.resolve(".env"));
		for (var name : List.of("LIVEKIT_URL", "LIVEKIT_API_KEY", "LIVEKIT_API_SECRET")) {
			var value = System.getenv(name);
			if (value == null || value.isBlank()) {
				value = lines.stream().map(String::trim).filter(line -> line.startsWith(name + "="))
						.reduce((first, last) -> last).orElseThrow(() -> new IllegalStateException("Missing LiveKit operator configuration"))
						.substring(name.length() + 1).trim();
				if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
						|| (value.startsWith("'") && value.endsWith("'")))) value = value.substring(1, value.length() - 1);
			}
			var configured = value;
			var property = switch (name) {
				case "LIVEKIT_URL" -> "url";
				case "LIVEKIT_API_KEY" -> "api-key";
				default -> "api-secret";
			};
			registry.add("mentalbridge.consultation.video." + property, () -> configured);
		}
	}

	@Test
	void realCloudMediaAndSignedAttendanceUsePublicAppointmentContracts() throws Exception {
		var frontend = Path.of(System.getProperty("mb384.frontend")).toAbsolutePath().normalize();
		assertThat(Files.isRegularFile(frontend.resolve("scripts/run-livekit-cloud-journey.mjs"))).isTrue();
		tokens = Map.of("USER", signedTestToken(userId, "USER"), "SPECIALIST", signedTestToken(specialistId, "SPECIALIST"));
		// Prerequisites only, in Testcontainers-owned storage. Booking, acceptance,
		// access, completion and settlement below use the production HTTP contracts.
		jdbc.sql("""
				insert into specialist_profile(account_id,display_name,biography,years_experience,timezone,
				approval_status,submitted_at,reviewed_at,reviewed_by)
				values (:id,'Chuyên gia thử nghiệm','Synthetic non-clinical LiveKit fixture',5,'Asia/Ho_Chi_Minh',
				'APPROVED',:at,:at,:admin)
				""").param("id", specialistId).param("at", java.sql.Timestamp.from(clock.instant()))
				.param("admin", UUID.randomUUID()).update();
		jdbc.sql("""
				insert into current_service_entitlement(account_id,package_code,source,source_reference,effective_from,effective_until,policy_version)
				values (:id,'PLUS','PAID',:ref,:start,:end,'service-entitlement-v1')
				""").param("id", userId).param("ref", "mb384-disposable-" + userId)
				.param("start", java.sql.Timestamp.from(clock.instant().minusSeconds(60)))
				.param("end", java.sql.Timestamp.from(clock.instant().plus(Duration.ofDays(30)))).update();
		var control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var fixtureKey = UUID.randomUUID().toString();
		control.createContext("/api/v1/account", this::account);
		control.createContext("/fixture/", exchange -> {
			if (!fixtureKey.equals(exchange.getRequestHeaders().getFirst("X-Fixture-Key"))) { respond(exchange, 403, Map.of()); return; }
			try {
				var input = json.readTree(exchange.getRequestBody());
				var output = switch (exchange.getRequestURI().getPath()) {
					case "/fixture/bootstrap" -> Map.of("tokens", tokens, "start", Instant.now().minusSeconds(30).truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString());
					case "/fixture/activate" -> {
						var id = UUID.fromString(input.get("appointmentId").asText());
						assertThat(jdbc.sql("select status from appointment where id=:id and user_account_id=:user and specialist_account_id=:specialist")
								.param("id", id).param("user", userId).param("specialist", specialistId).query(String.class).single()).isEqualTo("CONFIRMED");
						appointmentId.set(id);
						clock.offset.set(Duration.ZERO);
						yield Map.of("active", true);
					}
					case "/fixture/evidence" -> {
						var id = appointmentId.get();
						assertThat(id).isNotNull();
						yield Map.of("signedEvents", jdbc.sql("select count(*) from appointment_video_event where appointment_id=:id").param("id", id).query(Long.class).single(),
								"room", jdbc.sql("select state,coalesce(failure_reason,'NONE') as reason from appointment_video_room where appointment_id=:id")
								.param("id", id).query((row, ignored) -> Map.of("state", row.getString("state"), "reason", row.getString("reason"))).optional().orElse(Map.of()),
								"events", jdbc.sql("select event_type,count(*) as total from appointment_video_event where appointment_id=:id group by event_type")
								.param("id", id).query((row, ignored) -> Map.of("type", row.getString("event_type"), "count", row.getLong("total"))).list());
					}
					default -> Map.of("code", "UNKNOWN_FIXTURE_ACTION");
				};
				respond(exchange, 200, output);
			}
			catch (Exception failure) { respond(exchange, 500, Map.of("code", "LIVEKIT_FIXTURE_FAILED")); }
		});
		control.start();
		Process browser = null;
		try {
			var output = Path.of("target", "mb384-livekit-browser.log");
			browser = new ProcessBuilder("node", "scripts/run-livekit-cloud-journey.mjs").directory(frontend.toFile())
					.redirectErrorStream(true).redirectOutput(output.toFile()).start();
			try (var input = browser.getOutputStream()) {
				input.write(json.writeValueAsBytes(Map.of("consultation", "http://127.0.0.1:" + port,
						"fixture", "http://127.0.0.1:" + control.getAddress().getPort(), "key", fixtureKey,
						"webhookPort", 3188, "requireWebhook", Boolean.getBoolean("mb384.webhook"),
						"backend", Path.of(System.getProperty("mb384.backend", "..")).toAbsolutePath().normalize().toString())));
			}
			assertThat(browser.waitFor(20, TimeUnit.MINUTES)).as("Controlled Cloud journey deadline").isTrue();
			assertThat(browser.exitValue()).as(Files.readString(output)).isZero();
			if (Boolean.getBoolean("mb384.webhook")) {
				var id = appointmentId.get();
				assertThat(jdbc.sql("select status from appointment where id=:id").param("id", id).query(String.class).single()).isEqualTo("COMPLETED");
				assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", id).query(Long.class).single()).isOne();
				assertThat(jdbc.sql("select count(*) from service_credit_ledger where appointment_id=:id and event_type='CONSUMED'").param("id", id).query(Long.class).single()).isOne();
			}
		}
		finally {
			if (appointmentId.get() != null) {
				var mapping = jdbc.sql("select room_name,user_identity,specialist_identity from appointment_video_room where appointment_id=:id and state <> 'CLOSED'")
						.param("id", appointmentId.get()).query((row, ignored) -> new String[]{row.getString(1), row.getString(2), row.getString(3)}).optional();
				if (mapping.isPresent()) {
					var owned = mapping.get();
					provider.closeRoom(owned[0], List.of(owned[1], owned[2]), Instant.now());
				}
			}
			if (browser != null && browser.isAlive()) { browser.descendants().forEach(ProcessHandle::destroyForcibly); browser.destroyForcibly(); }
			control.stop(0);
		}
	}

	private void account(HttpExchange exchange) throws java.io.IOException {
		var authorization = exchange.getRequestHeaders().getFirst("Authorization");
		var role = tokens.entrySet().stream().filter(entry -> ("Bearer " + entry.getValue()).equals(authorization))
				.map(Map.Entry::getKey).findFirst().orElse(null);
		if (role == null) { respond(exchange, 401, Map.of("code", "UNAUTHENTICATED")); return; }
		respond(exchange, 200, Map.of("accountId", role.equals("USER") ? userId : specialistId,
				"email", role.toLowerCase() + "@video.test", "roles", List.of(role), "status", "ACTIVE", "emailVerified", true,
				"version", 0, "createdAt", Instant.now().toString(), "updatedAt", Instant.now().toString()));
	}

	private void respond(HttpExchange exchange, int status, Object body) throws java.io.IOException {
		var bytes = json.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TimeFixture { @Bean @Primary JourneyClock journeyClock() { return new JourneyClock(); } }
	static class JourneyClock extends Clock {
		final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ofDays(-2));
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("UTC fixture only"); return this; }
		@Override public Instant instant() { return Instant.now().plus(offset.get()); }
	}
}
