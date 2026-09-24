package com.mentalbridge.consultation.credits;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

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
import com.mentalbridge.consultation.shared.ApiException;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ServiceCreditFlowIntegrationTests extends ConsultationTestProperties {

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ServiceCreditService credits;

	@Test
	void freeHasNoCreditsAndOnlyUsersCanReadTheirOwnBalance() throws Exception {
		var userId = UUID.randomUUID();
		mvc.perform(get("/api/v1/service-credits").with(user(userId)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountId").value(userId.toString()))
				.andExpect(jsonPath("$.packageCode").value("FREE"))
				.andExpect(jsonPath("$.source").value("DEFAULT_FREE"))
				.andExpect(jsonPath("$.periodStart").isEmpty())
				.andExpect(jsonPath("$.balance.available").value(0))
				.andExpect(jsonPath("$.balance.total").value(0))
				.andExpect(jsonPath("$.policyVersion").value("consultation-credit-v2"))
				.andExpect(jsonPath("$.reservationCapacity.maximum").value(0));
		mvc.perform(get("/api/v1/service-credits").with(role(userId, "ROLE_SPECIALIST")))
				.andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/service-credits")).andExpect(status().isUnauthorized());
	}

	@Test
	void provisionsPlusIdempotentlyAndUpgradesOnlyTheDifference() throws Exception {
		var userId = UUID.randomUUID();
		var from = OffsetDateTime.now().minusHours(1).withNano(0);
		var until = from.plusDays(30);
		insertEntitlement(userId, "PLUS", "PAID", "paid-period-377", null, from, until);

		mvc.perform(get("/api/v1/service-credits").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("PLUS"))
				.andExpect(jsonPath("$.source").value("PAID"))
				.andExpect(jsonPath("$.policyVersion").value("consultation-credit-v2"))
				.andExpect(jsonPath("$.balance.available").value(4))
				.andExpect(jsonPath("$.history.length()").value(4));
		mvc.perform(get("/api/v1/service-credits").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.balance.total").value(4))
				.andExpect(jsonPath("$.history.length()").value(4));

		jdbc.sql("update current_service_entitlement set package_code='PREMIUM', version=version+1 where account_id=:id")
				.param("id", userId).update();
		mvc.perform(get("/api/v1/service-credits").with(user(userId))).andExpect(status().isOk())
				.andExpect(jsonPath("$.packageCode").value("PREMIUM"))
				.andExpect(jsonPath("$.balance.available").value(10))
				.andExpect(jsonPath("$.history.length()").value(10));
	}

	@Test
	void keepsDemoProvenanceAndRecordsHeldReleasedConsumedAndForfeitedHistory() throws Exception {
		var userId = UUID.randomUUID();
		insertEntitlement(userId, "PREMIUM", "DEMO", "controlled-demo-377", UUID.randomUUID(),
				OffsetDateTime.now().minusMinutes(1), OffsetDateTime.now().plusDays(7));
		var initial = credits.current(userId);
		assertThat(initial.source().name()).isEqualTo("DEMO");
		assertThat(initial.balance().available()).isEqualTo(10);
		var creditIds = jdbc.sql("""
				select c.id from service_credit c join service_credit_period p on p.id=c.period_id
				where p.account_id=:accountId order by c.ordinal
				""").param("accountId", userId).query(UUID.class).list();
		var firstAppointment = UUID.randomUUID();
		credits.transition(userId, creditIds.get(0), firstAppointment, CreditEventType.HELD, "hold-command-0000001");
		credits.transition(userId, creditIds.get(0), firstAppointment, CreditEventType.RELEASED, "release-command-0001");
		credits.transition(userId, creditIds.get(0), firstAppointment, CreditEventType.HELD, "hold-command-0000002");
		credits.transition(userId, creditIds.get(0), firstAppointment, CreditEventType.CONSUMED, "consume-command-0001");
		var secondAppointment = UUID.randomUUID();
		credits.transition(userId, creditIds.get(1), secondAppointment, CreditEventType.HELD, "hold-command-0000003");
		credits.transition(userId, creditIds.get(1), secondAppointment, CreditEventType.FORFEITED, "forfeit-command-0001");
		credits.transition(userId, creditIds.get(1), secondAppointment, CreditEventType.FORFEITED, "forfeit-command-0001");

		var result = credits.current(userId);
		assertThat(result.balance().available()).isEqualTo(8);
		assertThat(result.balance().consumed()).isEqualTo(1);
		assertThat(result.balance().forfeited()).isEqualTo(1);
		assertThat(result.balance().releasedTransitions()).isEqualTo(1);
		assertThat(result.history()).extracting(ServiceCreditResponse.LedgerEvent::eventType)
				.contains(CreditEventType.HELD, CreditEventType.RELEASED, CreditEventType.CONSUMED,
						CreditEventType.FORFEITED);
	}

	@Test
	void rollsBalanceIntoTheCurrentPeriodAndRejectsTransitionsFromTheEndedPeriod() {
		var userId = UUID.randomUUID();
		var now = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
		var periodAStart = now.minusHours(2);
		insertEntitlement(userId, "PLUS", "PAID", "paid-period-a", null, periodAStart, now.plusHours(1));

		var periodA = credits.current(userId);
		assertThat(periodA.balance().available()).isEqualTo(4);
		var periodACreditId = jdbc.sql("""
				select c.id from service_credit c
				join service_credit_period p on p.id=c.period_id
				where p.account_id=:accountId and p.source_reference='paid-period-a' and c.ordinal=1
				""").param("accountId", userId).query(UUID.class).single();

		var periodAEnd = now.minusMinutes(30);
		jdbc.sql("""
				update service_credit_period set period_end=:periodEnd, updated_at=:now
				where account_id=:accountId and source_reference='paid-period-a'
				""").param("periodEnd", periodAEnd).param("now", now).param("accountId", userId).update();
		var periodBStart = now.minusMinutes(15);
		jdbc.sql("""
				update current_service_entitlement
				set package_code='PREMIUM', source_reference='paid-period-b',
				    effective_from=:periodStart, effective_until=:periodEnd, version=version+1
				where account_id=:accountId
				""").param("periodStart", periodBStart).param("periodEnd", now.plusDays(30))
				.param("accountId", userId).update();

		var periodB = credits.current(userId);
		assertThat(periodB.packageCode().name()).isEqualTo("PREMIUM");
		assertThat(periodB.periodStart()).isEqualTo(periodBStart.toInstant());
		assertThat(periodB.balance().available()).isEqualTo(10);
		assertThat(periodB.balance().total()).isEqualTo(10);
		assertThat(jdbc.sql("""
				select count(*) from service_credit c
				join service_credit_period p on p.id=c.period_id
				where p.account_id=:accountId
				""").param("accountId", userId).query(Long.class).single()).isEqualTo(14L);

		assertThatThrownBy(() -> credits.transition(userId, periodACreditId, UUID.randomUUID(),
				CreditEventType.HELD, "expired-period-hold-0001"))
				.isInstanceOfSatisfying(ApiException.class,
						exception -> assertThat(exception.code()).isEqualTo("SERVICE_CREDIT_PERIOD_EXPIRED"));
		assertThat(jdbc.sql("select state from service_credit where id=:id").param("id", periodACreditId)
				.query(String.class).single()).isEqualTo("AVAILABLE");
	}

	@Test
	void preservesHistoricalV1PeriodAllocationAndLedgerProvenance() {
		var userId = UUID.randomUUID();
		var from = OffsetDateTime.now().minusHours(1).withNano(0);
		var until = from.plusDays(30);
		insertEntitlement(userId, "PLUS", "PAID", "historical-period", null, from, until);
		var periodId = UUID.randomUUID();
		var creditId = UUID.randomUUID();
		jdbc.sql("""
				insert into service_credit_period (
				 id, account_id, plan_version, credit_policy_version, package_code, source, source_reference,
				 period_start, period_end, allocated_count, created_at, updated_at
				) values (:periodId, :accountId, 'service-entitlement-v1', 'consultation-credit-v1',
				 'PLUS', 'PAID', 'historical-period', :from, :until, 1, now(), now())
				""").param("periodId", periodId).param("accountId", userId).param("from", from).param("until", until).update();
		jdbc.sql("""
				insert into service_credit (id, period_id, ordinal, state, created_at, updated_at)
				values (:creditId, :periodId, 1, 'AVAILABLE', now(), now())
				""").param("creditId", creditId).param("periodId", periodId).update();
		jdbc.sql("""
				insert into service_credit_ledger (
				 id, credit_id, account_id, event_type, idempotency_key, occurred_at
				) values (:id, :creditId, :accountId, 'PROVISIONED', :key, now())
				""").param("id", UUID.randomUUID()).param("creditId", creditId).param("accountId", userId)
				.param("key", "historical-provision:" + creditId).update();

		var result = credits.current(userId);

		assertThat(result.policyVersion()).isEqualTo("consultation-credit-v1");
		assertThat(result.balance().total()).isEqualTo(1);
		assertThat(result.history()).singleElement()
				.extracting(ServiceCreditResponse.LedgerEvent::policyVersion)
				.isEqualTo("consultation-credit-v1");
	}

	private void insertEntitlement(UUID accountId, String packageCode, String source, String sourceReference,
			UUID establishedBy, OffsetDateTime from, OffsetDateTime until) {
		jdbc.sql("""
				insert into current_service_entitlement (
				 account_id, package_code, source, source_reference, established_by,
				 effective_from, effective_until, policy_version
				) values (:id, :packageCode, :source, :reference, :actor, :from, :until, 'service-entitlement-v1')
				""").param("id", accountId).param("packageCode", packageCode).param("source", source)
				.param("reference", sourceReference).param("actor", establishedBy).param("from", from).param("until", until)
				.update();
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor user(UUID id) {
		return role(id, "ROLE_USER");
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor role(UUID id, String authority) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority(authority));
	}
}
