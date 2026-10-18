package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.shared.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminAccountControllerTests {

    private AdminAccountService service;
    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        service = mock(AdminAccountService.class);
        objectMapper = new ObjectMapper();
        mockMvc = MockMvcBuilders.standaloneSetup(new AdminAccountController(service))
                .setControllerAdvice(new ApiExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @Test
    void searchAccountsReturns200() throws Exception {
        when(service.searchAccounts(any(), any(), any(), any(), any()))
                .thenReturn(new AdminAccountService.AccountPage(List.of(), null));

        mockMvc.perform(get("/api/v1/admin/accounts"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void getAccountDetailReturns200WithEtag() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.getAccountDetail(id))
                .thenReturn(new AccountController.AccountResponse(
                        id, "user@example.com", AccountStatus.ACTIVE, List.of(RoleCode.USER),
                        true, Instant.now(), Instant.now(), 3L));

        mockMvc.perform(get("/api/v1/admin/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().exists("X-Correlation-Id"));
    }

    @Test
    void changeStateReturns200WithEtag() throws Exception {
        UUID id = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        AccountStateChangeRequest req = new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN);
        when(service.changeAccountState(any(), eq(id), any(), eq(2L), any()))
                .thenReturn(new AccountController.AccountResponse(
                        id, "user@example.com", AccountStatus.DISABLED, List.of(RoleCode.USER),
                        true, Instant.now(), Instant.now(), 3L));

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"2\"")
                        .header("X-Correlation-Id", corr.toString())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().string("X-Correlation-Id", corr.toString()));
    }

    @Test
    void changeStateSucceedsWithoutCallerSuppliedCorrelationHeaderAndGeneratesOne() throws Exception {
        UUID id = UUID.randomUUID();
        AccountStateChangeRequest req = new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN);
        ArgumentCaptor<UUID> correlationCaptor = ArgumentCaptor.forClass(UUID.class);
        when(service.changeAccountState(any(), eq(id), any(), eq(2L), correlationCaptor.capture()))
                .thenReturn(new AccountController.AccountResponse(
                        id, "user@example.com", AccountStatus.DISABLED, List.of(RoleCode.USER),
                        true, Instant.now(), Instant.now(), 3L));

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"2\"")
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"3\""))
                .andExpect(header().exists("X-Correlation-Id"));

        assertThat(correlationCaptor.getValue()).isNotNull();
    }

    @Test
    void changeStateReturns412OnVersionMismatch() throws Exception {
        UUID id = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        AccountStateChangeRequest req = new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN);
        when(service.changeAccountState(any(), eq(id), any(), eq(1L), any()))
                .thenThrow(new AccountVersionMismatchException(id, 1L, 2L));

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .header("X-Correlation-Id", corr.toString())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isPreconditionFailed());
    }

    @Test
    void changeStateReturns403OnDedicatedAdminProtection() throws Exception {
        UUID id = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        AccountStateChangeRequest req = new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN);
        when(service.changeAccountState(any(), eq(id), any(), any(Long.class), any()))
                .thenThrow(new DedicatedAdminProtectionException(id));

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .header("X-Correlation-Id", corr.toString())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    @Test
    void changeStateReturns404WhenNotFound() throws Exception {
        UUID id = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        AccountStateChangeRequest req = new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN);
        when(service.changeAccountState(any(), eq(id), any(), any(Long.class), any()))
                .thenThrow(new AccountNotFoundException(id));

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .header("X-Correlation-Id", corr.toString())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isNotFound());
    }

    @Test
    void changeStateReturns400OnInvalidStateTransition() throws Exception {
        UUID id = UUID.randomUUID();
        UUID corr = UUID.randomUUID();
        AccountStateChangeRequest req = new AccountStateChangeRequest(AccountStatus.DISABLED, AccountStateReasonCode.SAFETY_CONCERN);
        when(service.changeAccountState(any(), eq(id), any(), any(Long.class), any()))
                .thenThrow(new InvalidStateTransitionException("Invalid transition"));

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .header("X-Correlation-Id", corr.toString())
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
    @Test
    void changeStateRejectsMalformedIfMatch() throws Exception {
        UUID id = UUID.randomUUID();
        UUID corr = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "not-an-etag")
                        .header("X-Correlation-Id", corr.toString())
                        .content("{\"status\":\"DISABLED\",\"reasonCode\":\"SAFETY_CONCERN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void changeStateRejectsAStatusOutsideTheAdminContract() throws Exception {
        UUID id = UUID.randomUUID();

        mockMvc.perform(put("/api/v1/admin/accounts/" + id + "/state")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "\"1\"")
                        .header("X-Correlation-Id", UUID.randomUUID().toString())
                        .content("{\"status\":\"DELETION_PENDING\",\"reasonCode\":\"SAFETY_CONCERN\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getAccountsSummaryReturns200() throws Exception {
        when(service.getAccountsSummary()).thenReturn(new AdminAccountService.AccountsSummary(
                "IDENTITY",
                Instant.parse("2026-10-05T14:00:00Z"),
                10,
                8,
                1,
                1,
                0,
                new AdminAccountService.AccountsSummary.RoleBreakdown(8, 1, 1)
        ));

        mockMvc.perform(get("/api/v1/admin/accounts/summary")
                        .header("X-Correlation-Id", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"));
    }
}
