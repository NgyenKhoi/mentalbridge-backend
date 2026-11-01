package com.mentalbridge.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.identity.IdentityTestProperties;
import com.mentalbridge.identity.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "mentalbridge.identity.community-chat.service-token=synthetic-community-chat-token-at-least-32")
@AutoConfigureMockMvc
@Transactional
class CommunityChatAccountAuthorizationIntegrationTests extends IdentityTestProperties {
    private static final String PATH = "/internal/v1/community-chat/account-authorizations";
    private static final String TOKEN = "synthetic-community-chat-token-at-least-32";
    @Autowired MockMvc mvc;
    @Autowired AccountRepository accounts;
    @Autowired ObjectMapper mapper;

    @Test
    void queriesCurrentOwnerStateWithoutReturningAccountMetadata() throws Exception {
        var active = account(RoleCode.USER, true);
        var disabled = account(RoleCode.USER, true);
        disabled.disable(Instant.now());
        accounts.saveAndFlush(disabled);
        var pending = account(RoleCode.USER, false);
        var specialist = account(RoleCode.SPECIALIST, true);
        var admin = account(RoleCode.ADMIN, true);
        var ids = List.of(active.id(), disabled.id(), pending.id(), specialist.id(), admin.id(), UUID.randomUUID());
        var body = mapper.writeValueAsString(Map.of("accountIds", ids));
        var result = mvc.perform(post(PATH).header("X-MentalBridge-Service-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.activeUserAccountIds.length()").value(1))
                .andExpect(jsonPath("$.activeUserAccountIds[0]").value(active.id().toString()))
                .andReturn();
        var response = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(response.size()).isOne();
        active.disable(Instant.now());
        accounts.saveAndFlush(active);
        mvc.perform(post(PATH).header("X-MentalBridge-Service-Token", TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeUserAccountIds.length()").value(0));
    }

    @Test
    void requiresTheDedicatedServiceCredential() throws Exception {
        var body = mapper.writeValueAsString(Map.of("accountIds", List.of(UUID.randomUUID())));
        for (var candidate : List.of("", "wrong", "synthetic-notification-token-at-least-32")) {
            mvc.perform(post(PATH).header("X-MentalBridge-Service-Token", candidate)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        }
        mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsMalformedOrUnboundedRequests() throws Exception {
        for (var body : List.of("{}", "{\"accountIds\":[]}", "{\"accountIds\":[null]}",
                "{\"accountIds\":[\"bad\"]}",
                "{\"accountIds\":[\"11111111-1111-4111-8111-111111111111\"],\"email\":\"private\"}")) {
            mvc.perform(post(PATH).header("X-MentalBridge-Service-Token", TOKEN)
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        var tooMany = java.util.stream.IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();
        mvc.perform(post(PATH).header("X-MentalBridge-Service-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("accountIds", tooMany))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void deduplicatesRequestedIdsAndRedactsConfiguration() throws Exception {
        var active = account(RoleCode.USER, true);
        mvc.perform(post(PATH).header("X-MentalBridge-Service-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(Map.of("accountIds", List.of(active.id(), active.id())))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeUserAccountIds.length()").value(1));
        assertThat(new CommunityChatAuthorizationProperties(TOKEN).toString()).doesNotContain(TOKEN);
        assertThat(new CommunityChatAccountAuthorizationController.AuthorizationRequest(List.of(active.id())).toString()).doesNotContain(active.id().toString());
        assertThat(new CommunityChatAccountAuthorizationController.AuthorizationResult(List.of(active.id())).toString()).doesNotContain(active.id().toString());
    }

    private AccountEntity account(RoleCode role, boolean verified) {
        var now = Instant.now();
        var account = AccountEntity.pending(UUID.randomUUID() + "@example.test", "synthetic-hash", role, now);
        if (verified) account.activate(now);
        return accounts.saveAndFlush(account);
    }
}
