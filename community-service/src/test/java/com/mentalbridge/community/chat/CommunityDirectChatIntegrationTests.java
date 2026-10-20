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

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
    "mentalbridge.community.chat.enabled=true",
    "mentalbridge.community.chat.identity-service-token=synthetic-community-chat-token-at-least-32",
    "mentalbridge.community.chat.encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
    "mentalbridge.community.chat.encryption-key-version=test-v1",
    "mentalbridge.community.chat.identity-base-url=http://127.0.0.1:1"
})
@AutoConfigureMockMvc
class CommunityDirectChatIntegrationTests extends CommunityTestProperties {
    static final String ROOT = "/api/v1/community/chat";
    @Autowired MockMvc mvc;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired ChatAccountAuthorizer accounts;
    @MockitoBean ChatIdentityHttpClient identity;
    final Set<UUID> denied = ConcurrentHashMap.newKeySet();
    UUID a, b, outsider, ap, bp;

    @BeforeEach
    void setup() {
        jdbc.sql("truncate table community_profile cascade").update();
        denied.clear();
        ((io.github.resilience4j.circuitbreaker.CircuitBreaker) org.springframework.test.util.ReflectionTestUtils.getField(accounts, "breaker")).reset();
        when(identity.authorize(anyString(), anyString(), any())).thenAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var query = call.getArgument(2, ChatIdentityHttpClient.Query.class);
            return new ChatIdentityHttpClient.Result(query.accountIds().stream().filter(id -> !denied.contains(id)).toList());
        });
        a = UUID.randomUUID(); b = UUID.randomUUID(); outsider = UUID.randomUUID();
        ap = profile(a); bp = profile(b); profile(outsider);
    }

    @Test
    void privacyDefaultsAreExplicitAndReplacementUsesAnExactRevision() throws Exception {
        mvc.perform(get(ROOT + "/privacy").with(user(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("0"))
                .andExpect(jsonPath("$.preferences.directMessagePolicy").value("APPROVAL_REQUIRED"))
                .andExpect(jsonPath("$.preferences.presenceVisible").value(false));
        preference(a, "ANYONE", "0").andExpect(status().isOk()).andExpect(header().string("ETag", "\"1\""));
        preference(a, "APPROVAL_REQUIRED", "0").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        for (var body : List.of("{}", "{\"directMessagePolicy\":\"FRIENDS\"}",
                privacyBody("ANYONE").replace("false", "\"false\""), privacyBody("ANYONE").replace("false", "null"))) {
            mvc.perform(put(ROOT + "/privacy").with(user(a)).header("If-Match", "\"1\"")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        assertThat(count("community_chat_conversation")).isZero();
    }

    @Test
    void pendingRequestIsTextFreeRecipientOwnedAndActivatesOnlyOnExplicitAccept() throws Exception {
        var requestId = pending(a, bp);
        var hiddenConversation = jdbc.sql("select id from community_chat_conversation").query(UUID.class).single();
        mvc.perform(get(ROOT + "/conversations/{id}", hiddenConversation).with(user(a))).andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/requests").with(user(b))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].requestId").value(requestId.toString()))
                .andExpect(jsonPath("$.items[0].requester.communityProfileId").value(ap.toString()))
                .andExpect(jsonPath("$.items[0].requester.accountSubject").doesNotExist())
                .andExpect(jsonPath("$.items[0].content").doesNotExist());
        decision(outsider, requestId, "ACCEPT", "0").andExpect(status().isNotFound());
        decision(a, requestId, "ACCEPT", "0").andExpect(status().isNotFound());
        decision(b, requestId, "ACCEPT", "99").andExpect(status().isConflict());
        var accepted = json(decision(b, requestId, "ACCEPT", "0").andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\"")));
        var conversation = UUID.fromString(accepted.path("conversationId").asText());
        decision(b, requestId, "ACCEPT", "0").andExpect(status().isOk());
        decision(b, requestId, "DECLINE", "1").andExpect(status().isConflict());
        mvc.perform(get(ROOT + "/conversations/{id}", conversation).with(user(a))).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("DIRECT")).andExpect(jsonPath("$.lastSequence").doesNotExist())
                .andExpect(jsonPath("$.unreadCount").value(0));
        mvc.perform(get(ROOT + "/conversations/{id}", conversation).with(user(outsider))).andExpect(status().isNotFound());
        assertThat(count("community_chat_conversation")).isOne();
        assertThat(count("community_chat_request")).isOne();
        assertThat(count("community_chat_outbox")).isEqualTo(2);
        for (var wire : jdbc.sql("select event_payload::text from community_chat_outbox").query(String.class).list()) {
            var fact = mapper.readTree(wire);
            assertThat(fact.path("occurredAt").isTextual()).isTrue();
            assertThat(Instant.parse(fact.path("occurredAt").asText()).getNano() % 1000).isZero();
            assertThat(fact.path("aggregateId").asText()).isEqualTo(hiddenConversation.toString());
            var payload = fact.path("payload");
            assertThat(payload.has("content")).isFalse();
            if (payload.path("kind").asText().equals("DIRECT_REQUESTED")) {
                assertThat(payload.path("actorCommunityProfileId").asText()).isEqualTo(ap.toString());
                assertThat(payload.path("recipientRoutingReference").asText()).isEqualTo(b.toString());
            }
        }
    }

    @Test
    void anyoneAndConcurrentRetriesKeepOneUnorderedPairAndBindTheIdempotencyKey() throws Exception {
        preference(b, "ANYONE", "0").andExpect(status().isOk());
        var key = UUID.randomUUID();
        var conversation = UUID.fromString(json(start(a, bp, key).andExpect(status().isCreated())).path("conversationId").asText());
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = new ArrayList<Callable<Integer>>();
            for (int i = 0; i < 8; i++) tasks.add(() -> start(a, bp, key).andReturn().getResponse().getStatus());
            for (var future : executor.invokeAll(tasks)) assertThat(future.get()).isEqualTo(201);
        }
        var reverse = json(start(b, ap, UUID.randomUUID()).andExpect(status().isCreated()));
        assertThat(reverse.path("conversationId").asText()).isEqualTo(conversation.toString());
        start(a, profile(UUID.randomUUID()), key).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
        assertThat(count("community_chat_conversation")).isOne();
        assertThat(count("community_chat_request")).isZero();
        assertThat(count("community_chat_command")).isEqualTo(2);
    }

    @Test
    void crossedRequestsAndChangingToAnyoneNeverAutoAccept() throws Exception {
        var request = pending(a, bp);
        preference(b, "ANYONE", "0").andExpect(status().isOk());
        var reverse = json(start(b, ap, UUID.randomUUID()).andExpect(status().isCreated()));
        assertThat(reverse.path("requestId").asText()).isEqualTo(request.toString());
        mvc.perform(get(ROOT + "/conversations").with(user(a))).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        assertThat(count("community_chat_request")).isOne();
        decision(b, request, "ACCEPT", "0").andExpect(status().isOk());
    }

    @Test
    void declineSurvivesNewKeysPreferencesAndUnblockButRecipientCanInitiateReverse() throws Exception {
        var request = pending(a, bp);
        decision(b, request, "DECLINE", "0").andExpect(status().isNoContent());
        decision(b, request, "DECLINE", "0").andExpect(status().isNoContent());
        preference(b, "ANYONE", "0").andExpect(status().isOk());
        start(a, bp, UUID.randomUUID()).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REQUEST_NOT_ALLOWED"));
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        start(a, bp, UUID.randomUUID()).andExpect(status().isConflict());
        var reverse = json(start(b, ap, UUID.randomUUID()).andExpect(status().isCreated()));
        assertThat(reverse.path("state").asText()).isEqualTo("PENDING");
        assertThat(reverse.path("requestId").asText()).isNotEqualTo(request.toString());
        decision(a, UUID.fromString(reverse.path("requestId").asText()), "ACCEPT", "0").andExpect(status().isOk());
    }

    @Test
    void blockDecisionUsesBilateralAuthorityAndDoesNotRecreateAPendingRequestOnUnblock() throws Exception {
        var request = pending(a, bp);
        decision(b, request, "BLOCK", "0").andExpect(status().isNoContent());
        decision(b, request, "BLOCK", "0").andExpect(status().isNoContent());
        assertThat(count("community_block")).isOne();
        start(a, bp, UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        start(a, bp, UUID.randomUUID()).andExpect(status().isConflict());
        assertThat(count("community_chat_request")).isOne();
    }

    @Test
    void expiredRequestCannotBeAcceptedOrRecreatedByAnUncertainRetry() throws Exception {
        var key = UUID.randomUUID();
        var request = UUID.fromString(json(start(a, bp, key).andExpect(status().isCreated())).path("requestId").asText());
        jdbc.sql("update community_chat_request set created_at = now() - interval '8 days', expires_at = now() - interval '1 day'").update();
        decision(b, request, "ACCEPT", "0").andExpect(status().isConflict());
        start(a, bp, key).andExpect(status().isConflict());
        var replacement = json(start(a, bp, UUID.randomUUID()).andExpect(status().isCreated()));
        assertThat(replacement.path("requestId").asText()).isNotEqualTo(request.toString());
        assertThat(jdbc.sql("select state from community_chat_request where id = :id").param("id", request).query(String.class).single()).isEqualTo("EXPIRED");
    }

    @Test
    void newRequestsAreBoundedAcrossDevicesAndRetriesDoNotConsumeExtraRequests() throws Exception {
        var firstKey = UUID.randomUUID();
        start(a, bp, firstKey).andExpect(status().isCreated());
        for (int i = 0; i < 4; i++) start(a, profile(UUID.randomUUID()), UUID.randomUUID()).andExpect(status().isCreated());
        start(a, bp, firstKey).andExpect(status().isCreated());
        start(a, profile(UUID.randomUUID()), UUID.randomUUID()).andExpect(status().isTooManyRequests()).andExpect(header().exists("Retry-After"));
        assertThat(count("community_chat_request")).isEqualTo(5);
        assertThat(count("community_chat_command")).isEqualTo(5);
    }

    @Test
    void inactiveAccountsAndOwnerOutageFailClosedWithoutPersistingCommands() throws Exception {
        denied.add(a);
        start(a, bp, UUID.randomUUID()).andExpect(status().isNotFound());
        denied.clear(); denied.add(b);
        start(a, bp, UUID.randomUUID()).andExpect(status().isNotFound());
        denied.clear();
        doThrow(new RuntimeException("synthetic timeout")).when(identity).authorize(anyString(), anyString(), any());
        start(a, bp, UUID.randomUUID()).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("DEPENDENCY_UNAVAILABLE"));
        assertThat(count("community_chat_conversation")).isZero();
        assertThat(count("community_chat_command")).isZero();
    }

    @Test
    void rejectsAnonymousOtherRolesSpoofedIdentityAndUnsupportedText() throws Exception {
        mvc.perform(get(ROOT + "/privacy")).andExpect(status().isUnauthorized());
        for (var role : List.of("ROLE_ADMIN", "ROLE_SPECIALIST")) {
            mvc.perform(get(ROOT + "/privacy").with(jwt().jwt(j -> j.subject(a.toString()))
                    .authorities(new SimpleGrantedAuthority(role)))).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        }
        for (var extra : List.of("content", "actorId", "recipientAccountId", "anonymousPostId")) {
            mvc.perform(post(ROOT + "/direct-conversations").with(user(a)).header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("recipientCommunityProfileId", bp, extra, "forged"))))
                    .andExpect(status().isBadRequest());
        }
        start(a, ap, UUID.randomUUID()).andExpect(status().isNotFound());
        start(a, UUID.randomUUID(), UUID.randomUUID()).andExpect(status().isNotFound());
        mvc.perform(post(ROOT + "/direct-conversations").with(user(a)).header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("recipientCommunityProfileId", bp))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsDuplicateFieldsTrailingJsonAndBodiesAboveTheContractLimit() throws Exception {
        for (var body : List.of("{\"recipientCommunityProfileId\":\"" + bp + "\",\"recipientCommunityProfileId\":\"" + ap + "\"}",
                "{\"recipientCommunityProfileId\":\"" + bp + "\"} {}", "{\"content\":\"" + "x".repeat(32769) + "\"}")) {
            mvc.perform(post(ROOT + "/direct-conversations").with(user(a)).header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        }
        assertThat(count("community_chat_command")).isZero();
    }

    @Test
    void simultaneousDecisionsCannotBothCommitDifferentTerminalStates() throws Exception {
        var request = pending(a, bp);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var barrier = new CyclicBarrier(2);
            var tasks = List.<Callable<Integer>>of(
                    () -> { barrier.await(); return decision(b, request, "ACCEPT", "0").andReturn().getResponse().getStatus(); },
                    () -> { barrier.await(); return decision(b, request, "DECLINE", "0").andReturn().getResponse().getStatus(); });
            var results = executor.invokeAll(tasks);
            var statuses = List.of(results.get(0).get(), results.get(1).get());
            assertThat(statuses).contains(409);
            assertThat(statuses.stream().filter(code -> code == 200 || code == 204).count()).isOne();
        }
    }

    @Test
    void requestCursorsAreOpaqueViewerAndVisibilityBoundWithFilteringBeforePagination() throws Exception {
        pending(a, bp); pending(outsider, bp); pending(UUID.randomUUID(), bp);
        var first = json(mvc.perform(get(ROOT + "/requests").with(user(b)).param("limit", "1")).andExpect(status().isOk()));
        var cursor = first.path("nextCursor").asText();
        assertThat(cursor).isNotBlank().doesNotContain(bp.toString()).doesNotContain("2026-");
        mvc.perform(get(ROOT + "/requests").with(user(a)).param("cursor", cursor)).andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/conversations").with(user(b)).param("cursor", cursor)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        mvc.perform(get(ROOT + "/requests").with(user(b)).param("cursor", cursor)).andExpect(status().isNotFound());
        mvc.perform(get(ROOT + "/requests").with(user(b)).param("limit", "50")).andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2)).andExpect(jsonPath("$.nextCursor").isEmpty());
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
    JsonNode json(ResultActions result) throws Exception { return mapper.readTree(result.andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)); }
    static RequestPostProcessor user(UUID actor) { return jwt().jwt(j -> j.subject(actor.toString())).authorities(new SimpleGrantedAuthority("ROLE_USER")); }
}
