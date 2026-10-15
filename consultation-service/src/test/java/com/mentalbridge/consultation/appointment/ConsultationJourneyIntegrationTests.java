package com.mentalbridge.consultation.appointment;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

@Import({TestcontainersConfiguration.class, ConsultationJourneyIntegrationTests.TimeFixture.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"server.address=127.0.0.1", "logging.level.root=WARN",
		"mentalbridge.consultation.video.enabled=false",
		"mentalbridge.consultation.availability.video-enabled=false",
		"mentalbridge.consultation.chat-settlement-interval=PT24H",
		"mentalbridge.consultation.appointments.expiry-interval=PT24H"
})
class ConsultationJourneyIntegrationTests extends ConsultationTestProperties {

	@LocalServerPort int port;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired JourneyClock clock;
	@Autowired ChatSessionSettlementService settlements;
	@Autowired AppointmentDecisionService decisions;
	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

	@BeforeEach
	void resetTestTime() {
		clock.now.set(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
	}

	@Test
	void discoveryBookingEvidenceSummaryAndRatingShareOneImmutableCompletionAndCredit() throws Exception {
		var fixture = fixture();
		var slot = slot(fixture, fixture.start());
		var detail = send(fixture.user(), "GET", "/api/v1/specialists/" + fixture.specialist().id(), null, 200);
		assertThat(detail.path("selectableSlots").get(0).path("id").asText()).isEqualTo(slot.path("id").asText());
		assertThat(Duration.between(Instant.parse(slot.path("startAt").asText()),
				Instant.parse(slot.path("endAt").asText()))).isEqualTo(Duration.ofHours(1));
		var booking = book(fixture, slot, "mb623-golden-booking");
		assertThat(book(fixture, slot, "mb623-golden-booking")).isEqualTo(booking);
		var id = UUID.fromString(booking.path("id").asText());
		assertThat(booking.path("creditState").asText()).isEqualTo("HELD");
		assertThat(eventCount(id, "HELD")).isOne();
		accept(fixture, id);
		assertThat(eligibility(fixture.user(), id).path("phase").asText()).isEqualTo("TOO_EARLY");
		clock.now.set(fixture.start().minusSeconds(600));
		var waiting = eligibility(fixture.user(), id);
		assertThat(waiting.path("phase").asText()).isEqualTo("WAITING");
		assertThat(waiting.path("sendAllowed").asBoolean()).isFalse();
		send(fixture.specialist(), "POST", "/api/v1/specialist/appointments/" + id + "/session-summaries",
				summary(), 409, command("mb623-premature-summary", null));
		send(fixture.user(), "PUT", "/api/v1/appointments/" + id + "/rating", Map.of("rating", 5), 409);
		complete(fixture, id);
		var completed = appointment(fixture.user(), id);
		assertThat(completed.path("status").asText()).isEqualTo("COMPLETED");
		assertThat(completed.path("creditState").asText()).isEqualTo("CONSUMED");
		assertThat(completed.path("sessionPolicyVersion").asText()).isEqualTo(ChatSessionCompletionPolicy.VERSION);
		assertThat(completed.path("completionFactId").isTextual()).isTrue();
		assertThat(eventCount(id, "CONSUMED")).isOne();
		assertThat(earningCount(id)).isOne();
		var published = send(fixture.specialist(), "POST", "/api/v1/specialist/appointments/" + id + "/session-summaries",
				summary(), 201, command("mb623-publish-summary", null));
		assertThat(send(fixture.specialist(), "POST", "/api/v1/specialist/appointments/" + id + "/session-summaries",
				summary(), 201, command("mb623-publish-summary", null))).isEqualTo(published);
		var history = send(fixture.user(), "GET", "/api/v1/appointments/" + id + "/session-summaries", null, 200);
		assertThat(history.path("count").asInt()).isOne();
		assertThat(history.path("items").get(0).path("reuseConsent").path("approved").asBoolean()).isFalse();
		var step = published.path("agreedNextSteps").get(0).path("id").asText();
		send(fixture.user(), "PUT", "/api/v1/agreed-next-steps/" + step,
				Map.of("state", "COMPLETED", "hidden", false), 200, Map.of("If-Match", "\"0\""));
		var specialistRead = send(fixture.specialist(), "GET", "/api/v1/specialist/appointments/" + id + "/session-summaries", null, 200);
		assertThat(specialistRead.path("items").get(0).path("agreedNextSteps").get(0).path("state").isNull()).isTrue();
		var rating = send(fixture.user(), "PUT", "/api/v1/appointments/" + id + "/rating", Map.of("rating", 5), 200);
		send(fixture.user(), "PUT", "/api/v1/appointments/" + id + "/rating", Map.of("rating", 4), 200,
				Map.of("If-Match", "\"" + rating.path("version").asLong() + "\""));
		assertThat(jdbc.sql("select count(*) from appointment_rating where appointment_id=:id")
				.param("id", id).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select rating_count from specialist_rating_aggregate where specialist_account_id=:id")
				.param("id", fixture.specialist().id()).query(Long.class).single()).isOne();
		var earnings = send(fixture.specialist(), "GET", "/api/v1/specialist/earnings", null, 200);
		assertThat(earnings.path("balance").path("availableVnd").asLong()).isZero();
		assertThat(earnings.path("earnings").get(0).path("consumedCreditId").asText()).isEqualTo(booking.path("heldCreditId").asText());
		assertThat(jdbc.sql("select completion_fact_id from specialist_earning where appointment_id=:id")
				.param("id", id).query(UUID.class).single().toString()).isEqualTo(completed.path("completionFactId").asText());
		assertThat(appointment(fixture.user(), id).path("completionFactId")).isEqualTo(completed.path("completionFactId"));
	}

	@ParameterizedTest
	@CsvSource({"USER, SPECIALIST_NO_SHOW, AVAILABLE", "SPECIALIST, USER_NO_SHOW, FORFEITED",
			"NONE, BOTH_NO_SHOW, AVAILABLE", "CHECK_INS, INSUFFICIENT_EVIDENCE, AVAILABLE"})
	void nonPayableOutcomesNeverProduceCompletionOrEarning(String witness, String outcome, String credit) throws Exception {
		var fixture = fixture();
		var booking = book(fixture, slot(fixture, fixture.start()), "mb623-nonpayable-book");
		var id = UUID.fromString(booking.path("id").asText());
		accept(fixture, id);
		clock.now.set(fixture.start().plusSeconds(31 * 60));
		if (witness.equals("CHECK_INS")) {
			checkIn(fixture.user(), id, fixture.start());
			checkIn(fixture.specialist(), id, fixture.start());
		} else if (!witness.equals("NONE")) {
			participate(witness.equals("USER") ? fixture.user() : fixture.specialist(), id, fixture.start(), 15);
		}
		clock.now.set(fixture.start().plusSeconds(3600));
		settlements.end(id);
		assertThat(appointment(fixture.user(), id).path("creditState").asText()).isEqualTo("HELD");
		assertThat(earningCount(id)).isZero();
		clock.now.set(fixture.start().plusSeconds(3901));
		settlements.settle(id);
		settlements.settle(id);
		var result = appointment(fixture.user(), id);
		assertThat(result.path("status").asText()).isEqualTo("SESSION_ENDED");
		assertThat(result.path("sessionOutcome").asText()).isEqualTo(outcome);
		assertThat(result.path("creditState").asText()).isEqualTo(credit);
		assertThat(result.path("completionFactId").isNull()).isTrue();
		assertThat(earningCount(id)).isZero();
		assertThat(eventCount(id, credit.equals("FORFEITED") ? "FORFEITED" : "RELEASED")).isOne();
		send(fixture.user(), "PUT", "/api/v1/appointments/" + id + "/rating", Map.of("rating", 5), 409);
	}

	@Test
	void conflictingEvidenceStaysHeldUntilExistingTechnicalReconciliationAndNeverEarns() throws Exception {
		var fixture = fixture();
		var booking = book(fixture, slot(fixture, fixture.start()), "mb623-conflict-evidence-book");
		var id = UUID.fromString(booking.path("id").asText());
		accept(fixture, id);
		clock.now.set(fixture.start().plusSeconds(60));
		var evidenceId = UUID.randomUUID();
		var original = Map.of("evidenceId", evidenceId, "type", "CHECK_IN", "occurredAt", fixture.start().toString());
		var endpoint = "/internal/v1/appointments/" + id + "/chat-evidence";
		send(fixture.user(), "POST", endpoint, original, 200, evidenceHeaders());
		assertThat(send(fixture.user(), "POST", endpoint, original, 200, evidenceHeaders()).path("duplicate").asBoolean()).isTrue();
		var conflicting = Map.of("evidenceId", evidenceId, "type", "CHECK_IN", "occurredAt", fixture.start().plusSeconds(1).toString());
		assertThat(send(fixture.user(), "POST", endpoint, conflicting, 200, evidenceHeaders()).path("reasonCode").asText()).isEqualTo("EVIDENCE_ID_CONFLICT");
		clock.now.set(fixture.start().plusSeconds(3901));
		settlements.end(id);
		settlements.settle(id);
		assertThat(appointment(fixture.user(), id).path("creditState").asText()).isEqualTo("HELD");
		assertThat(eligibility(fixture.user(), id).path("phase").asText()).isEqualTo("EVIDENCE_REVIEW");
		assertThat(eventCount(id, "RELEASED")).isZero();
		clock.now.set(fixture.start().plusSeconds(3600 + 35 * 60 + 1));
		settlements.settle(id);
		settlements.settle(id);
		assertThat(appointment(fixture.user(), id).path("sessionOutcome").asText()).isEqualTo("EVIDENCE_REVIEW");
		assertThat(eventCount(id, "RELEASED")).isOne();
		assertThat(earningCount(id)).isZero();
	}

	@ParameterizedTest
	@CsvSource({"REJECT, REJECTED", "EXPIRE, EXPIRED", "CANCEL, CANCELLED"})
	void requestExceptionCommandsAndRetriesReleaseTheOriginalHoldOnce(String action, String expected) throws Exception {
		var fixture = fixture();
		var booking = book(fixture, slot(fixture, fixture.start()), "mb623-exception-book");
		var id = UUID.fromString(booking.path("id").asText());
		if (action.equals("EXPIRE")) {
			clock.now.set(Instant.parse(booking.path("decisionDeadlineAt").asText()).plusSeconds(1));
			decisions.expire(id);
			decisions.expire(id);
		} else {
			var actor = action.equals("REJECT") ? fixture.specialist() : fixture.user();
			var path = action.equals("REJECT") ? "/api/v1/specialist/appointments/" + id + "/reject" : "/api/v1/appointments/" + id + "/cancel";
			for (int replay = 0; replay < 2; replay++) send(actor, "POST", path, null, 200, command("mb623-exception-command", 0L));
		}
		var result = appointment(fixture.user(), id);
		assertThat(result.path("status").asText()).isEqualTo(expected);
		assertThat(result.path("creditState").asText()).isEqualTo("AVAILABLE");
		assertThat(eventCount(id, "RELEASED")).isOne();
		assertThat(earningCount(id)).isZero();
	}

	@Test
	void reservationCapAndFailedReplacementPreserveHoldsThenAtomicRescheduleReusesOneCredit() throws Exception {
		var fixture = fixture();
		var first = book(fixture, slot(fixture, fixture.start()), "mb623-cap-first-book");
		var second = book(fixture, slot(fixture, fixture.start().plusSeconds(7200)), "mb623-cap-second-book");
		var target = slot(fixture, fixture.start().plusSeconds(14400));
		var rejected = send(fixture.user(), "POST", "/api/v1/appointments",
				Map.of("slotId", target.path("id").asText(), "modality", "IN_APP_CHAT"), 409, command("mb623-cap-third-book", null));
		assertThat(rejected.path("code").asText()).isEqualTo("APPOINTMENT_RESERVATION_LIMIT_REACHED");
		var firstId = UUID.fromString(first.path("id").asText());
		var replacement = Map.of("slotId", second.path("slotId").asText(), "modality", "IN_APP_CHAT", "replacesAppointmentId", firstId);
		send(fixture.user(), "POST", "/api/v1/appointments", replacement, 409, command("mb623-failed-replacement", 0L));
		assertThat(appointment(fixture.user(), firstId).path("status").asText()).isEqualTo("REQUESTED");
		assertThat(eventCount(firstId, "RELEASED")).isZero();
		var moved = send(fixture.user(), "POST", "/api/v1/appointments",
				Map.of("slotId", target.path("id").asText(), "modality", "IN_APP_CHAT", "replacesAppointmentId", firstId),
				201, command("mb623-atomic-replacement", 0L));
		assertThat(moved.path("heldCreditId")).isEqualTo(first.path("heldCreditId"));
		var old = appointment(fixture.user(), firstId);
		assertThat(old.path("status").asText()).isEqualTo("CANCELLED");
		assertThat(old.path("scheduledStartAt")).isEqualTo(first.path("scheduledStartAt"));
		assertThat(old.path("replacedByAppointmentId")).isEqualTo(moved.path("id"));
		assertThat(eventCount(firstId, "RELEASED")).isOne();
		assertThat(send(fixture.user(), "GET", "/api/v1/service-credits", null, 200).path("balance").path("held").asInt()).isEqualTo(2);
	}

	@Test
	void disputeBlocksMatureEarningAndResolutionAdjustsCreditWithoutRewritingCompletion() throws Exception {
		var fixture = fixture();
		var booking = book(fixture, slot(fixture, fixture.start()), "mb623-dispute-book");
		var id = UUID.fromString(booking.path("id").asText());
		accept(fixture, id);
		complete(fixture, id);
		var fact = appointment(fixture.user(), id).path("completionFactId").asText();
		var dispute = send(fixture.user(), "POST", "/api/v1/appointments/" + id + "/dispute",
				Map.of("reasonCode", "OUTCOME_INCORRECT"), 200, command("mb623-open-dispute", null));
		assertThat(dispute.path("settlementGated").asBoolean()).isTrue();
		clock.now.set(clock.instant().plus(Duration.ofDays(8)));
		var earnings = send(fixture.specialist(), "GET", "/api/v1/specialist/earnings", null, 200);
		assertThat(earnings.path("balance").path("availableVnd").asLong()).isZero();
		var resolution = Map.of("outcome", "RELEASE_USER_CREDIT", "reasonCode", "TECHNICAL_FAILURE_CONFIRMED");
		for (int replay = 0; replay < 2; replay++) send(fixture.admin(), "POST",
				"/api/v1/admin/appointment-disputes/" + dispute.path("id").asText() + "/resolve", resolution, 200,
				command("mb623-resolve-dispute", 0L));
		assertThat(eventCount(id, "ADJUSTED_RELEASED")).isOne();
		assertThat(eventCount(id, "CONSUMED")).isOne();
		assertThat(appointment(fixture.user(), id).path("completionFactId").asText()).isEqualTo(fact);
		assertThat(appointment(fixture.user(), id).path("creditState").asText()).isEqualTo("AVAILABLE");
		assertThat(send(fixture.specialist(), "GET", "/api/v1/specialist/earnings", null, 200)
				.path("balance").path("availableVnd").asLong()).isZero();
	}

	@Test
	void freeAndWrongActorsCannotBookOrReadPrivateContextAndDisabledVideoIsNotSelectable() throws Exception {
		var fixture = fixture();
		var slot = slot(fixture, fixture.start());
		var stranger = actor("USER");
		assertThat(send(stranger, "GET", "/api/v1/specialists", null, 200).path("bookingHandoff").asText()).isEqualTo("BROWSE_ONLY");
		send(stranger, "POST", "/api/v1/appointments", Map.of("slotId", slot.path("id").asText(), "modality", "IN_APP_CHAT"),
				403, command("mb623-free-cannot-book", null));
		var booking = book(fixture, slot, "mb623-owner-only-book");
		var id = UUID.fromString(booking.path("id").asText());
		send(stranger, "GET", "/internal/v1/appointments/" + id + "/consultation-brief-context", null, 404);
		send(actor("SPECIALIST"), "GET", "/internal/v1/appointments/" + id + "/consultation-brief-context", null, 404);
		send(fixture.user(), "POST", "/internal/v1/appointments/" + id + "/chat-evidence",
				Map.of("evidenceId", UUID.randomUUID(), "type", "CHECK_IN", "occurredAt", fixture.start().toString()), 401);
		assertThat(send(fixture.user(), "GET", "/api/v1/specialists", null, 200).path("videoEnabled").asBoolean()).isFalse();
		assertThat(send(fixture.specialist(), "POST", "/api/v1/availability-slots",
				Map.of("startAt", fixture.start().plusSeconds(7200).toString(), "endAt", fixture.start().plusSeconds(10800).toString(),
						"timezone", "Asia/Ho_Chi_Minh", "modality", "IN_APP_VIDEO"), 409, command("mb623-disabled-video", null))
				.path("code").asText()).isEqualTo("VIDEO_AVAILABILITY_DISABLED");
		assertThat(eventCount(id, "HELD")).isOne();
	}

	private Fixture fixture() throws Exception {
		var user = actor("USER");
		var specialist = actor("SPECIALIST");
		var admin = actor("ADMIN");
		jdbc.sql("""
				insert into current_service_entitlement(account_id,package_code,source,source_reference,effective_from,effective_until,policy_version)
				values (:id,'PLUS','PAID',:ref,:start,:end,'service-entitlement-v1')
				""").param("id", user.id()).param("ref", "mb623-synthetic-" + user.id())
				.param("start", Timestamp.from(clock.instant().minusSeconds(60)))
				.param("end", Timestamp.from(clock.instant().plus(Duration.ofDays(30)))).update();
		jdbc.sql("""
				insert into specialist_profile(account_id,display_name,biography,years_experience,timezone,approval_status,submitted_at,reviewed_at,reviewed_by)
				values (:id,:name,'Synthetic non-clinical journey fixture',5,'Asia/Ho_Chi_Minh','APPROVED',:now,:now,:admin)
				""").param("id", specialist.id()).param("name", "Chuyên gia MB-623 " + specialist.id().toString().substring(0, 8))
				.param("now", Timestamp.from(clock.instant())).param("admin", admin.id()).update();
		jdbc.sql("insert into specialist_profile_support_area values (:id,'ANXIETY_SYMPTOMS')").param("id", specialist.id()).update();
		jdbc.sql("insert into specialist_profile_language values (:id,'vi')").param("id", specialist.id()).update();
		return new Fixture(user, specialist, admin, clock.instant().plus(Duration.ofDays(2)));
	}

	private Actor actor(String role) throws Exception {
		var id = UUID.randomUUID();
		return new Actor(id, role, signedTestToken(id, role));
	}

	private JsonNode slot(Fixture fixture, Instant start) throws Exception {
		return send(fixture.specialist(), "POST", "/api/v1/availability-slots",
				Map.of("startAt", start.toString(), "endAt", start.plusSeconds(3600).toString(),
						"timezone", "Asia/Ho_Chi_Minh", "modality", "IN_APP_CHAT"), 201, command("mb623-slot-" + UUID.randomUUID(), null));
	}

	private JsonNode book(Fixture fixture, JsonNode slot, String key) throws Exception {
		return send(fixture.user(), "POST", "/api/v1/appointments",
				Map.of("slotId", slot.path("id").asText(), "modality", "IN_APP_CHAT"), 201, command(key, null));
	}

	private void accept(Fixture fixture, UUID id) throws Exception {
		for (int replay = 0; replay < 2; replay++) send(fixture.specialist(), "POST", "/api/v1/specialist/appointments/" + id + "/accept",
				null, 200, command("mb623-accept-" + id, 0L));
	}

	private JsonNode appointment(Actor actor, UUID id) throws Exception {
		var items = send(actor, "GET", "/api/v1/appointments", null, 200).path("items");
		for (var item : items) if (item.path("id").asText().equals(id.toString())) return item;
		throw new AssertionError("Appointment missing from authoritative owner history");
	}

	private JsonNode eligibility(Actor actor, UUID id) throws Exception {
		return send(actor, "GET", "/internal/v1/appointments/" + id + "/chat-eligibility?operation=SEND", null, 200);
	}

	private void complete(Fixture fixture, UUID id) throws Exception {
		clock.now.set(fixture.start().plusSeconds(31 * 60));
		participate(fixture.user(), id, fixture.start(), 30);
		participate(fixture.specialist(), id, fixture.start(), 30);
		clock.now.set(fixture.start().plusSeconds(3600));
		settlements.end(id);
		settlements.end(id);
		assertThat(appointment(fixture.user(), id).path("status").asText()).isEqualTo("SESSION_ENDED");
		assertThat(earningCount(id)).isZero();
		assertThat(eventCount(id, "CONSUMED")).isZero();
		clock.now.set(fixture.start().plusSeconds(3901));
		try (var executor = Executors.newFixedThreadPool(2)) {
			var gate = new CountDownLatch(1);
			var tasks = new ArrayList<CompletableFuture<Void>>();
			for (int i = 0; i < 2; i++) tasks.add(CompletableFuture.runAsync(() -> {
				try { gate.await(); settlements.settle(id); }
				catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
			}, executor));
			gate.countDown();
			for (var task : tasks) task.join();
		}
	}

	private void participate(Actor actor, UUID id, Instant start, int minutes) throws Exception {
		checkIn(actor, id, start);
		evidence(actor, id, Map.of("evidenceId", UUID.randomUUID(), "type", "ACCEPTED_MESSAGE",
				"occurredAt", start.plusSeconds(30).toString(), "messageId", UUID.randomUUID()));
		for (int minute = 0; minute < minutes; minute++) evidence(actor, id,
				Map.of("evidenceId", UUID.randomUUID(), "type", "PRESENCE_INTERVAL",
						"intervalStartedAt", start.plusSeconds(minute * 60L).toString(), "occurredAt", start.plusSeconds((minute + 1) * 60L).toString()));
	}

	private void checkIn(Actor actor, UUID id, Instant at) throws Exception {
		evidence(actor, id, Map.of("evidenceId", UUID.randomUUID(), "type", "CHECK_IN", "occurredAt", at.toString()));
	}

	private void evidence(Actor actor, UUID id, Object body) throws Exception {
		assertThat(send(actor, "POST", "/internal/v1/appointments/" + id + "/chat-evidence", body, 200, evidenceHeaders())
				.path("accepted").asBoolean()).isTrue();
	}

	private long eventCount(UUID id, String event) {
		return jdbc.sql("select count(*) from service_credit_ledger where appointment_id=:id and event_type=:event")
				.param("id", id).param("event", event).query(Long.class).single();
	}

	private long earningCount(UUID id) {
		return jdbc.sql("select count(*) from specialist_earning where appointment_id=:id").param("id", id).query(Long.class).single();
	}

	private Map<String, String> command(String key, Long version) {
		return version == null ? Map.of("Idempotency-Key", key) : Map.of("Idempotency-Key", key, "If-Match", "\"" + version + "\"");
	}

	private Map<String, String> evidenceHeaders() {
		return Map.of("X-MentalBridge-Service-Token", EVIDENCE_SERVICE_TOKEN);
	}

	private Map<String, Object> summary() {
		return Map.of("topicsDiscussed", List.of("Thói quen hằng ngày"), "progressSummary", "Nội dung thử nghiệm đã cùng thống nhất.",
				"followUpSuggested", true, "agreedNextSteps", List.of(Map.of("type", "JOURNAL", "title", "Viết một ghi chú riêng tư")));
	}

	private JsonNode send(Actor actor, String method, String path, Object body, int expected) throws Exception {
		return send(actor, method, path, body, expected, Map.of());
	}

	private JsonNode send(Actor actor, String method, String path, Object body, int expected, Map<String, String> headers) throws Exception {
		var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10))
				.header("Authorization", "Bearer " + actor.token()).header("Content-Type", "application/json")
				.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
		headers.forEach(request::header);
		var response = http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
		var result = json.readTree(response.body());
		assertThat(response.statusCode()).as("%s %s (%s)", method, path, result == null ? "no body" : result.path("code").asText()).isEqualTo(expected);
		return result;
	}

	private record Actor(UUID id, String role, String token) { }
	private record Fixture(Actor user, Actor specialist, Actor admin, Instant start) { }

	@TestConfiguration(proxyBeanMethods = false)
	static class TimeFixture {
		@Bean @Primary JourneyClock journeyClock() { return new JourneyClock(); }
	}

	static class JourneyClock extends Clock {
		final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return now.get(); }
	}
}
