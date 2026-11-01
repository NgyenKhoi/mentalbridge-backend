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

class CommunityChatPresenceIntegrationTests extends ChatGroupIntegrationSupport {
    static final KeyPair KEYS = CommunityChatRealtimeIntegrationTests.keys();
    static final String PATH = "/internal/v1/community/chat/presence-projections";
    @DynamicPropertySource static void realtime(DynamicPropertyRegistry registry) {
        registry.add("mentalbridge.community.chat-realtime.enabled", () -> true);
        registry.add("mentalbridge.community.chat-realtime.key-id", () -> "realtime-test");
        registry.add("mentalbridge.community.chat-realtime.public-key", () -> "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getEncoder().encodeToString(KEYS.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----");
    }

    @Test void directPresenceDefaultsOffAndHonorsOptInInvisibleAndBothBlockDirections() throws Exception {
        var direct = direct();
        presence(a, direct).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(0));
        visible(b, true, false);
        var result = json(presence(a, direct).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")));
        assertThat(result.path("peers").size()).isEqualTo(1);
        assertThat(result.path("peers").get(0).path("communityProfileId").asText()).isEqualTo(bp.toString());
        assertThat(result.path("peers").get(0).path("subjectRoutingReference").asText()).isEqualTo(b.toString());
        assertThat(result.toString()).doesNotContain("displayName", "lastSeen", "createdAt", "sequence");
        visible(b, true, true);
        presence(a, direct).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(0));
        visible(b, true, false);
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        presence(a, direct).andExpect(status().isNotFound());
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        mvc.perform(put("/api/v1/community/blocks/{id}", bp).with(user(a))).andExpect(status().isNoContent());
        presence(a, direct).andExpect(status().isNotFound());
    }

    @Test void groupProjectionContainsOnlyCurrentUnblockedOptedInOtherMembers() throws Exception {
        var group = group(); join(b, group, bp); var outsideProfile = profile(outsider);
        visible(a, true, false); visible(b, true, false); visible(outsider, true, false);
        invite(a, group, outsideProfile);
        presence(outsider, group).andExpect(status().isNotFound());
        presence(a, group).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(1))
                .andExpect(jsonPath("$.peers[0].communityProfileId").value(bp.toString()));
        mvc.perform(put("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        presence(a, group).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(0));
        mvc.perform(delete("/api/v1/community/blocks/{id}", ap).with(user(b))).andExpect(status().isNoContent());
        remove(a, group, bp).andExpect(status().isNoContent());
        presence(b, group).andExpect(status().isNotFound());
        presence(a, group).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(0));
        join(b, group, bp);
        presence(a, group).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(1));
        denied.add(b);
        presence(a, group).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(0));
        presence(b, group).andExpect(status().isNotFound());
    }

    @Test void requesterAndOutsiderCannotUseARoutingIdAsAPresenceGrant() throws Exception {
        pending(a, bp);
        var reserved = jdbc.sql("select id from community_chat_conversation").query(UUID.class).single();
        visible(b, true, false);
        presence(a, reserved).andExpect(status().isNotFound());
        presence(outsider, reserved).andExpect(status().isNotFound());
    }

    @Test void delegationIsConversationBoundAndRejectsPublicIdentitySelectors() throws Exception {
        var direct = direct();
        call(token(a, direct, "community:subscribe", "community-service"), Map.of("conversationId", direct)).andExpect(status().isForbidden());
        call(token(a, direct, "community:presence:project", "wrong-service"), Map.of("conversationId", direct)).andExpect(status().isUnauthorized());
        call(token(a, UUID.randomUUID(), "community:presence:project", "community-service"), Map.of("conversationId", direct)).andExpect(status().isNotFound());
        call(token(a, direct, "community:presence:project", "community-service"), Map.of("conversationId", direct, "viewerId", b)).andExpect(status().isBadRequest());
        mvc.perform(post(PATH).with(user(a)).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("conversationId", direct)))).andExpect(status().isForbidden());
        presence(outsider, direct).andExpect(status().isNotFound());
    }

    @Test void optOutAndCurrentAccountUncertaintyNeverReuseAPositiveProjection() throws Exception {
        var direct = direct(); visible(b, true, false);
        presence(a, direct).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(1));
        visible(b, false, false);
        presence(a, direct).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(0));
        denied.add(b); presence(a, direct).andExpect(status().isNotFound()); denied.clear();
        doThrow(new IllegalStateException("synthetic failure")).when(identity).authorize(anyString(), anyString(), any());
        presence(a, direct).andExpect(status().isServiceUnavailable());
    }

    @Test void privacyChangedDuringIdentityCheckIsReadAgainInTheOwnerTransaction() throws Exception {
        var direct = direct(); visible(b, true, false);
        doAnswer(call -> {
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            jdbc.sql("update community_chat_privacy set presence_visible = false, version = version + 1, visibility_epoch = visibility_epoch + 1 where profile_id = :id")
                    .param("id", bp).update();
            return new ChatIdentityHttpClient.Result(call.getArgument(2, ChatIdentityHttpClient.Query.class).accountIds());
        }).when(identity).authorize(anyString(), anyString(), any());
        presence(a, direct).andExpect(status().isOk()).andExpect(jsonPath("$.peers.length()").value(0));
    }

    UUID direct() throws Exception {
        preference(b, "ANYONE", "0").andExpect(status().isOk());
        return UUID.fromString(json(start(a, bp, UUID.randomUUID()).andExpect(status().isCreated())).path("conversationId").asText());
    }
    void visible(UUID actor, boolean enabled, boolean invisible) throws Exception {
        var current = json(mvc.perform(get(ROOT + "/privacy").with(user(actor))).andExpect(status().isOk()));
        var preferences = (com.fasterxml.jackson.databind.node.ObjectNode) current.path("preferences");
        preferences.put("presenceVisible", enabled); preferences.put("invisible", invisible);
        mvc.perform(put(ROOT + "/privacy").with(user(actor)).header("If-Match", "\"" + current.path("version").asText() + "\"")
                .contentType(MediaType.APPLICATION_JSON).content(preferences.toString())).andExpect(status().isOk());
    }
    ResultActions presence(UUID actor, UUID conversation) throws Exception {
        return call(token(actor, conversation, "community:presence:project", "community-service"), Map.of("conversationId", conversation));
    }
    ResultActions call(String token, Map<String, Object> body) throws Exception {
        return mvc.perform(post(PATH).header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(body)));
    }
    String token(UUID subject, UUID conversation, String scope, String audience) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer("realtime-service").audience(audience).subject(subject.toString())
                .jwtID(UUID.randomUUID().toString()).issueTime(Date.from(clock.instant())).expirationTime(Date.from(clock.instant().plusSeconds(30)))
                .claim("act", Map.of("sub", "realtime-service")).claim("scope", scope).claim("conversationId", conversation.toString()).build();
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("realtime-test").build(), claims);
        signed.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate())); return signed.serialize();
    }
}
