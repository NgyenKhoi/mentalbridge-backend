package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ChatAccountAuthorizerTests {
    private final ChatIdentityHttpClient http = mock(ChatIdentityHttpClient.class);
    private final ChatProperties properties = new ChatProperties(true, "", "synthetic-service-token-at-least-32-characters",
            Duration.ofMillis(500), Duration.ofMillis(1500), "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=", "v1", "{}");
    @Test
    void rejectsMalformedExtraOrDuplicateSubjectsAndNeverCachesAPositiveDecision() {
        var actor = UUID.randomUUID(); var correlation = UUID.randomUUID();
        var client = new ChatAccountAuthorizer(http, properties);
        when(http.authorize(anyString(), anyString(), any())).thenReturn(new ChatIdentityHttpClient.Result(List.of(actor)),
                new ChatIdentityHttpClient.Result(List.of()), new ChatIdentityHttpClient.Result(List.of(UUID.randomUUID())),
                new ChatIdentityHttpClient.Result(List.of(actor, actor)), null);
        client.require(List.of(actor), correlation);
        assertThatThrownBy(() -> client.require(List.of(actor), correlation)).isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.status).isEqualTo(404));
        for (int i = 0; i < 3; i++) assertThatThrownBy(() -> client.require(List.of(actor), correlation))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.status).isEqualTo(503));
    }
    @Test
    void refusesRemoteAuthorizationInsideALocalTransaction() {
        var client = new ChatAccountAuthorizer(http, properties);
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThatThrownBy(() -> client.active(List.of(UUID.randomUUID()), UUID.randomUUID())).isInstanceOf(IllegalStateException.class); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(http);
    }
    @Test
    void boundsTheDeadlineAndRequiresSecretsOnlyWhenEnabled() {
        assertThatThrownBy(() -> new ChatProperties(true, "", "short", Duration.ofMillis(500), Duration.ofMillis(1500), "", "v1", "{}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("short");
        assertThatThrownBy(() -> new ChatProperties(false, "", "", Duration.ofSeconds(1), Duration.ofSeconds(2), "", "v1", "{}"))
                .isInstanceOf(IllegalArgumentException.class);
        var disabled = new ChatProperties(false, "", "", Duration.ofMillis(500), Duration.ofMillis(1500), "", "v1", "{}");
        assertThatThrownBy(() -> new ChatAccountAuthorizer(http, disabled).require(List.of(UUID.randomUUID()), UUID.randomUUID()))
                .isInstanceOfSatisfying(ChatException.class, e -> assertThat(e.status).isEqualTo(503));
        assertThat(properties.toString()).doesNotContain(properties.encryptionKey()).doesNotContain(properties.identityServiceToken());
    }
}
