package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.appointment.ChatSessionSettlementService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

@Import({TestcontainersConfiguration.class, CommercialCrossStackTests.TimeFixture.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
		properties = {"server.address=127.0.0.1", "logging.level.root=WARN"})
@EnabledIfSystemProperty(named = "mb622.frontend", matches = ".+")
class CommercialCrossStackTests extends ConsultationTestProperties {

	@LocalServerPort int port;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired JourneyClock clock;
	@Autowired ChatSessionSettlementService settlements;
	private final HttpClient http = HttpClient.newHttpClient();
	private final UUID userId = UUID.randomUUID();
	private final UUID specialistId = UUID.randomUUID();
	private final UUID adminId = UUID.randomUUID();
	private Map<String, String> tokens;
	private final AtomicReference<Throwable> fixtureFailure = new AtomicReference<>();

	@Test
	void browserPurchaseUpgradeBookingSettlementAndPayoutUseTheSameCanonicalFacts() throws Exception {
		var frontend = Path.of(System.getProperty("mb622.frontend")).toAbsolutePath().normalize();
		assertThat(Files.isRegularFile(frontend.resolve("scripts/run-commercial-cross-stack.mjs"))).isTrue();
		tokens = Map.of("USER", signedTestToken(userId, "USER"), "SPECIALIST", signedTestToken(specialistId, "SPECIALIST"),
				"ADMIN", signedTestToken(adminId, "ADMIN"));
		jdbc.sql("""
				insert into specialist_profile(account_id, display_name, biography, years_experience, timezone,
				 approval_status, submitted_at, reviewed_at, reviewed_by)
				values (:id, 'Synthetic commercial specialist', 'Controlled non-clinical fixture', 5,
				 'Asia/Ho_Chi_Minh', 'APPROVED', now(), now(), :admin)
				""").param("id", specialistId).param("admin", adminId).update();
		var control = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		var key = UUID.randomUUID().toString();
		control.createContext("/api/v1/account", this::account);
		control.createContext("/fixture/", exchange -> {
			try {
				if (!key.equals(exchange.getRequestHeaders().getFirst("X-Fixture-Key"))) { respond(exchange, 403, Map.of()); return; }
				var input = json.readTree(exchange.getRequestBody());
				var output = switch (exchange.getRequestURI().getPath()) {
					case "/fixture/bootstrap" -> Map.of("tokens", tokens, "specialistId", specialistId,
							"start", clock.instant().plusSeconds(2 * 86_400L).toString());
					case "/fixture/confirm" -> confirm(UUID.fromString(input.get("paymentId").asText()));
						case "/fixture/financial-state" -> financialState(UUID.fromString(input.get("paymentId").asText()),
								UUID.fromString(input.get("subscriptionId").asText()));
					case "/fixture/settle" -> complete(UUID.fromString(input.get("appointmentId").asText()));
					case "/fixture/mature" -> { clock.now.set(clock.instant().plusSeconds(7 * 86_400L + 1)); yield Map.of("advancedHold", true); }
					default -> throw new IllegalArgumentException("Unknown fixture action");
				};
				respond(exchange, 200, output);
			}
			catch (Throwable failure) { fixtureFailure.compareAndSet(null, failure); respond(exchange, 500, Map.of("code", "COMMERCIAL_FIXTURE_FAILED")); }
		});
		control.start();
		Process browser = null;
		try {
			var output = Path.of("target", "mb622-browser.log");
			browser = new ProcessBuilder("node", "scripts/run-commercial-cross-stack.mjs").directory(frontend.toFile())
					.redirectErrorStream(true).redirectOutput(output.toFile()).start();
			try (var input = browser.getOutputStream()) {
				input.write(json.writeValueAsBytes(Map.of("consultation", "http://127.0.0.1:" + port,
						"fixture", "http://127.0.0.1:" + control.getAddress().getPort(), "key", key)));
			}
			assertThat(browser.waitFor(240, TimeUnit.SECONDS)).as("Controlled browser deadline").isTrue();
			if (fixtureFailure.get() != null) throw new AssertionError("Controlled fixture failed", fixtureFailure.get());
			assertThat(browser.exitValue()).as(Files.readString(output)).isZero();
			assertThat(jdbc.sql("select count(*) from payment_transaction pt join user_subscription s on s.id=pt.subscription_id where s.user_id=:id and pt.status='SUCCEEDED'")
					.param("id", userId).query(Long.class).single()).isEqualTo(2);
			assertThat(jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:id and status='SUCCEEDED'")
					.param("id", specialistId).query(Long.class).single()).isOne();
			assertThat(jdbc.sql("""
					select count(*) from specialist_earning e join service_credit c on c.id=e.consumed_credit_id
					join service_credit_period p on p.id=c.period_id join appointment a on a.id=e.appointment_id
					where e.specialist_account_id=:id and e.status='PAID' and a.status='COMPLETED' and c.state='CONSUMED'
					 and e.completion_fact_id=a.completion_fact_id and e.plan_version=p.plan_version
					 and e.plan_version='62200000-0000-4000-8000-000000000002'
					 and e.credit_allocation_minor=p.credit_allocation_minor and e.specialist_amount_minor=210000
					""").param("id", specialistId).query(Long.class).single()).isOne();
		}
		finally {
			if (browser != null && browser.isAlive()) {
				browser.descendants().forEach(ProcessHandle::destroyForcibly);
				browser.destroyForcibly();
			}
			control.stop(0);
		}
	}

	private void account(HttpExchange exchange) throws java.io.IOException {
		var authorization = exchange.getRequestHeaders().getFirst("Authorization");
		var role = tokens.entrySet().stream().filter(entry -> ("Bearer " + entry.getValue()).equals(authorization))
				.map(Map.Entry::getKey).findFirst().orElse(null);
		if (role == null) { respond(exchange, 401, Map.of("code", "UNAUTHENTICATED")); return; }
		var id = role.equals("USER") ? userId : role.equals("SPECIALIST") ? specialistId : adminId;
		respond(exchange, 200, Map.of("accountId", id, "email", role.toLowerCase() + "@commercial.test",
				"roles", List.of(role), "status", "ACTIVE", "emailVerified", true, "version", 0,
				"createdAt", Instant.now().toString(), "updatedAt", Instant.now().toString()));
	}

	private Map<String, Object> confirm(UUID paymentId) throws Exception {
		var payment = jdbc.sql("""
				select pt.momo_order_id, pt.momo_request_id, pt.amount_minor from payment_transaction pt
				join user_subscription s on s.id=pt.subscription_id where pt.id=:id and s.user_id=:user
				""").param("id", paymentId).param("user", userId)
				.query((row, ignored) -> new String[]{row.getString(1), row.getString(2), row.getString(3)}).single();
		var node = json.createObjectNode().put("partnerCode", "MOMO_TEST_PARTNER").put("orderId", payment[0])
				.put("requestId", payment[1]).put("amount", Long.parseLong(payment[2])).put("orderInfo", "Synthetic plan checkout")
				.put("orderType", "momo_wallet").put("transId", Math.abs((long) paymentId.hashCode()) + 1)
				.put("resultCode", 0).put("message", "Successful.").put("payType", "qr")
				.put("responseTime", clock.instant().toEpochMilli()).put("extraData", "");
		var raw = "accessKey=test-payment-access-key";
		for (var field : List.of("amount", "extraData", "message", "orderId", "orderInfo", "orderType", "partnerCode", "payType", "requestId", "responseTime", "resultCode", "transId"))
			raw += "&" + field + "=" + node.get(field).asText();
		var mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec("test-payment-secret-key".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
		node.put("signature", HexFormat.of().formatHex(mac.doFinal(raw.getBytes(StandardCharsets.UTF_8))));
		for (int replay = 0; replay < 2; replay++) send("/internal/v1/payments/momo/ipn", null, node, 204);
		return Map.of("verified", true, "duplicateAcknowledged", true);
	}

	private Map<String, Object> financialState(UUID paymentId, UUID subscriptionId) {
		var status = jdbc.sql("select status from payment_transaction where id=:id and subscription_id=:subscriptionId")
				.param("id", paymentId).param("subscriptionId", subscriptionId).query(String.class).single();
		var credits = jdbc.sql("""
				select count(*) from service_credit c join service_credit_period p on p.id=c.period_id
				where p.source='PAID' and p.source_reference=:subscriptionId
				""").param("subscriptionId", subscriptionId.toString()).query(Long.class).single();
		var consumed = jdbc.sql("""
				select count(*) from service_credit c join service_credit_period p on p.id=c.period_id
				where p.source='PAID' and p.source_reference=:subscriptionId and c.state='CONSUMED'
				""").param("subscriptionId", subscriptionId.toString()).query(Long.class).single();
		var earnings = jdbc.sql("select count(*) from specialist_earning where specialist_account_id=:id")
				.param("id", specialistId).query(Long.class).single();
		var payouts = jdbc.sql("select count(*) from specialist_payout where specialist_account_id=:id")
				.param("id", specialistId).query(Long.class).single();
		var payoutItems = jdbc.sql("""
				select count(*) from specialist_payout_item i join specialist_payout p on p.id=i.payout_id
				where p.specialist_account_id=:id
				""").param("id", specialistId).query(Long.class).single();
		return Map.of("paymentStatus", status, "creditCount", credits, "consumedCreditCount", consumed,
				"earningCount", earnings, "payoutCount", payouts, "payoutItemCount", payoutItems);
	}

	private Map<String, Object> complete(UUID appointmentId) throws Exception {
		var start = jdbc.sql("select scheduled_start_at from appointment where id=:id and user_account_id=:user and specialist_account_id=:specialist")
				.param("id", appointmentId).param("user", userId).param("specialist", specialistId)
				.query((row, ignored) -> row.getTimestamp(1).toInstant()).single();
		clock.now.set(start.plusSeconds(31 * 60));
		for (var role : List.of("USER", "SPECIALIST")) {
			evidence(appointmentId, role, "CHECK_IN", start, null);
			evidence(appointmentId, role, "ACCEPTED_MESSAGE", start.plusSeconds(30), null);
			for (int minute = 0; minute < 30; minute++) evidence(appointmentId, role, "PRESENCE_INTERVAL",
					start.plusSeconds((minute + 1) * 60L), start.plusSeconds(minute * 60L));
		}
		clock.now.set(start.plusSeconds(3600));
		settlements.end(appointmentId);
		assertThat(jdbc.sql("select status from appointment where id=:id").param("id", appointmentId).query(String.class).single()).isEqualTo("SESSION_ENDED");
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", appointmentId).query(Long.class).single()).isZero();
		clock.now.set(start.plusSeconds(3901));
		settlements.settle(appointmentId);
		settlements.settle(appointmentId);
		assertThat(jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", appointmentId).query(Long.class).single()).isOne();
		return Map.of("status", "COMPLETED", "timerDidNotEarn", true, "earningCount", 1);
	}

	private void evidence(UUID id, String role, String type, Instant occurredAt, Instant interval) throws Exception {
		var node = json.createObjectNode().put("evidenceId", UUID.randomUUID().toString()).put("type", type).put("occurredAt", occurredAt.toString());
		if (type.equals("ACCEPTED_MESSAGE")) node.put("messageId", UUID.randomUUID().toString());
		if (interval != null) node.put("intervalStartedAt", interval.toString());
		assertThat(send("/internal/v1/appointments/" + id + "/chat-evidence", tokens.get(role), node, 200).get("accepted").asBoolean()).isTrue();
	}

	private JsonNode send(String path, String token, ObjectNode body, int expected) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
				.timeout(java.time.Duration.ofSeconds(10)).header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
		if (token != null) request.header("Authorization", "Bearer " + token).header("X-MentalBridge-Service-Token", EVIDENCE_SERVICE_TOKEN);
		var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		assertThat(response.statusCode()).as("Real HTTP " + path).isEqualTo(expected);
		return expected == 204 ? json.createObjectNode() : json.readTree(response.body());
	}

	private void respond(HttpExchange exchange, int status, Object body) throws java.io.IOException {
		var bytes = json.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TimeFixture {
		@Bean @Primary JourneyClock journeyClock() { return new JourneyClock(); }
	}

	static class JourneyClock extends Clock {
		final AtomicReference<Instant> now = new AtomicReference<>(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { if (!zone.equals(ZoneOffset.UTC)) throw new IllegalArgumentException("UTC fixture only"); return this; }
		@Override public Instant instant() { return now.get(); }
	}
}
