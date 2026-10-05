package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import com.mentalbridge.identity.authentication.JwtTokenService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdministrationAuditIntegrationTests extends IdentityTestProperties {

    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired JwtTokenService tokens;
    @Autowired ObjectMapper objectMapper;
    @Autowired AdministrationAuditEventConsumer auditConsumer;

    @Test
    void adminCanPageFilterAndExportWithIdenticalPrivacySafeSemantics() throws Exception {
        UUID adminId = insertAccount("admin-audit@example.com", RoleCode.ADMIN);
        UUID userId = insertAccount("user-audit@example.com", RoleCode.USER);
        String token = token(adminId, RoleCode.ADMIN);
        Instant now = Instant.now().minusSeconds(5);
        UUID newest = insertAudit(userId, adminId, "ACCOUNT_RESTORED", "SUCCEEDED", "REVIEW_COMPLETED",
                now.minusSeconds(60));
        UUID matching = insertAudit(userId, adminId, "ACCOUNT_DISABLED", "DENIED", "ACCESS_TOKEN_SECRET_123",
                now.minusSeconds(120));
        insertAudit(userId, null, "ACCOUNT_DISABLED", "FAILED", "POLICY_VIOLATION", now.minusSeconds(180));

        String firstBody = mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString())
                        .param("to", now.toString()).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(header().exists("X-Correlation-Id"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].eventId").value(newest.toString()))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andExpect(jsonPath("$.items[0].password").doesNotExist())
                .andExpect(jsonPath("$.items[0].payload").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        String cursor = objectMapper.readTree(firstBody).get("nextCursor").asText();
        mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString())
                        .param("to", now.toString()).param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));

        String target = "account:" + userId;
        String filteredBody = mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString()).param("to", now.toString())
                        .param("sourceService", "IDENTITY").param("domain", "ACCOUNT_ADMINISTRATION")
                        .param("actorType", "ADMIN").param("action", "ACCOUNT_DISABLED")
                        .param("result", "DENIED").param("targetIdentifier", target))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value(matching.toString()))
                .andExpect(jsonPath("$.items[0].reasonCode").doesNotExist())
                .andExpect(jsonPath("$.items[0].targetIdentifier").value(target))
                .andReturn().getResponse().getContentAsString();
        assertThat(filteredBody).doesNotContain("ACCESS_TOKEN_SECRET_123", "password", "journal", "chat body");

        String csv = mvc.perform(get("/api/v1/admin/audit-events/export")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString()).param("to", now.toString())
                        .param("sourceService", "IDENTITY").param("domain", "ACCOUNT_ADMINISTRATION")
                        .param("actorType", "ADMIN").param("action", "ACCOUNT_DISABLED")
                        .param("result", "DENIED").param("targetIdentifier", target))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "text/csv;charset=UTF-8"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("attachment")))
                .andReturn().getResponse().getContentAsString();
        assertThat(csv).contains(matching.toString(), "ACCOUNT_DISABLED", "DENIED", target)
                .doesNotContain(newest.toString(), "ACCESS_TOKEN_SECRET_123", "admin-audit@example.com");
    }

    @Test
    void onlyAdminCanBrowseOrExportAndInvalidFiltersFailClosed() throws Exception {
        UUID userId = insertAccount("user-no-audit@example.com", RoleCode.USER);
        String userToken = token(userId, RoleCode.USER);

        mvc.perform(get("/api/v1/admin/audit-events"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/admin/audit-events/export").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());

        UUID adminId = insertAccount("admin-invalid-audit@example.com", RoleCode.ADMIN);
        String adminToken = token(adminId, RoleCode.ADMIN);
        Instant now = Instant.now().minusSeconds(5);
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization", "Bearer " + adminToken)
                        .param("from", now.toString()).param("to", now.minusSeconds(60).toString()))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization", "Bearer " + adminToken)
                        .param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admin/audit-events").header("Authorization", "Bearer " + adminToken)
                        .param("action", "raw journal content"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deletedSubjectIsReturnedOnlyAsStableTombstone() throws Exception {
        UUID adminId = insertAccount("admin-tombstone@example.com", RoleCode.ADMIN);
        UUID subjectId = insertAccount("deleted-subject@example.com", RoleCode.USER);
        String token = token(adminId, RoleCode.ADMIN);
        Instant occurredAt = Instant.now().minusSeconds(60);
        String hash = subjectHash(subjectId);
        insertAudit(subjectId, adminId, "ACCOUNT_DISABLED", "SUCCEEDED", "POLICY_VIOLATION", occurredAt);
        jdbc.sql("delete from account where id = :id").param("id", subjectId).update();

        String body = mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", occurredAt.minusSeconds(60).toString())
                        .param("to", occurredAt.plusSeconds(60).toString())
                        .param("targetIdentifier", "tombstone:" + hash))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].targetIdentifier").value("tombstone:" + hash))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(subjectId.toString(), "deleted-subject@example.com");
    }

    @Test
    void crossServiceEventsIngestedViaKafkaEventPathProjectSafelyWithDeduplicationAndPrivacy() throws Exception {
        UUID adminId = insertAccount("admin-cross@example.com", RoleCode.ADMIN);
        UUID userId = insertAccount("user-cross@example.com", RoleCode.USER);
        String token = token(adminId, RoleCode.ADMIN);
        Instant now = Instant.now().minusSeconds(5);

        UUID consultationEventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        // 1. Ingest cross-service event from CONSULTATION via real consumer with sensitive payload fields
        String consultationEventJson = """
                {
                  "eventId": "%s",
                  "messageType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "producer": "consultation-service",
                  "correlationId": "%s",
                  "payload": {
                    "sourceService": "CONSULTATION",
                    "domain": "SPECIALIST_REVIEW",
                    "actorId": "%s",
                    "action": "SPECIALIST_SUSPENDED",
                    "result": "SUCCEEDED",
                    "reasonCode": "POLICY_VIOLATION",
                    "targetAccountId": "%s",
                    "rawJournal": "PATIENT_CONFIDENTIAL_JOURNAL_NOTES_DO_NOT_STORE",
                    "chatBody": "PRIVATE_CONSULTATION_TRANSCRIPT_BODY",
                    "assessmentAnswers": {"item_1": 3, "item_2": 4},
                    "credentials": "top_secret_bearer_token_xyz",
                    "providerPayload": {"momoPaymentAccount": "999888777"}
                  }
                }
                """.formatted(consultationEventId, now.minusSeconds(50), correlationId, adminId, userId);

        auditConsumer.onMessage(consultationEventJson);

        // 2. Ingest cross-service event from CONTENT with tombstone target
        UUID contentEventId = UUID.randomUUID();
        String tombstoneHash = "e".repeat(64);
        String contentEventJson = """
                {
                  "eventId": "%s",
                  "messageType": "content.resource.published",
                  "occurredAt": "%s",
                  "producer": "content-notification-service",
                  "correlationId": "%s",
                  "payload": {
                    "sourceService": "CONTENT",
                    "domain": "RESOURCE_MANAGEMENT",
                    "actorId": "%s",
                    "action": "RESOURCE_PUBLISHED",
                    "result": "SUCCEEDED",
                    "reasonCode": "REVIEW_COMPLETED",
                    "targetIdentifier": "tombstone:%s"
                  }
                }
                """.formatted(contentEventId, now.minusSeconds(30), UUID.randomUUID(), adminId, tombstoneHash);

        auditConsumer.onMessage(contentEventJson);

        // 3. Browse filtering specifically by CONSULTATION source service and SPECIALIST_REVIEW domain
        String consultationBody = mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString())
                        .param("to", now.toString())
                        .param("sourceService", "CONSULTATION")
                        .param("domain", "SPECIALIST_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value(consultationEventId.toString()))
                .andExpect(jsonPath("$.items[0].sourceService").value("CONSULTATION"))
                .andExpect(jsonPath("$.items[0].domain").value("SPECIALIST_REVIEW"))
                .andExpect(jsonPath("$.items[0].action").value("SPECIALIST_SUSPENDED"))
                .andExpect(jsonPath("$.items[0].reasonCode").value("POLICY_VIOLATION"))
                .andExpect(jsonPath("$.items[0].targetIdentifier").value("account:" + userId))
                .andReturn().getResponse().getContentAsString();

        // 4. Privacy: Verify that NONE of the sensitive payload fields were exposed or persisted
        assertThat(consultationBody)
                .doesNotContain(contentEventId.toString())
                .doesNotContain("PATIENT_CONFIDENTIAL_JOURNAL_NOTES_DO_NOT_STORE")
                .doesNotContain("PRIVATE_CONSULTATION_TRANSCRIPT_BODY")
                .doesNotContain("top_secret_bearer_token_xyz")
                .doesNotContain("999888777");

        // 5. Browse filtering specifically by CONTENT source service and RESOURCE_MANAGEMENT domain
        mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString())
                        .param("to", now.toString())
                        .param("sourceService", "CONTENT")
                        .param("domain", "RESOURCE_MANAGEMENT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].eventId").value(contentEventId.toString()))
                .andExpect(jsonPath("$.items[0].sourceService").value("CONTENT"))
                .andExpect(jsonPath("$.items[0].domain").value("RESOURCE_MANAGEMENT"))
                .andExpect(jsonPath("$.items[0].action").value("RESOURCE_PUBLISHED"))
                .andExpect(jsonPath("$.items[0].targetIdentifier").value("tombstone:" + tombstoneHash));

        // 6. Deduplication & Idempotency: Retrying the exact same event does NOT create duplicate records
        auditConsumer.onMessage(consultationEventJson);

        mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString())
                        .param("to", now.toString())
                        .param("sourceService", "CONSULTATION")
                        .param("domain", "SPECIALIST_REVIEW"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1));

        // 7. Unallowlisted event type is rejected and never projected
        UUID rejectedEventId = UUID.randomUUID();
        String unallowlistedJson = """
                {
                  "eventId": "%s",
                  "messageType": "chat.message.sent",
                  "payload": {
                    "action": "SEND_UNAUTHORIZED_CHAT",
                    "chatBody": "Unallowlisted private message"
                  }
                }
                """.formatted(rejectedEventId);
        auditConsumer.onMessage(unallowlistedJson);

        mvc.perform(get("/api/v1/admin/audit-events")
                        .header("Authorization", "Bearer " + token)
                        .param("from", now.minusSeconds(3_600).toString())
                        .param("to", now.toString())
                        .param("action", "SEND_UNAUTHORIZED_CHAT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    private UUID insertAccount(String email, RoleCode role) {
        return jdbc.sql("""
                insert into account (email, password_hash, role_code, status, email_verified_at)
                values (:email, 'bcrypt-test-hash', :role, 'ACTIVE', now()) returning id
                """).param("email", email).param("role", role.name()).query(UUID.class).single();
    }

    private UUID insertAudit(UUID accountId, UUID actorId, String action, String outcome, String reason, Instant occurredAt) {
        return insertAudit(accountId, actorId, action, outcome, reason, "IDENTITY", "ACCOUNT_ADMINISTRATION", occurredAt);
    }

    private UUID insertAudit(UUID accountId, UUID actorId, String action, String outcome, String reason,
            String sourceService, String domain, Instant occurredAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                insert into security_audit_event
                    (id, account_id, actor_id, action, outcome, reason_code, correlation_id,
                     subject_reference_hash, source_service, domain, occurred_at, created_at)
                values (:id, :accountId, :actorId, :action, :outcome, :reason, :correlationId,
                        encode(digest(:accountIdText, 'sha256'), 'hex'), :sourceService, :domain, :occurredAt, :occurredAt)
                """).param("id", id).param("accountId", accountId).param("actorId", actorId)
                .param("action", action).param("outcome", outcome).param("reason", reason)
                .param("sourceService", sourceService).param("domain", domain)
                .param("correlationId", UUID.randomUUID()).param("accountIdText", accountId.toString())
                .param("occurredAt", java.sql.Timestamp.from(occurredAt)).update();
        return id;
    }

    private String subjectHash(UUID accountId) {
        return jdbc.sql("select encode(digest(:value, 'sha256'), 'hex')")
                .param("value", accountId.toString()).query(String.class).single();
    }

    private String token(UUID accountId, RoleCode role) {
        return tokens.issue(accountId, role, Instant.now()).value();
    }
}
