package com.mentalbridge.consultation.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.consultation.ConsultationTestProperties;
import com.mentalbridge.consultation.TestcontainersConfiguration;
import com.mentalbridge.consultation.billing.SubscriptionBillingService;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AdminCommercialReconciliationIntegrationTests extends ConsultationTestProperties {

	private static final UUID PLUS = UUID.fromString("62200000-0000-4000-8000-000000000002");

	@Autowired MockMvc mvc;
	@Autowired JdbcClient jdbc;
	@Autowired ObjectMapper json;
	@Autowired SubscriptionBillingService billing;

	@Test
	void adminRoleRequiredForCommercialReconciliationEndpoints() throws Exception {
		var subId = UUID.randomUUID();
		var payoutId = UUID.randomUUID();

		for (var path : new String[] {
				"/api/v1/admin/payments",
				"/api/v1/admin/payouts",
				"/api/v1/admin/commercial/discrepancies",
				"/api/v1/admin/commercial/provenance?paymentId=" + UUID.randomUUID(),
				"/api/v1/admin/subscriptions/" + subId,
				"/api/v1/admin/payouts/" + payoutId
		}) {
			// Unauthenticated -> 401
			mvc.perform(get(path)).andExpect(status().isUnauthorized());

			// USER role -> 403
			mvc.perform(get(path).with(role(UUID.randomUUID(), "ROLE_USER"))).andExpect(status().isForbidden());

			// SPECIALIST role -> 403
			mvc.perform(get(path).with(role(UUID.randomUUID(), "ROLE_SPECIALIST"))).andExpect(status().isForbidden());
		}
	}

	@Test
	void adminCanFilterPaymentsByStatusAndSubscription() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "checkout-" + UUID.randomUUID());

		// ADMIN read with filters
		mvc.perform(get("/api/v1/admin/payments")
				.param("status", "PENDING")
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(1))
				.andExpect(jsonPath("$.items[0].paymentId").value(checkout.paymentId().toString()))
				.andExpect(jsonPath("$.items[0].status").value("PENDING"))
				.andExpect(jsonPath("$.items[0].amountVnd").value(5000));

		// Non-matching filter returns empty list
		mvc.perform(get("/api/v1/admin/payments")
				.param("status", "SUCCEEDED")
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.count").value(0))
				.andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void adminCanReadSubscriptionDetail() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "detail-test-" + UUID.randomUUID());

		mvc.perform(get("/api/v1/admin/subscriptions/{subscriptionId}", checkout.subscriptionId())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.subscriptionId").value(checkout.subscriptionId().toString()))
				.andExpect(jsonPath("$.planCode").value("PLUS"))
				.andExpect(jsonPath("$.status").value("PENDING_PAYMENT"))
				.andExpect(jsonPath("$.payments[0].paymentId").value(checkout.paymentId().toString()));

		// Non-existent subscription returns 404
		mvc.perform(get("/api/v1/admin/subscriptions/{subscriptionId}", UUID.randomUUID())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isNotFound());
	}

	@Test
	void adminCanReadCommercialDiscrepancies() throws Exception {
		var result = mvc.perform(get("/api/v1/admin/commercial/discrepancies")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andReturn();

		var body = json.readTree(result.getResponse().getContentAsByteArray());
		assertThat(body.has("generatedAt")).isTrue();
		assertThat(body.has("totalDiscrepancies")).isTrue();
		assertThat(body.has("webhooks")).isTrue();
		assertThat(body.has("payouts")).isTrue();
		assertThat(body.has("heldEarnings")).isTrue();
		assertThat(body.has("disputes")).isTrue();
	}

	@Test
	void adminCanTraceProvenanceAcrossPaymentSubscription() throws Exception {
		var userId = UUID.randomUUID();
		var checkout = billing.checkout(userId, PLUS, "trace-test-" + UUID.randomUUID());

		// Trace by paymentId
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("paymentId", checkout.paymentId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.queryIdentifierType").value("PAYMENT"))
				.andExpect(jsonPath("$.queryIdentifierValue").value(checkout.paymentId().toString()))
				.andExpect(jsonPath("$.subscription.subscriptionId").value(checkout.subscriptionId().toString()))
				.andExpect(jsonPath("$.payments[0].paymentId").value(checkout.paymentId().toString()));

		// Trace by subscriptionId
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.param("subscriptionId", checkout.subscriptionId().toString())
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.found").value(true))
				.andExpect(jsonPath("$.subscription.subscriptionId").value(checkout.subscriptionId().toString()));

		// Missing parameter returns 400
		mvc.perform(get("/api/v1/admin/commercial/provenance")
				.with(role(UUID.randomUUID(), "ROLE_ADMIN")))
				.andExpect(status().isBadRequest());
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor role(UUID id, String authority) {
		return jwt().jwt(token -> token.subject(id.toString())).authorities(new SimpleGrantedAuthority(authority));
	}
}

