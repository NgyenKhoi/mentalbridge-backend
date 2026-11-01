package com.mentalbridge.community.chat;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ChatAccountAuthorizer {
    private final ChatIdentityHttpClient client;
    private final ChatProperties properties;
    private final CircuitBreaker breaker = CircuitBreaker.of("communityChatIdentity",
            CircuitBreakerConfig.custom().slidingWindowSize(10).minimumNumberOfCalls(5)
                    .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(5))
                    .permittedNumberOfCallsInHalfOpenState(1).build());

    ChatAccountAuthorizer(ChatIdentityHttpClient client, ChatProperties properties) {
        this.client = client; this.properties = properties;
    }

    public Set<UUID> active(Collection<UUID> subjects, UUID correlationId) {
        properties.requireEnabled();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Current account authorization must precede the local transaction");
        }
        var requested = new HashSet<>(subjects);
        if (requested.isEmpty() || requested.size() > 100 || requested.contains(null)) throw ChatException.invalid();
        try {
            return breaker.executeSupplier(() -> {
                var result = client.authorize(properties.identityServiceToken(), correlationId.toString(),
                        new ChatIdentityHttpClient.Query(requested.stream().toList()));
                if (result == null || result.activeUserAccountIds() == null
                        || result.activeUserAccountIds().stream().anyMatch(id -> id == null || !requested.contains(id))
                        || new HashSet<>(result.activeUserAccountIds()).size() != result.activeUserAccountIds().size()) {
                    throw ChatException.unavailable();
                }
                return Set.copyOf(result.activeUserAccountIds());
            });
        } catch (RuntimeException exception) { throw ChatException.unavailable(); }
    }

    public void require(Collection<UUID> subjects, UUID correlationId) {
        if (!active(subjects, correlationId).containsAll(subjects)) throw ChatException.target();
    }
}
