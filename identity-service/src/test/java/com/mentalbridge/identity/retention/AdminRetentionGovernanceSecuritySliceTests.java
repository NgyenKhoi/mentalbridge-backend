package com.mentalbridge.identity.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.anonymous;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.identity.account.SecurityAuditEventEntity;
import com.mentalbridge.identity.account.SecurityAuditEventRepository;

@WebMvcTest(AdminRetentionGovernanceController.class)
@Import(AdminRetentionGovernanceSecuritySliceTests.TestSecurityConfig.class)
class AdminRetentionGovernanceSecuritySliceTests {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private AdminRetentionGovernanceService service;

	@org.springframework.boot.test.context.TestConfiguration
	@org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
	@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
	static class TestSecurityConfig {
		@org.springframework.context.annotation.Bean
		public org.springframework.security.web.SecurityFilterChain filterChain(
				org.springframework.security.config.annotation.web.builders.HttpSecurity http) throws Exception {
			http.csrf(csrf -> csrf.disable())
				.authorizeHttpRequests(auth -> auth
					.requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
					.anyRequest().authenticated()
				);
			return http.build();
		}
	}

	@Test
	void controllerHasAdminPreAuthorizeAnnotation() {
		PreAuthorize preAuth = AdminRetentionGovernanceController.class.getAnnotation(PreAuthorize.class);
		assertThat(preAuth).isNotNull();
		assertThat(preAuth.value()).isEqualTo("hasRole('ADMIN')");
	}

	@Test
	void auditLoggerUsesAppropriatePropagation() throws Exception {
		Method deniedMethod = RetentionAuditLogger.class.getMethod("recordDenied", SecurityAuditEventEntity.class);
		Transactional deniedTx = deniedMethod.getAnnotation(Transactional.class);
		assertThat(deniedTx).isNotNull();
		assertThat(deniedTx.propagation()).isEqualTo(Propagation.REQUIRES_NEW);

		Method successMethod = RetentionAuditLogger.class.getMethod("recordSuccess", SecurityAuditEventEntity.class);
		Transactional successTx = successMethod.getAnnotation(Transactional.class);
		assertThat(successTx).isNotNull();
		assertThat(successTx.propagation()).isEqualTo(Propagation.REQUIRED);

		// Verify execution saves to repository
		SecurityAuditEventRepository repo = mock(SecurityAuditEventRepository.class);
		RetentionAuditLogger logger = new RetentionAuditLogger(repo);
		SecurityAuditEventEntity deniedEvent = new SecurityAuditEventEntity(
				UUID.randomUUID(), null, null, "TEST", "DENIED", "REASON",
				UUID.randomUUID(), null, "IDENTITY", "DATA_RETENTION",
				java.time.Instant.now(), java.time.Instant.now()
		);
		logger.recordDenied(deniedEvent);
		verify(repo).save(deniedEvent);

		SecurityAuditEventEntity successEvent = new SecurityAuditEventEntity(
				UUID.randomUUID(), null, null, "TEST", "SUCCEEDED", "REASON",
				UUID.randomUUID(), null, "IDENTITY", "DATA_RETENTION",
				java.time.Instant.now(), java.time.Instant.now()
		);
		logger.recordSuccess(successEvent);
		verify(repo).save(successEvent);
	}

	@Test
	void anonymousRequestIsUnauthorizedOrForbidden() throws Exception {
		mvc.perform(get("/api/v1/admin/retention/policies").with(anonymous()))
				.andExpect(status().is4xxClientError());
	}

	@Test
	void userRoleIsForbidden() throws Exception {
		mvc.perform(get("/api/v1/admin/retention/policies")
				.with(user("regular-user").roles("USER")))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/v1/admin/retention/deletions")
				.with(user("regular-user").roles("USER")))
				.andExpect(status().isForbidden());
	}

	@Test
	void userRoleIsForbiddenOnMutatingEndpoints() throws Exception {
		UUID randomId = UUID.randomUUID();
		mvc.perform(post("/api/v1/admin/retention/policies/MB-IDENTITY-REPORTING-001/sweep")
				.with(user("regular-user").roles("USER")))
				.andExpect(status().isForbidden());

		mvc.perform(post("/api/v1/admin/retention/deletions/" + randomId + "/retry")
				.with(user("regular-user").roles("USER")))
				.andExpect(status().isForbidden());

		mvc.perform(post("/api/v1/admin/retention/deletions/" + randomId + "/holds")
				.contentType("application/json")
				.content("{\"holdType\":\"LEGAL_HOLD\",\"holdReason\":\"STATUTORY_INQUIRY\"}")
				.with(user("regular-user").roles("USER")))
				.andExpect(status().isForbidden());

		mvc.perform(delete("/api/v1/admin/retention/deletions/" + randomId + "/holds")
				.with(user("regular-user").roles("USER")))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminRoleIsAuthorized() throws Exception {
		mvc.perform(get("/api/v1/admin/retention/policies")
				.with(user("admin-user").roles("ADMIN")))
				.andExpect(status().isOk());

		mvc.perform(get("/api/v1/admin/retention/deletions")
				.with(user("admin-user").roles("ADMIN")))
				.andExpect(status().isOk());
	}

	@Test
	void adminRoleIsAuthorizedOnActualMutatingEndpoints() throws Exception {
		UUID requestId = UUID.randomUUID();

		mvc.perform(post("/api/v1/admin/retention/policies/MB-IDENTITY-REPORTING-001/sweep")
				.with(user("admin-user").roles("ADMIN")))
				.andExpect(status().isOk());

		mvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/retry")
				.with(user("admin-user").roles("ADMIN")))
				.andExpect(status().isOk());

		mvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/holds")
				.contentType("application/json")
				.content("{\"holdType\":\"OPERATIONAL_HOLD\",\"holdReason\":\"AUDIT_COMPLIANCE_HOLD\"}")
				.with(user("admin-user").roles("ADMIN")))
				.andExpect(status().isOk());

		mvc.perform(delete("/api/v1/admin/retention/deletions/" + requestId + "/holds")
				.with(user("admin-user").roles("ADMIN")))
				.andExpect(status().isOk());
	}
}

