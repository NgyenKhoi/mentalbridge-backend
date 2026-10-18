package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "mentalbridge.identity.audit-ingestion.retry-interval=100",
        "mentalbridge.identity.audit-ingestion.max-attempts=-1"
})
class AdministrationAuditKafkaDurabilityIntegrationTests extends IdentityTestProperties {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdministrationAuditKafkaDurabilityIntegrationTests.class);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private KafkaListenerEndpointRegistry registry;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoSpyBean
    private SecurityAuditEventRepository auditRepository;

    @Value("${mentalbridge.identity.audit-ingestion.topic:mentalbridge.admin.audit-event.v1}")
    private String topic;

    @Test
    void auditEventsSurviveDatabaseOutagesAndRestartWithoutLossOrDuplicate() throws Exception {
        UUID adminId = insertAccount("admin-durability@example.com", RoleCode.ADMIN);
        UUID targetId = insertAccount("target-durability@example.com", RoleCode.USER);
        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant occurredAt = Instant.now().minusSeconds(10);

        String eventJson = """
                {
                  "eventId": "%s",
                  "eventType": "consultation.specialist.suspended",
                  "occurredAt": "%s",
                  "producer": "consultation-service",
                  "schemaVersion": "1.0",
                  "sourceService": "CONSULTATION",
                  "domain": "SPECIALIST_REVIEW",
                  "actorId": "%s",
                  "actorType": "ADMIN",
                  "action": "SPECIALIST_SUSPENDED",
                  "result": "SUCCEEDED",
                  "reasonCode": "POLICY_VIOLATION",
                  "correlationId": "%s",
                  "targetAccountId": "%s"
                }
                """.formatted(eventId, occurredAt, adminId, correlationId, targetId);

        AtomicBoolean databaseHealthy = new AtomicBoolean(false);
        AtomicInteger failureAttempts = new AtomicInteger(0);
        CountDownLatch failureThresholdLatch = new CountDownLatch(6);

        doAnswer(invocation -> {
            if (!databaseHealthy.get()) {
                int attempt = failureAttempts.incrementAndGet();
                failureThresholdLatch.countDown();
                LOGGER.info("Simulating database outage on insert attempt {}", attempt);
                throw new DataAccessResourceFailureException("Simulated transient database connection outage (attempt " + attempt + ")");
            }
            return invocation.callRealMethod();
        }).when(auditRepository).insertIfAbsent(any(SecurityAuditEventEntity.class));

        MessageListenerContainer listenerContainer = registry.getListenerContainer("administrationAuditConsumer");
        assertThat(listenerContainer).isNotNull();
        long assignDeadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < assignDeadline && listenerContainer.getAssignedPartitions().isEmpty()) {
            Thread.sleep(100);
        }

        // 1. Send audit event to Kafka
        kafkaTemplate.send(topic, eventId.toString(), eventJson).get(10, TimeUnit.SECONDS);

        // 2. Observe retries exceeding the retry budget (> 5 attempts) without committing or discarding
        boolean reachedThreshold = failureThresholdLatch.await(15, TimeUnit.SECONDS);
        assertThat(reachedThreshold)
                .as("Consumer should retry persistently during database outage (reached >= 6 attempts)")
                .isTrue();
        assertThat(failureAttempts.get()).isGreaterThanOrEqualTo(6);

        // Verify the event is NOT yet inserted in PostgreSQL
        Integer pendingCount = jdbc.sql("select count(*) from security_audit_event where id = :id")
                .param("id", eventId)
                .query(Integer.class)
                .single();
        assertThat(pendingCount).isEqualTo(0);

        // 3. Test restart resilience: stop and start listener container during outage
        listenerContainer.stop();
        assertThat(listenerContainer.isRunning()).isFalse();

        // 4. Restore database health
        databaseHealthy.set(true);

        // 5. Restart listener container; verify it resumes and consumes the uncommitted event
        listenerContainer.start();
        assertThat(listenerContainer.isRunning()).isTrue();

        // 6. Wait for the event to be successfully projected exactly once
        long deadline = System.currentTimeMillis() + 15_000;
        int rowCount = 0;
        while (System.currentTimeMillis() < deadline) {
            rowCount = jdbc.sql("select count(*) from security_audit_event where id = :id")
                    .param("id", eventId)
                    .query(Integer.class)
                    .single();
            if (rowCount > 0) {
                break;
            }
            Thread.sleep(200);
        }

        assertThat(rowCount)
                .as("Event must be successfully projected after database restoration and container restart")
                .isEqualTo(1);

        // Verify content of persisted audit row
        String persistedAction = jdbc.sql("select action from security_audit_event where id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
        assertThat(persistedAction).isEqualTo("SPECIALIST_SUSPENDED");
    }

    private UUID insertAccount(String email, RoleCode role) {
        return jdbc.sql("""
                insert into account (email, password_hash, role_code, status, email_verified_at)
                values (:email, 'bcrypt-test-hash', :role, 'ACTIVE', now()) returning id
                """).param("email", email).param("role", role.name()).query(UUID.class).single();
    }
}
