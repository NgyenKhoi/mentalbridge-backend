package com.mentalbridge.identity.retention;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.account.AccountStatus;
import com.mentalbridge.identity.shared.ApiExceptionHandler;

class AdminRetentionGovernanceControllerTests {

	private AdminRetentionGovernanceService service;
	private MockMvc mockMvc;
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		service = mock(AdminRetentionGovernanceService.class);
		objectMapper = new ObjectMapper();
		mockMvc = MockMvcBuilders.standaloneSetup(new AdminRetentionGovernanceController(service))
				.setControllerAdvice(new ApiExceptionHandler())
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.build();
	}

	@Test
	void getPoliciesReturns200WithListAndCorrelationHeader() throws Exception {
		var policy = new AdminRetentionGovernanceService.RetentionPolicyResponse(
				"MB-IDENTITY-RETENTION-001", "Identity Retention", "1.0",
				RetentionOwnerService.IDENTITY, RetentionPolicyStatus.APPROVED,
				"365 days retention", 365, "IDENTITY_RETENTION_SWEEP", RetentionJobStatus.ACTIVE,
				true, true, false, null, null
		);
		when(service.getRetentionPolicies()).thenReturn(List.of(policy));

		mockMvc.perform(get("/api/v1/admin/retention/policies"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.length()").value(1))
				.andExpect(jsonPath("$[0].policyId").value("MB-IDENTITY-RETENTION-001"))
				.andExpect(jsonPath("$[0].automatedExecutionSupported").value(true));
	}

	@Test
	void triggerSweepReturns200ForSupportedPolicy() throws Exception {
		var record = new AdminRetentionGovernanceService.RetentionExecutionResponse(
				UUID.randomUUID(), "MB-IDENTITY-RETENTION-001", "IDENTITY", "IDENTITY_RETENTION_SWEEP",
				"SUCCEEDED", Instant.now(), Instant.now(), 20, 5, 0, 0, null
		);
		when(service.triggerRetentionSweep(eq("MB-IDENTITY-RETENTION-001"), any(), any()))
				.thenReturn(record);

		mockMvc.perform(post("/api/v1/admin/retention/policies/MB-IDENTITY-RETENTION-001/sweep"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.jobStatus").value("SUCCEEDED"))
				.andExpect(jsonPath("$.recordsPurged").value(5));
	}

	@Test
	void triggerSweepFailsWith422ForUnsupportedCapability() throws Exception {
		when(service.triggerRetentionSweep(eq("MB-PRIVACY-CARE-001"), any(), any()))
				.thenThrow(new UnsupportedRetentionCapabilityException("MB-PRIVACY-CARE-001", "Care owns purge job"));

		mockMvc.perform(post("/api/v1/admin/retention/policies/MB-PRIVACY-CARE-001/sweep"))
				.andExpect(status().isUnprocessableEntity());
	}

	@Test
	void getDeletionsReturns200() throws Exception {
		var item = new AdminRetentionGovernanceService.DeletionRequestResponse(
				UUID.randomUUID(), "tombstone:" + "a".repeat(64), AccountStatus.DELETION_PENDING,
				DeletionRequestStatus.PENDING, DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, Instant.now(), Instant.now(), null, null, List.of()
		);
		when(service.getDeletionRequests(any(), any(), any(), any()))
				.thenReturn(new AdminRetentionGovernanceService.DeletionRequestPage(List.of(item), 1, null));

		mockMvc.perform(get("/api/v1/admin/retention/deletions")
				.param("page", "0")
				.param("limit", "10"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].subjectReference").value("tombstone:" + "a".repeat(64)));
	}

	@Test
	void retryDeletionReturns200OnSuccess() throws Exception {
		UUID requestId = UUID.randomUUID();
		var item = new AdminRetentionGovernanceService.DeletionRequestResponse(
				requestId, "tombstone:" + "b".repeat(64), AccountStatus.DELETED,
				DeletionRequestStatus.COMPLETED, DeletionHoldType.NONE, null, 1, 3,
				DeletionEscalationState.NONE, null, Instant.now(), Instant.now(), Instant.now(), Instant.now(), List.of()
		);
		when(service.retryDeletion(eq(requestId), any(), any())).thenReturn(item);

		mockMvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/retry"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.status").value("COMPLETED"));
	}

	@Test
	void retryDeletionReturns409WhenHoldActive() throws Exception {
		UUID requestId = UUID.randomUUID();
		when(service.retryDeletion(eq(requestId), any(), any()))
				.thenThrow(new DeletionHoldActiveException("LEGAL_HOLD active"));

		mockMvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/retry"))
				.andExpect(status().isConflict());
	}

	@Test
	void retryDeletionReturns409WhenRetryLimitExhausted() throws Exception {
		UUID requestId = UUID.randomUUID();
		when(service.retryDeletion(eq(requestId), any(), any()))
				.thenThrow(new DeletionRetryExhaustedException(3));

		mockMvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/retry"))
				.andExpect(status().isConflict());
	}

	@Test
	void applyHoldReturns200() throws Exception {
		UUID requestId = UUID.randomUUID();
		var item = new AdminRetentionGovernanceService.DeletionRequestResponse(
				requestId, "tombstone:" + "c".repeat(64), AccountStatus.DELETION_PENDING,
				DeletionRequestStatus.HELD, DeletionHoldType.LEGAL_HOLD, "COURT_ORDER", 0, 3,
				DeletionEscalationState.NONE, null, Instant.now(), Instant.now(), null, null, List.of()
		);
		when(service.applyHold(eq(requestId), eq(DeletionHoldType.LEGAL_HOLD), eq(DeletionHoldReason.STATUTORY_INQUIRY), any(), any()))
				.thenReturn(item);

		mockMvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/holds")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"holdType\":\"LEGAL_HOLD\",\"holdReason\":\"STATUTORY_INQUIRY\"}"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.status").value("HELD"))
				.andExpect(jsonPath("$.holdType").value("LEGAL_HOLD"));
	}

	@Test
	void applyHoldReturns409WhenRequestAlreadyCompleted() throws Exception {
		UUID requestId = UUID.randomUUID();
		when(service.applyHold(eq(requestId), eq(DeletionHoldType.LEGAL_HOLD), eq(DeletionHoldReason.STATUTORY_INQUIRY), any(), any()))
				.thenThrow(new org.springframework.web.server.ResponseStatusException(
						org.springframework.http.HttpStatus.CONFLICT, "Không thể áp dụng Hold lên yêu cầu xóa đã hoàn tất (COMPLETED)."));

		mockMvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/holds")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"holdType\":\"LEGAL_HOLD\",\"holdReason\":\"STATUTORY_INQUIRY\"}"))
				.andExpect(status().isConflict());
	}

	@Test
	void applyHoldReturns400WhenHoldReasonInvalid() throws Exception {
		UUID requestId = UUID.randomUUID();
		mockMvc.perform(post("/api/v1/admin/retention/deletions/" + requestId + "/holds")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"holdType\":\"LEGAL_HOLD\",\"holdReason\":\"UNSAFE_FREE_TEXT_REASON\"}"))
				.andExpect(status().isBadRequest());
	}

	@Test
	void releaseHoldReturns200() throws Exception {
		UUID requestId = UUID.randomUUID();
		var item = new AdminRetentionGovernanceService.DeletionRequestResponse(
				requestId, "tombstone:" + "d".repeat(64), AccountStatus.DELETION_PENDING,
				DeletionRequestStatus.PENDING, DeletionHoldType.NONE, null, 0, 3,
				DeletionEscalationState.NONE, null, Instant.now(), Instant.now(), null, null, List.of()
		);
		when(service.releaseHold(eq(requestId), any(), any())).thenReturn(item);

		mockMvc.perform(delete("/api/v1/admin/retention/deletions/" + requestId + "/holds"))
				.andExpect(status().isOk())
				.andExpect(header().exists("X-Correlation-Id"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.holdType").value("NONE"));
	}
}

