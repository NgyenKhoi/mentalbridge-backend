package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.security.KeyPair;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.ResultActions;

class CommunityChatNotificationIntegrationTests extends ChatGroupIntegrationSupport {
    static final KeyPair KEYS = CommunityChatRealtimeIntegrationTests.keys();
    static final String PATH = ChatNotificationSecurity.PATH;
    @DynamicPropertySource static void notification(DynamicPropertyRegistry registry) {
        registry.add("mentalbridge.community.chat-notification.enabled", () -> true);
        registry.add("mentalbridge.community.chat-notification.key-id", () -> "content-test");
        registry.add("mentalbridge.community.chat-notification.public-key", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
    }
    @Test void pendingRequestIsAuthorizedWithoutGrantingConversationAndStopsAfterDecision() throws Exception {
        var request = pending(a, bp); var conversation = conversation(request);
        var body = target(b, "DIRECT_REQUESTED", request, conversation, ap);
        check(body).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().json("{\"allowed\":true,\"decisionVersion\":\"0\"}"));
        mvc.perform(get(ROOT + "/conversations/{id}", conversation).with(user(b))).andExpect(status().isNotFound());
        check(target(a, "DIRECT_REQUESTED", request, conversation, bp)).andExpect(status().isNotFound());
        check(target(b, "MESSAGE_RECEIVED", request, conversation, ap)).andExpect(status().isNotFound());
        decision(b, request, "DECLINE", "0").andExpect(status().isNoContent());
        check(body).andExpect(status().isNotFound());
    }
    @Test void exactMessageRecipientActorAndConversationAreBoundAndContentNeverLeavesAuthorization() throws Exception {
        preference(b, "ANYONE", "0").andExpect(status().isOk());
        var conversation = UUID.fromString(json(start(a, bp, UUID.randomUUID())).path("conversationId").asText());
        var message = send(a, conversation);
        var result = check(target(b, "MESSAGE_RECEIVED", message, conversation, ap)).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("synthetic-private-content", ap.toString(), b.toString());
        check(target(b, "MESSAGE_RECEIVED", message, UUID.randomUUID(), ap)).andExpect(status().isNotFound());
        check(target(b, "MESSAGE_RECEIVED", message, conversation, profile(outsider))).andExpect(status().isNotFound());
        check(target(a, "MESSAGE_RECEIVED", message, conversation, ap)).andExpect(status().isNotFound());
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        check(target(b, "MESSAGE_RECEIVED", message, conversation, ap)).andExpect(status().isNotFound());
    }
    @Test void pendingInvitationDoesNotRequireMembershipButChecksCurrentInviterAndLifecycle() throws Exception {
        var group = group(); var invitation = invite(a, group, bp);
        var body = target(b, "GROUP_INVITED", invitation, group, ap);
        check(body).andExpect(status().isOk());
        mvc.perform(get(ROOT + "/conversations/{id}", group).with(user(b))).andExpect(status().isNotFound());
        check(target(b, "DIRECT_REQUESTED", invitation, group, ap)).andExpect(status().isNotFound());
        decide(b, invitation, "ACCEPT", 0).andExpect(status().isOk());
        check(body).andExpect(status().isNotFound());
    }
    @Test void groupMessageCannotSurviveRemovalOrBeReusedAfterRejoin() throws Exception {
        var group = group(); join(b, group, bp); var message = send(a, group);
        var body = target(b, "MESSAGE_RECEIVED", message, group, ap);
        check(body).andExpect(status().isOk());
        remove(a, group, bp).andExpect(status().isNoContent());
        check(body).andExpect(status().isNotFound());
        join(b, group, bp);
        check(body).andExpect(status().isNotFound());
        check(target(b, "MESSAGE_RECEIVED", send(a, group), group, ap)).andExpect(status().isOk());
    }
    @Test void expiryAccountDenialAndOwnerUncertaintyFailClosed() throws Exception {
        var request = pending(a, bp); var body = target(b, "DIRECT_REQUESTED", request, conversation(request), ap);
        denied.add(a); check(body).andExpect(status().isNotFound()); denied.clear();
        doThrow(new IllegalStateException("synthetic failure")).when(identity).authorize(anyString(), anyString(), any());
        check(body).andExpect(status().isServiceUnavailable());
        reset(identity);
        doAnswer(call -> new ChatIdentityHttpClient.Result(call.getArgument(2, ChatIdentityHttpClient.Query.class).accountIds()))
                .when(identity).authorize(anyString(), anyString(), any());
        clock.advance(java.time.Duration.ofDays(8));
        check(body).andExpect(status().isNotFound());
    }
    @Test void blockCommittedDuringIdentityReadWinsBeforeLocalAuthorization() throws Exception {
        var request = pending(a, bp); var body = target(b, "DIRECT_REQUESTED", request, conversation(request), ap);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.sql("insert into community_block(blocker_profile_id,blocked_profile_id,created_at) values(:a,:b,now())")
                    .param("a", ap).param("b", bp).update();
            return new ChatIdentityHttpClient.Result(call.getArgument(2, ChatIdentityHttpClient.Query.class).accountIds());
        }).when(identity).authorize(anyString(), anyString(), any());
        check(body).andExpect(status().isNotFound());
    }
    @Test void dedicatedServiceCredentialAndClosedInputAreRequired() throws Exception {
        var request = pending(a, bp); var body = target(b, "DIRECT_REQUESTED", request, conversation(request), ap);
        call(token("community:subscribe", "community-service", 30), body).andExpect(status().isForbidden());
        call(token("community:notification:authorize", "wrong-service", 30), body).andExpect(status().isUnauthorized());
        call(token("community:notification:authorize", "community-service", 60), body).andExpect(status().isUnauthorized());
        mvc.perform(post(PATH).with(user(b)).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)))
                .andExpect(status().isForbidden());
        var extra = new HashMap<>(body); extra.put("allowed", true); check(extra).andExpect(status().isBadRequest());
        var missing = new HashMap<>(body); missing.remove("actorCommunityProfileId"); check(missing).andExpect(status().isBadRequest());
    }
    UUID conversation(UUID request) { return jdbc.sql("select conversation_id from community_chat_request where id = :id").param("id", request).query(UUID.class).single(); }
    UUID send(UUID actor, UUID conversation) throws Exception {
        return UUID.fromString(json(mvc.perform(post(ROOT + "/conversations/{id}/messages", conversation).with(user(actor))
                .header("Idempotency-Key", UUID.randomUUID()).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "content", "synthetic-private-content"))))
                .andExpect(status().isCreated())).path("message").path("messageId").asText());
    }
    Map<String,Object> target(UUID recipient, String kind, UUID id, UUID conversation, UUID actor) {
        return Map.of("recipientRoutingReference", recipient, "kind", kind, "targetId", id, "conversationId", conversation, "actorCommunityProfileId", actor);
    }
    ResultActions check(Map<String,Object> body) throws Exception { return call(token("community:notification:authorize", "community-service", 30), body); }
    ResultActions call(String token, Map<String,Object> body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    String token(String scope, String audience, int lifetime) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("content-notification-service").subject("content-notification-service")
                .audience(audience).jwtID(UUID.randomUUID().toString()).issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(clock.instant().plusSeconds(lifetime))).claim("scope", scope).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("content-test").build(), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate())); return jwt.serialize();
    }
}
