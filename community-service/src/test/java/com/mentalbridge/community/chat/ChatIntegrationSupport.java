package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.community.CommunityTestProperties;
import com.mentalbridge.community.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@Import({TestcontainersConfiguration.class, ChatIntegrationSupport.ClockConfiguration.class})
@SpringBootTest(properties = {
    "mentalbridge.community.chat.enabled=true",
    "mentalbridge.community.chat.identity-service-token=synthetic-community-chat-token-at-least-32",
    "mentalbridge.community.chat.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "mentalbridge.community.chat.encryption-key-version=test-v1",
    "mentalbridge.community.chat.identity-base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
abstract class ChatIntegrationSupport extends CommunityTestProperties {
    static final String ROOT = "/api/v1/community/chat";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ChatAccountAuthorizer accounts;
    @MockitoBean ChatIdentityHttpClient identity;
    @MockitoBean ChatRetentionSchedule retentionSchedule;
    @Autowired TestClock clock;
    final Set<UUID> denied = ConcurrentHashMap.newKeySet();
    UUID a, b, outsider, ap, bp;

    @BeforeEach
    void setup() {
        jdbc.sql("truncate table community_profile cascade").update();
        denied.clear();
        clock.now = Instant.parse("2026-10-20T08:00:00Z");
        ((io.github.resilience4j.circuitbreaker.CircuitBreaker) org.springframework.test.util.ReflectionTestUtils.getField(accounts, "breaker")).reset();
        when(identity.authorize(anyString(), anyString(), any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var query = call.getArgument(2, ChatIdentityHttpClient.Query.class);
            return new ChatIdentityHttpClient.Result(query.accountIds().stream().filter(id -> !denied.contains(id)).toList());
        });
        a = UUID.randomUUID(); b = UUID.randomUUID(); outsider = UUID.randomUUID();
        ap = profile(a); bp = profile(b); profile(outsider);
    }

    UUID pending(UUID actor, UUID target) throws Exception {
        var result = json(start(actor, target, UUID.randomUUID()).andExpect(status().isCreated()));
        assertThat(result.path("state").asText()).isEqualTo("PENDING");
        return UUID.fromString(result.path("requestId").asText());
    }
    ResultActions start(UUID actor, UUID target, UUID key) throws Exception {
        return mvc.perform(post(ROOT + "/direct-conversations").with(user(actor)).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("recipientCommunityProfileId", target))));
    }
    ResultActions decision(UUID actor, UUID id, String value, String version) throws Exception {
        return mvc.perform(post(ROOT + "/requests/{id}/decision", id).with(user(actor)).header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("decision", value))));
    }
    ResultActions preference(UUID actor, String value, String version) throws Exception {
        return mvc.perform(put(ROOT + "/privacy").with(user(actor)).header("If-Match", "\"" + version + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(privacyBody(value)));
    }
    static String privacyBody(String mode) {
        return "{\"directMessagePolicy\":\"" + mode + "\",\"presenceVisible\":false,\"invisible\":false,\"typingVisible\":false,\"readReceiptsVisible\":false}";
    }
    UUID profile(UUID subject) {
        var existing = jdbc.sql("select id from community_profile where account_subject = :subject").param("subject", subject).query(UUID.class).optional();
        if (existing.isPresent()) return existing.orElseThrow();
        var id = UUID.randomUUID();
        jdbc.sql("insert into community_profile(id, account_subject, display_name, status, created_at, updated_at, version) values(:id,:subject,'Tên cộng đồng mẫu','ACTIVE',now(),now(),0)")
                .param("id", id).param("subject", subject).update();
        return id;
    }
    long count(String table) { return jdbc.sql("select count(*) from " + table).query(Long.class).single(); }
    JsonNode json(ResultActions result) throws Exception {
        var response = result.andReturn();
        var wire = response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(ChatWireContracts.validResponse(response.getRequest().getRequestURI(), response.getRequest().getMethod(),
                response.getResponse().getStatus(), wire)).isTrue();
        return mapper.readTree(wire);
    }
    static RequestPostProcessor user(UUID actor) { return jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")); }
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        TestClock chatTestClock() { return new TestClock(); }
    }
    static class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-20T08:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
        void advance(Duration duration) { now = now.plus(duration); }
    }
}
