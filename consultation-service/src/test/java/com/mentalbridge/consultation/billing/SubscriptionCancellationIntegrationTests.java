package com.mentalbridge.consultation.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.appointment.AppointmentModality;
import com.mentalbridge.consultation.appointment.AppointmentService;
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class SubscriptionCancellationIntegrationTests extends ConsultationTestProperties {

	private static final UUID PLUS = UUID.fromString("62200000-0000-4000-8000-000000000002");

	@Autowired SubscriptionCancellationService cancellations;
	@Autowired AppointmentService appointments;
	@Autowired JdbcClient jdbc;
	@Autowired MockMvc mvc;

	@Test
	void cancellationRevokesPaidAccessAndUnusedCreditsAndReplaysAuthoritativeResult() throws Exception {
		var owner = UUID.randomUUID();
		var subscription = activeSubscription(owner, 4);
		var futureAppointment = futureAppointment(owner, subscription);
		var key = "cancel-command-" + UUID.randomUUID();

		var accepted = cancellations.cancel(owner, subscription, key);
		var replay = cancellations.cancel(owner, subscription, key);

		assertThat(accepted.status()).isEqualTo("CANCELLED");
		assertThat(accepted.cancelledAppointments()).isOne();
		assertThat(accepted.revokedCredits()).isEqualTo(4);
		assertThat(replay.subscriptionId()).isEqualTo(subscription);
		assertThat(replay.cancellationRequestedAt()).isEqualTo(accepted.cancellationRequestedAt());
		assertThat(jdbc.sql("select count(*) from current_service_entitlement where account_id=:owner")
				.param("owner", owner).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("select count(*) from service_credit c join service_credit_period p on p.id=c.period_id where p.account_id=:owner and c.state='REVOKED'")
				.param("owner", owner).query(Long.class).single()).isEqualTo(4);
		assertThat(jdbc.sql("select count(*) from subscription_cancellation_command where subscription_id=:id")
				.param("id", subscription).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select changed_by from subscription_status_history where subscription_id=:id and to_status='CANCELLED'")
				.param("id", subscription).query(UUID.class).single()).isEqualTo(owner);
		assertThat(jdbc.sql("select status from appointment where id=:id").param("id", futureAppointment)
				.query(String.class).single()).isEqualTo("CANCELLED");
		assertThat(jdbc.sql("select cancellation_credit_outcome from appointment where id=:id")
				.param("id", futureAppointment).query(String.class).single()).isEqualTo("REVOKED");

		mvc.perform(get("/api/v1/subscriptions/current").with(user(owner)))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.subscriptionId").value(subscription.toString()));
		mvc.perform(get("/api/v1/service-credits").with(user(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.history[0].eventType").value("REVOKED"));
		mvc.perform(get("/api/v1/appointments").with(user(owner)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].cancellationReason").value("OWNER_SUBSCRIPTION_CANCELLED"))
				.andExpect(jsonPath("$.items[0].cancellationActor").value("USER"))
				.andExpect(jsonPath("$.items[0].cancellationCreditOutcome").value("REVOKED"))
				.andExpect(jsonPath("$.items[0].creditState").value("REVOKED"))
				.andExpect(jsonPath("$.items[0].history[0].reason").value("OWNER_SUBSCRIPTION_CANCELLED"));
	}

	@Test
	void liveSessionIsPreservedAndAutomaticCompletionUsesSystemActor() {
		var owner = UUID.randomUUID();
		var subscription = activeSubscription(owner, 1);
		var appointment = futureAppointment(owner, subscription);
		var now = Instant.now();
		jdbc.sql("""
				update appointment set status='IN_PROGRESS', decided_at=:now,
				 decision_reason='SPECIALIST_ACCEPTED', requested_at=:requested,
				 decision_deadline_at=:deadline, scheduled_start_at=:start, scheduled_end_at=:end
				 where id=:id
				""").param("now", database(now.minusSeconds(120)))
				.param("requested", database(now.minusSeconds(3_600)))
				.param("deadline", database(now.minusSeconds(120))).param("start", database(now.minusSeconds(60)))
				.param("end", database(now.plusSeconds(3_540))).param("id", appointment).update();

		var accepted = cancellations.cancel(owner, subscription, "live-cancel-" + UUID.randomUUID());
		assertThat(accepted.status()).isEqualTo("CANCEL_PENDING_SESSION_END");
		assertThat(jdbc.sql("select status from appointment where id=:id").param("id", appointment)
				.query(String.class).single()).isEqualTo("IN_PROGRESS");
		assertThat(jdbc.sql("select state from service_credit where appointment_id=:id").param("id", appointment)
				.query(String.class).single()).isEqualTo("HELD");

		jdbc.sql("""
				update appointment set requested_at=:requested, decision_deadline_at=:deadline,
				 scheduled_start_at=:start, scheduled_end_at=:end where id=:id
				""").param("requested", database(now.minusSeconds(7_200)))
				.param("deadline", database(now.minusSeconds(4_000)))
				.param("start", database(now.minusSeconds(3_700)))
				.param("end", database(now.minusSeconds(100))).param("id", appointment).update();
		assertThat(cancellations.current(owner).status()).isEqualTo("CANCELLED");
		var audit = jdbc.sql("""
				select changed_by, reason_code from subscription_status_history
				 where subscription_id=:id and from_status='CANCEL_PENDING_SESSION_END'
				""").param("id", subscription).query((row, ignored) -> java.util.Map.entry(
				row.getObject("changed_by", UUID.class), row.getString("reason_code"))).single();
		assertThat(audit.getKey()).isEqualTo(new UUID(0, 0));
		assertThat(audit.getValue()).isEqualTo("LIVE_SESSION_ENDED");
	}

	@Test
	void bookingAndCancellationShareOwnerSerializationFence() throws Exception {
		var owner = UUID.randomUUID();
		var subscription = activeSubscription(owner, 2);
		var slot = bookableSlot();
		var start = new CountDownLatch(1);
		try (var executor = Executors.newFixedThreadPool(2)) {
			var booking = executor.submit(() -> {
				start.await();
				try {
					return appointments.request(owner, "booking-race-" + UUID.randomUUID(), slot,
							AppointmentModality.IN_APP_CHAT, null, null);
				}
				catch (ApiException rejectedAfterCancellation) {
					return null;
				}
			});
			var cancellation = executor.submit(() -> {
				start.await();
				return cancellations.cancel(owner, subscription, "cancel-race-" + UUID.randomUUID());
			});
			start.countDown();
			assertThat(cancellation.get(30, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
			booking.get(30, TimeUnit.SECONDS);
		}
		assertThat(jdbc.sql("""
				select count(*) from appointment where user_account_id=:owner
				 and status in ('REQUESTED','CONFIRMED','IN_PROGRESS')
				""").param("owner", owner).query(Long.class).single()).isZero();
		assertThat(jdbc.sql("""
				select count(*) from service_credit c join service_credit_period p on p.id=c.period_id
				 where p.account_id=:owner and c.state in ('AVAILABLE','HELD','UPGRADE_HELD')
				""").param("owner", owner).query(Long.class).single()).isZero();
	}

	@Test
	void concurrentDuplicateCommandMutatesExactlyOnce() throws Exception {
		var owner = UUID.randomUUID();
		var subscription = activeSubscription(owner, 2);
		var key = "concurrent-cancel-" + UUID.randomUUID();
		try (var executor = Executors.newFixedThreadPool(2)) {
			var first = executor.submit(() -> cancellations.cancel(owner, subscription, key));
			var second = executor.submit(() -> cancellations.cancel(owner, subscription, key));
			assertThat(first.get(20, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
			assertThat(second.get(20, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
		}
		assertThat(jdbc.sql("select count(*) from subscription_cancellation_command where subscription_id=:id")
				.param("id", subscription).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from service_credit_ledger where account_id=:owner and event_type='REVOKED'")
				.param("owner", owner).query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void failsClosedForOtherOwnerInvalidStateAndReusedKey() {
		var owner = UUID.randomUUID();
		var subscription = activeSubscription(owner, 0);
		assertThatThrownBy(() -> cancellations.cancel(UUID.randomUUID(), subscription,
				"forbidden-cancel-" + UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
					error -> assertThat(error.code()).isEqualTo("SUBSCRIPTION_NOT_FOUND"));

		var key = "reused-cancel-key-" + UUID.randomUUID();
		cancellations.cancel(owner, subscription, key);
		assertThatThrownBy(() -> cancellations.cancel(owner, UUID.randomUUID(), key))
				.isInstanceOfSatisfying(ApiException.class,
					error -> assertThat(error.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
		assertThatThrownBy(() -> cancellations.cancel(owner, subscription,
				"second-cancel-key-" + UUID.randomUUID())).isInstanceOfSatisfying(ApiException.class,
					error -> assertThat(error.code()).isEqualTo("SUBSCRIPTION_CANCELLATION_INVALID_STATE"));
	}

	@Test
	void endpointRequiresOwnerAuthenticationAndIdempotencyKey() throws Exception {
		var owner = UUID.randomUUID();
		var subscription = activeSubscription(owner, 0);
		mvc.perform(post("/api/v1/subscriptions/{id}/cancellation", subscription)).andExpect(status().isUnauthorized());
		mvc.perform(post("/api/v1/subscriptions/{id}/cancellation", subscription).with(user(owner)))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_INVALID"));
		mvc.perform(post("/api/v1/subscriptions/{id}/cancellation", subscription)
				.with(user(UUID.randomUUID())).header("Idempotency-Key", "forbidden-api-key-123456"))
				.andExpect(status().isNotFound());
	}

	private UUID activeSubscription(UUID owner, int creditCount) {
		var id = UUID.randomUUID();
		var periodId = UUID.randomUUID();
		var now = Instant.now();
		var start = OffsetDateTime.ofInstant(now.minusSeconds(60), ZoneOffset.UTC);
		var end = OffsetDateTime.ofInstant(now.plusSeconds(86_400), ZoneOffset.UTC);
		jdbc.sql("""
				insert into user_subscription(id,user_id,plan_version_id,status,current_period_start,current_period_end,
				 idempotency_key,created_at,updated_at) values(:id,:owner,:plan,'ACTIVE',:start,:end,:key,:start,:start)
				""").param("id", id).param("owner", owner).param("plan", PLUS).param("start", start)
				.param("end", end).param("key", "purchase-" + UUID.randomUUID()).update();
		jdbc.sql("""
				insert into current_service_entitlement(account_id,package_code,source,source_reference,effective_from,
				 effective_until,policy_version,created_at,updated_at) values(:owner,'PLUS','PAID',:source,:start,:end,
				 'service-entitlement-v1',:start,:start)
				""").param("owner", owner).param("source", id.toString()).param("start", start).param("end", end).update();
		var payment = UUID.randomUUID();
		jdbc.sql("""
				insert into payment_transaction(id,subscription_id,target_plan_version_id,payment_provider,momo_order_id,
				 momo_request_id,amount_minor,currency,status,purpose,idempotency_key,paid_at,expires_at,checkout_url,
				 created_at,updated_at)
				 values(:id,:subscription,:plan,'FAKE',:orderId,:requestId,100000,'VND','SUCCEEDED','PURCHASE',:key,
				 :start,:expires,'https://test-payment.momo.vn/subscription-cancellation',:start,:start)
				""").param("id", payment).param("subscription", id).param("plan", PLUS)
				.param("orderId", "cancel-order-" + payment).param("requestId", "cancel-request-" + payment)
				.param("key", "cancel-payment-" + payment).param("start", start)
				.param("expires", OffsetDateTime.ofInstant(now.plusSeconds(1_200), ZoneOffset.UTC)).update();
		if (creditCount > 0) {
			jdbc.sql("""
					insert into service_credit_period(id,account_id,plan_version,credit_policy_version,package_code,source,
					 source_reference,period_start,period_end,allocated_count,credit_allocation_minor,created_at,updated_at)
					 values(:id,:owner,:plan,'consultation-credit-v2','PLUS','PAID',:source,:start,:end,4,300000,:start,:start)
					""").param("id", periodId).param("owner", owner).param("plan", PLUS.toString())
					.param("source", id.toString()).param("start", start).param("end", end).update();
			for (var ordinal = 1; ordinal <= creditCount; ordinal++) {
				jdbc.sql("""
						insert into service_credit(id,period_id,ordinal,state,created_at,updated_at)
						 values(:id,:period,:ordinal,'AVAILABLE',:now,:now)
						""").param("id", UUID.randomUUID()).param("period", periodId).param("ordinal", ordinal)
						.param("now", start).update();
			}
		}
		return id;
	}

	private UUID bookableSlot() {
		var specialist = UUID.randomUUID();
		var slot = UUID.randomUUID();
		var start = Instant.now().plusSeconds(7_200);
		jdbc.sql("""
				insert into specialist_profile(account_id,display_name,biography,years_experience,timezone,
				 approval_status,submitted_at,reviewed_at,reviewed_by)
				 values(:id,'Race Specialist','Booking cancellation race',5,'Asia/Ho_Chi_Minh','APPROVED',now(),now(),:admin)
				""").param("id", specialist).param("admin", UUID.randomUUID()).update();
		jdbc.sql("""
				insert into availability_slot(id,specialist_account_id,start_at,end_at,timezone,modality,
				 idempotency_key,created_at,updated_at)
				 values(:id,:specialist,:start,:end,'Asia/Ho_Chi_Minh','IN_APP_CHAT',:key,now(),now())
				""").param("id", slot).param("specialist", specialist).param("start", database(start))
				.param("end", database(start.plusSeconds(3_600))).param("key", "race-slot-" + slot).update();
		return slot;
	}

	private UUID futureAppointment(UUID owner, UUID subscriptionId) {
		var specialist = UUID.randomUUID();
		var slot = UUID.randomUUID();
		var appointment = UUID.randomUUID();
		var start = Instant.now().plusSeconds(7_200);
		var credit = jdbc.sql("""
				select c.id from service_credit c join service_credit_period p on p.id=c.period_id
				 where p.source_reference=:source and c.state='AVAILABLE' order by c.ordinal limit 1
				""").param("source", subscriptionId.toString()).query(UUID.class).single();
		jdbc.sql("""
				insert into specialist_profile(account_id,display_name,biography,years_experience,timezone,
				 approval_status,submitted_at,reviewed_at,reviewed_by)
				 values(:id,'Specialist','Subscription cancellation test',5,'Asia/Ho_Chi_Minh','APPROVED',now(),now(),:admin)
				""").param("id", specialist).param("admin", UUID.randomUUID()).update();
		jdbc.sql("""
				insert into availability_slot(id,specialist_account_id,start_at,end_at,timezone,modality,
				 idempotency_key,created_at,updated_at)
				 values(:id,:specialist,:start,:end,'Asia/Ho_Chi_Minh','IN_APP_CHAT',:key,now(),now())
				""").param("id", slot).param("specialist", specialist)
				.param("start", database(start)).param("end", database(start.plusSeconds(3_600)))
				.param("key", "slot-command-" + slot).update();
		jdbc.sql("""
				insert into appointment(id,user_account_id,specialist_account_id,availability_slot_id,service_credit_id,
				 status,modality,scheduled_start_at,scheduled_end_at,display_timezone,requested_at,decision_deadline_at,
				 idempotency_key,created_at,updated_at)
				 values(:id,:owner,:specialist,:slot,:credit,'REQUESTED','IN_APP_CHAT',:start,:end,
				 'Asia/Ho_Chi_Minh',:requested,:deadline,:key,:requested,:requested)
				""").param("id", appointment).param("owner", owner).param("specialist", specialist)
				.param("slot", slot).param("credit", credit).param("start", database(start))
				.param("end", database(start.plusSeconds(3_600)))
				.param("requested", database(start.minusSeconds(7_200)))
				.param("deadline", database(start.minusSeconds(3_600)))
				.param("key", "appointment-command-" + appointment).update();
		jdbc.sql("update service_credit set state='HELD',appointment_id=:appointment where id=:credit")
				.param("appointment", appointment).param("credit", credit).update();
		return appointment;
	}

	private OffsetDateTime database(Instant value) {
		return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return jwt().jwt(token -> token.subject(id.toString()).claim("roles", java.util.List.of("USER")))
				.authorities(new SimpleGrantedAuthority("ROLE_USER"));
	}
}
