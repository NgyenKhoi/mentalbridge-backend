package com.mentalbridge.consultation.earnings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.specialist.ConsultationAdminAuditOutbox;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class PayoutDestinationIntegrationTests extends ConsultationTestProperties {

	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired Clock clock;
	@Autowired PayoutProperties configured;
	@Autowired PlatformTransactionManager transactionManager;
	@Autowired ConsultationAdminAuditOutbox audit;
	@Autowired MockMvc mvc;
	@MockitoBean PayoutBankDirectory banks;
	private PayoutDestinationService destinations;
	private PayoutProperties properties;

	@BeforeEach
	void destinationServiceWithSyntheticStagingConfiguration() {
		properties = new PayoutProperties();
		properties.setMode("PAYOS");
		properties.setEncryptionKey(configured.getEncryptionKey());
		properties.setPayosClientId("synthetic-client");
		properties.setPayosApiKey("synthetic-api");
		properties.setPayosChecksumKey("synthetic-checksum");
		var environment = new MockEnvironment();
		environment.setActiveProfiles("staging");
		properties.setEnvironment(environment);
		when(banks.banks()).thenReturn(new PayoutBankDirectory.BankList(clock.instant(),
				List.of(new PayoutBankDirectory.Bank("970436", "Synthetic bank", "SYNTHETIC"))));
		var service = new PayoutDestinationService(jdbc, properties, new PayoutDestinationCipher(properties),
				banks, json, clock, transactionManager, audit);
		var proxy = new ProxyFactory(service);
		proxy.setProxyTargetClass(true);
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		destinations = (PayoutDestinationService) proxy.getProxy();
	}

	@Test
	void newHolderEditAndRevertedBankAllRequireIndependentConfirmationWithoutPlaintextStorage() {
		var owner = specialist();
		var admin = UUID.randomUUID();
		var request = bank("1234567890", "NGUYEN VAN A");
		var pending = destinations.save(owner, request);
		assertThat(pending.status()).isEqualTo("PENDING_CONFIRMATION");
		assertThat(pending.verifiedAt()).isNull();
		assertThat(pending.version()).isZero();
		assertThat(pending.usableForPayout()).isFalse();
		assertThat(pending.bankName()).isEqualTo("Synthetic bank");
		assertThat(destinations.save(owner, request).id()).isEqualTo(pending.id());
		var confirmed = destinations.confirm(admin, pending.id(), "confirm-original-bank-01",
				new ConfirmPayoutDestinationRequest(0L, "ACCOUNT_HOLDER_CHECKED"));
		assertThat(confirmed.status()).isEqualTo("VERIFIED");
		assertThat(confirmed.version()).isOne();
		assertThat(confirmed.usableForPayout()).isTrue();
		var changed = destinations.save(owner, bank("1234567890", "NGUYEN VAN B"));
		assertThat(changed.id()).isNotEqualTo(pending.id());
		assertThat(changed.status()).isEqualTo("PENDING_CONFIRMATION");
		assertThat(destinations.current(owner).id()).isEqualTo(changed.id());
		var replay = destinations.confirm(admin, pending.id(), "confirm-original-bank-01",
				new ConfirmPayoutDestinationRequest(0L, "ACCOUNT_HOLDER_CHECKED"));
		assertThat(replay.status()).isEqualTo("DISABLED");
		assertThat(destinations.current(owner).status()).isEqualTo("PENDING_CONFIRMATION");
		var reverted = destinations.save(owner, request);
		assertThat(reverted.id()).isNotIn(pending.id(), changed.id());
		assertThat(reverted.status()).isEqualTo("PENDING_CONFIRMATION");
		assertThat(reverted.verifiedAt()).isNull();
		assertThat(jdbc.sql("select count(*) from specialist_payout_destination where specialist_account_id=:id")
				.param("id", owner).query(Long.class).single()).isEqualTo(3);
		var ciphertext = jdbc.sql("select destination_ciphertext from specialist_payout_destination where id=:id")
				.param("id", pending.id()).query(String.class).single();
		assertThat(ciphertext).doesNotContain("1234567890", "NGUYEN", "970436", "Synthetic bank");
		var auditPayload = jdbc.sql("select payload::text from consultation_admin_audit_outbox where correlation_id=:id")
				.param("id", pending.id()).query(String.class).single();
		assertThat(auditPayload).contains("PAYOUT_DESTINATION_CONFIRMED").doesNotContain("1234567890", "NGUYEN", "970436");
	}

	@Test
	void staleConfirmationAndConflictingCommandCannotConfirmAnotherRevision() {
		var owner = specialist();
		var admin = UUID.randomUUID();
		var first = destinations.save(owner, bank("1234567890", "NGUYEN VAN A"));
		assertCode(() -> destinations.confirm(admin, first.id(), "confirm-stale-version-01",
				new ConfirmPayoutDestinationRequest(7L, "ACCOUNT_HOLDER_CHECKED")), "PAYOUT_DESTINATION_VERSION_CONFLICT");
		destinations.confirm(admin, first.id(), "confirm-idempotent-key-01",
				new ConfirmPayoutDestinationRequest(0L, "ACCOUNT_HOLDER_CHECKED"));
		var changed = destinations.save(owner, bank("1234567891", "NGUYEN VAN A"));
		assertCode(() -> destinations.confirm(admin, changed.id(), "confirm-idempotent-key-01",
				new ConfirmPayoutDestinationRequest(0L, "ACCOUNT_HOLDER_CHECKED")), "IDEMPOTENCY_KEY_CONFLICT");
		assertCode(() -> destinations.confirm(admin, first.id(), "confirm-old-revision-01",
				new ConfirmPayoutDestinationRequest(2L, "ACCOUNT_HOLDER_CHECKED")), "PAYOUT_DESTINATION_VERSION_CONFLICT");
		assertThat(destinations.current(owner).status()).isEqualTo("PENDING_CONFIRMATION");
	}

	@Test
	void confirmRacingWithEditLeavesOnlyNewUnconfirmedRevisionCurrent() throws Exception {
		var owner = specialist();
		var pending = destinations.save(owner, bank("1234567890", "NGUYEN VAN A"));
		try (var executor = Executors.newFixedThreadPool(2)) {
			Callable<Object> confirm = () -> {
				try {
					return destinations.confirm(UUID.randomUUID(), pending.id(), "confirm-edit-race-key-01",
							new ConfirmPayoutDestinationRequest(0L, "ACCOUNT_HOLDER_CHECKED"));
				}
				catch (ApiException error) { return error.code(); }
			};
			var tasks = executor.invokeAll(List.of(confirm,
					(Callable<Object>) () -> destinations.save(owner, bank("1234567891", "NGUYEN VAN A"))));
			var result = tasks.get(0).get();
			assertThat(result instanceof SpecialistEarningsResponse.Destination
					|| "PAYOUT_DESTINATION_VERSION_CONFLICT".equals(result)).isTrue();
			var changed = (SpecialistEarningsResponse.Destination) tasks.get(1).get();
			assertThat(destinations.current(owner).id()).isEqualTo(changed.id());
			assertThat(destinations.current(owner).status()).isEqualTo("PENDING_CONFIRMATION");
			assertThat(destinations.destination(owner, pending.id()).status()).isEqualTo("DISABLED");
		}
	}

	@Test
	void concurrentConfirmationReplayCreatesOneVersionCommandAndAudit() throws Exception {
		var owner = specialist();
		var admin = UUID.randomUUID();
		var pending = destinations.save(owner, bank("1234567890", "NGUYEN VAN A"));
		Callable<SpecialistEarningsResponse.Destination> confirm = () -> destinations.confirm(admin, pending.id(),
				"confirm-concurrent-replay-01", new ConfirmPayoutDestinationRequest(0L, "ACCOUNT_HOLDER_CHECKED"));
		try (var executor = Executors.newFixedThreadPool(2)) {
			for (var result : executor.invokeAll(List.of(confirm, confirm))) {
				assertThat(result.get().status()).isEqualTo("VERIFIED");
				assertThat(result.get().version()).isOne();
			}
		}
		assertThat(jdbc.sql("select count(*) from payout_destination_confirmation_command where destination_id=:id")
				.param("id", pending.id()).query(Long.class).single()).isOne();
		assertThat(jdbc.sql("select count(*) from consultation_admin_audit_outbox where correlation_id=:id")
				.param("id", pending.id()).query(Long.class).single()).isOne();
		assertThat(destinations.current(owner).version()).isOne();
	}

	@Test
	void everyDestinationAdministrationRouteRejectsUnauthenticatedAndNonAdminCallers() throws Exception {
		var pending = destinations.save(specialist(), bank("1234567890", "NGUYEN VAN A"));
		var listPath = "/api/v1/admin/payout-destinations";
		var detailPath = listPath + "/" + pending.id();
		var body = "{\"expectedVersion\":0,\"reasonCode\":\"ACCOUNT_HOLDER_CHECKED\"}";
		mvc.perform(get(listPath)).andExpect(status().isUnauthorized());
		mvc.perform(get(detailPath)).andExpect(status().isUnauthorized());
		mvc.perform(post(detailPath + "/confirm").header("Idempotency-Key", "confirm-authorization-01")
				.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
		for (var role : List.of("ROLE_USER", "ROLE_SPECIALIST")) {
			var caller = jwt().jwt(token -> token.subject(UUID.randomUUID().toString()))
					.authorities(new SimpleGrantedAuthority(role));
			mvc.perform(get(listPath).with(caller)).andExpect(status().isForbidden());
			mvc.perform(get(detailPath).with(caller)).andExpect(status().isForbidden());
			mvc.perform(post(detailPath + "/confirm").with(caller)
					.header("Idempotency-Key", "confirm-authorization-01")
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
		}
		assertThat(jdbc.sql("select status from specialist_payout_destination where id=:id")
				.param("id", pending.id()).query(String.class).single()).isEqualTo("PENDING_CONFIRMATION");
		assertThat(jdbc.sql("select count(*) from consultation_admin_audit_outbox where correlation_id=:id")
				.param("id", pending.id()).query(Long.class).single()).isZero();
	}

	@Test
	void inspectionAndConfirmationAreAdminOnlyAuditedAndNeverCached() throws Exception {
		var owner = specialist();
		var pending = destinations.save(owner, bank("1234567890", "NGUYEN VAN A"));
		var admin = UUID.randomUUID();
		var path = "/api/v1/admin/payout-destinations/" + pending.id();
		mvc.perform(get(path).with(jwt().jwt(token -> token.subject(owner.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST")))).andExpect(status().isForbidden());
		mvc.perform(get(path)).andExpect(status().isUnauthorized());
		mvc.perform(get(path).with(jwt().jwt(token -> token.subject(admin.toString()))
				.authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.accountReference").value("1234567890"))
				.andExpect(jsonPath("$.accountHolderName").value("NGUYEN VAN A"))
				.andExpect(jsonPath("$.confirmedBy").isEmpty());
		mvc.perform(get("/api/v1/admin/payout-destinations").param("status", "ALL")
				.with(jwt().jwt(token -> token.subject(admin.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].accountReference").doesNotExist());
		mvc.perform(get("/api/v1/admin/payout-destinations").param("limit", "201")
				.with(jwt().jwt(token -> token.subject(admin.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN"))))
				.andExpect(status().isBadRequest());
		for (var body : List.of(
				"{\"expectedVersion\":0,\"reasonCode\":\"ACCOUNT_HOLDER_CHECKED\",\"accountReference\":\"9999999999\"}",
				"{\"expectedVersion\":0,\"reasonCode\":\"AUTOMATIC_CHECK\"}",
				"{\"reasonCode\":\"ACCOUNT_HOLDER_CHECKED\"}")) {
			mvc.perform(post(path + "/confirm").header("Idempotency-Key", "confirm-endpoint-bank-01")
					.with(jwt().jwt(token -> token.subject(admin.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
					.contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
		}
		mvc.perform(post(path + "/confirm").header("Idempotency-Key", "confirm-endpoint-bank-01")
				.with(jwt().jwt(token -> token.subject(admin.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
				.contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0,\"reasonCode\":\"ACCOUNT_HOLDER_CHECKED\"}"))
				.andExpect(status().isOk()).andExpect(jsonPath("$.status").value("VERIFIED"));
		assertThat(jdbc.sql("select count(*) from consultation_admin_audit_outbox where correlation_id=:id")
				.param("id", pending.id()).query(Long.class).single()).isEqualTo(2);
		assertThat(destinations.detail(admin, pending.id()).confirmedBy()).isEqualTo(admin);
		mvc.perform(get("/api/v1/specialist/payout-banks")
				.with(jwt().jwt(token -> token.subject(admin.toString())).authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))).andExpect(status().isForbidden());
		mvc.perform(get("/api/v1/specialist/payout-banks")
				.with(jwt().jwt(token -> token.subject(owner.toString())).authorities(new SimpleGrantedAuthority("ROLE_SPECIALIST"))))
				.andExpect(status().isOk()).andExpect(jsonPath("$.items[0].bin").value("970436"));
	}

	@Test
	void invalidWalletUnknownBinAndDirectoryFailureCannotSaveAndExistingRevisionRemainsReadable() {
		var owner = specialist();
		assertCode(() -> destinations.save(owner, new SavePayoutDestinationRequest("MOMO_WALLET",
				"1234567890", "NGUYEN VAN A", null)), "PAYOUT_DESTINATION_INVALID");
		assertCode(() -> destinations.save(owner, new SavePayoutDestinationRequest("BANK_ACCOUNT",
				"1234567890", "NGUYEN VAN A", "970000")), "PAYOUT_DESTINATION_INVALID");
		var pending = destinations.save(owner, bank("1234567890", "NGUYEN VAN A"));
		when(banks.banks()).thenThrow(PayoutBankDirectory.unavailable());
		assertCode(() -> destinations.save(owner, bank("1234567891", "NGUYEN VAN A")), "PAYOUT_BANK_LIST_UNAVAILABLE");
		assertThat(destinations.current(owner).id()).isEqualTo(pending.id());
		properties.setMode("FAKE");
		assertThat(destinations.current(owner).provider()).isEqualTo("PAYOS");
		assertThat(destinations.current(owner).usableForPayout()).isFalse();
		properties.setEncryptionKey("unavailable");
		assertCode(() -> destinations.detail(UUID.randomUUID(), pending.id()), "PAYOUT_CONFIGURATION_UNAVAILABLE");
	}

	private SavePayoutDestinationRequest bank(String reference, String holder) {
		return new SavePayoutDestinationRequest("BANK_ACCOUNT", reference, holder, "970436");
	}

	private UUID specialist() {
		var id = UUID.randomUUID();
		jdbc.sql("""
				insert into specialist_profile (account_id,display_name,biography,years_experience,timezone,
				 approval_status,submitted_at,reviewed_at,reviewed_by)
				values (:id,'Synthetic specialist','Synthetic profile',5,'Asia/Ho_Chi_Minh',
				 'APPROVED',now(),now(),:admin)
				""").param("id", id).param("admin", UUID.randomUUID()).update();
		return id;
	}

	private void assertCode(Runnable action, String code) {
		assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class,
				error -> assertThat(error.code()).isEqualTo(code));
	}
}
