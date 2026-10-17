package com.mentalbridge.consultation.appointment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.video.VideoProvider;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"mentalbridge.consultation.availability.video-enabled=true",
		"mentalbridge.consultation.video.enabled=true",
		"mentalbridge.consultation.video.url=wss://synthetic-project.livekit.cloud",
		"mentalbridge.consultation.video.api-key=synthetic-test-key",
		"mentalbridge.consultation.video.api-secret=synthetic-test-secret"
})
@AutoConfigureMockMvc
class ImmediateBookingIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired AppointmentDecisionService decisions;
	@MockitoBean VideoProvider provider;

	@Test
	void activeWindowCanBePublishedDiscoveredBookedConfirmedAndJoinedImmediately() throws Exception {
		var specialist = specialist();
		var user = paidUser();
		var slot = publish(specialist, Instant.now().minusSeconds(30));
		var slotId = slot.get("id").asText();
		mvc.perform(get("/api/v1/specialists").with(actor(user, "USER")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[?(@.specialistAccountId == '%s')].selectableSlots[*].id".formatted(specialist))
						.value(org.hamcrest.Matchers.hasItem(slotId)));
		mvc.perform(get("/api/v1/bookable-slots").with(actor(user, "USER")))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.id == '%s')]".formatted(slotId)).isNotEmpty());
		var requested = request(user, slotId);
		var appointmentId = requested.get("id").asText();
		assertThat(Instant.parse(requested.get("decisionDeadlineAt").asText()))
				.isEqualTo(Instant.parse(slot.get("endAt").asText()));
		var available = jdbc.sql("select count(*) from service_credit c join service_credit_period p on p.id=c.period_id where p.account_id=:id and c.state='AVAILABLE'")
				.param("id", user).query(Integer.class).single();
		assertThat(available).isEqualTo(3);
		mvc.perform(post("/api/v1/appointments/{id}/video-access", appointmentId).with(actor(user, "USER")))
				.andExpect(status().isConflict());
		mvc.perform(post("/api/v1/specialist/appointments/{id}/accept", appointmentId)
				.with(actor(specialist, "SPECIALIST")).header("If-Match", "\"0\"")
				.header("Idempotency-Key", "immediate-accept-" + appointmentId))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CONFIRMED"));
		when(provider.issueToken(anyString(), anyString(), any(), any())).thenReturn("synthetic-access-token");
		for (var role : List.of("USER", "SPECIALIST")) {
			var participant = role.equals("USER") ? user : specialist;
			mvc.perform(get("/api/v1/appointments/{id}/video-session", appointmentId)
					.with(actor(participant, role)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.joinAllowed").value(true));
			mvc.perform(post("/api/v1/appointments/{id}/video-access", appointmentId)
					.with(actor(participant, role)))
					.andExpect(status().isOk()).andExpect(jsonPath("$.participantToken").value("synthetic-access-token"));
		}
		assertThat(jdbc.sql("select state from service_credit where appointment_id=:id")
				.param("id", UUID.fromString(appointmentId)).query(String.class).single()).isEqualTo("HELD");
		assertThatThrownBy(() -> jdbc.sql("update appointment set decision_deadline_at=scheduled_end_at+interval '1 second' where id=:id")
				.param("id", UUID.fromString(appointmentId)).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void oneMinuteAheadBookingHasAFutureDeadlineAndRetryDoesNotDuplicateItsHold() throws Exception {
		var specialist = specialist();
		var user = paidUser();
		var slot = publish(specialist, Instant.now().plusSeconds(60));
		var first = request(user, slot.get("id").asText());
		var retry = request(user, slot.get("id").asText());
		assertThat(retry.get("id").asText()).isEqualTo(first.get("id").asText());
		assertThat(Instant.parse(first.get("decisionDeadlineAt").asText())).isAfter(Instant.now());
		assertThat(jdbc.sql("select count(*) from service_credit where appointment_id=:id")
				.param("id", UUID.fromString(first.get("id").asText())).query(Integer.class).single()).isEqualTo(1);
	}

	@Test
	void shortNoticeExpiryReleasesTheCurrentCreditOnceAndNeverUsesAnExpiredPeriod() throws Exception {
		var specialist = specialist();
		var user = paidUser();
		var slot = publish(specialist, Instant.now().minusSeconds(30));
		var expiredPeriod = UUID.randomUUID();
		var expiredCredit = UUID.randomUUID();
		jdbc.sql("insert into service_credit_period(id,account_id,plan_version,credit_policy_version,package_code,source,source_reference,period_start,period_end,allocated_count,credit_allocation_minor,created_at,updated_at) values(:id,:user,'service-entitlement-v1','consultation-credit-v2','PLUS','DEMO',:ref,now()-interval '1 day',now()-interval '10 seconds',4,300000,now(),now())")
				.param("id", expiredPeriod).param("user", user).param("ref", "expired-" + user).update();
		jdbc.sql("insert into service_credit(id,period_id,ordinal,state,created_at,updated_at) values(:id,:period,1,'AVAILABLE',now(),now())")
				.param("id", expiredCredit).param("period", expiredPeriod).update();
		var appointment = request(user, slot.get("id").asText());
		var id = UUID.fromString(appointment.get("id").asText());
		assertThat(appointment.get("heldCreditId").asText()).isNotEqualTo(expiredCredit.toString());
		jdbc.sql("update appointment set requested_at=:requested,decision_deadline_at=:deadline where id=:id")
				.param("requested", java.sql.Timestamp.from(Instant.now().minusSeconds(120)))
				.param("deadline", java.sql.Timestamp.from(Instant.now().minusSeconds(10)))
				.param("id", id).update();
		assertThat(decisions.expire(id)).isTrue();
		assertThat(decisions.expire(id)).isFalse();
		assertThat(jdbc.sql("select count(*) from service_credit_ledger where appointment_id=:id and event_type='RELEASED'")
				.param("id", id).query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("select state from service_credit where id=:id")
				.param("id", expiredCredit).query(String.class).single()).isEqualTo("AVAILABLE");
	}

	@Test
	void endedAvailabilityCannotBePublished() throws Exception {
		var specialist = specialist();
		var start = Instant.now().minusSeconds(7_200);
		mvc.perform(post("/api/v1/availability-slots").with(actor(specialist, "SPECIALIST"))
				.header("Idempotency-Key", "immediate-ended-" + specialist).contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.violations[0].code").value("AVAILABILITY_MUST_BE_FUTURE"));
	}

	private JsonNode publish(UUID specialist, Instant start) throws Exception {
		var response = mvc.perform(post("/api/v1/availability-slots").with(actor(specialist, "SPECIALIST"))
				.header("Idempotency-Key", "immediate-publish-" + specialist).contentType(MediaType.APPLICATION_JSON)
				.content(slotBody(start.truncatedTo(ChronoUnit.MILLIS)))).andExpect(status().isCreated()).andReturn();
		return json.readTree(response.getResponse().getContentAsByteArray());
	}

	private String slotBody(Instant start) {
		return "{\"startAt\":\"%s\",\"endAt\":\"%s\",\"timezone\":\"Asia/Ho_Chi_Minh\",\"modality\":\"IN_APP_VIDEO\"}"
				.formatted(start, start.plusSeconds(3_600));
	}

	private JsonNode request(UUID user, String slotId) throws Exception {
		var response = mvc.perform(post("/api/v1/appointments").with(actor(user, "USER"))
				.header("Idempotency-Key", "immediate-request-" + slotId).contentType(MediaType.APPLICATION_JSON)
				.content("{\"slotId\":\"%s\",\"modality\":\"IN_APP_VIDEO\"}".formatted(slotId)))
				.andExpect(status().isCreated()).andReturn();
		return json.readTree(response.getResponse().getContentAsByteArray());
	}

	private UUID specialist() {
		var id = UUID.randomUUID();
		jdbc.sql("insert into specialist_profile(account_id,display_name,biography,years_experience,timezone,approval_status,submitted_at,reviewed_at,reviewed_by) values(:id,'Immediate specialist','Synthetic test profile',4,'Asia/Ho_Chi_Minh','APPROVED',now(),now(),:admin)")
				.param("id", id).param("admin", UUID.randomUUID()).update();
		jdbc.sql("insert into specialist_profile_support_area values(:id,'ANXIETY_SYMPTOMS')").param("id", id).update();
		jdbc.sql("insert into specialist_profile_language values(:id,'vi')").param("id", id).update();
		return id;
	}

	private UUID paidUser() {
		var id = UUID.randomUUID();
		jdbc.sql("insert into current_service_entitlement(account_id,package_code,source,source_reference,established_by,effective_from,effective_until,policy_version) values(:id,'PLUS','DEMO',:ref,:admin,now()-interval '1 day',now()+interval '1 day','service-entitlement-v1')")
				.param("id", id).param("ref", "immediate-test-" + id).param("admin", UUID.randomUUID()).update();
		return id;
	}

	private RequestPostProcessor actor(UUID id, String role) {
		return jwt().jwt(token -> token.subject(id.toString()).tokenValue("synthetic-token").claim("roles", List.of(role)))
				.authorities(new SimpleGrantedAuthority("ROLE_" + role));
	}
}
