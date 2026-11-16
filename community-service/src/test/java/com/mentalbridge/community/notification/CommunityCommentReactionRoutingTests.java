package com.mentalbridge.community.notification;

import static org.mockito.Mockito.*;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;

class CommunityCommentReactionRoutingTests {
    @Test void routesCommentReactionsSeparatelyWithoutWideningTheExistingInteractionTopic() throws Exception {
        @SuppressWarnings("unchecked") var kafka = (KafkaTemplate<String,String>) mock(KafkaTemplate.class);
        var mapper = new ObjectMapper().findAndRegisterModules();
        var target = UUID.randomUUID();
        when(kafka.send(anyString(), anyString(), anyString())).thenReturn(CompletableFuture.completedFuture(null));
        var properties = new CommunityInteractionRelayProperties(true, "mentalbridge.community.interaction.v1", 10,
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(5));
        var sink = new KafkaCommunityInteractionEventSink(properties, kafka, mapper);
        for (var kind : new CommunityInteractionFact.InteractionKind[] {
                CommunityInteractionFact.InteractionKind.SUPPORT, CommunityInteractionFact.InteractionKind.RELATE,
                CommunityInteractionFact.InteractionKind.THANK_YOU, CommunityInteractionFact.InteractionKind.LIKE,
                CommunityInteractionFact.InteractionKind.EMPATHY, CommunityInteractionFact.InteractionKind.CHEER,
                CommunityInteractionFact.InteractionKind.GROWTH }) {
            var fact = CommunityInteractionFact.create(UUID.randomUUID(), UUID.randomUUID(), CommunityInteractionFact.TargetType.COMMENT,
                    target, kind, UUID.randomUUID(), Instant.now());
            var wire = mapper.valueToTree(fact);
            sink.publish(target, wire);
            verify(kafka).send("mentalbridge.community.comment-reaction.v1", target.toString(), mapper.writeValueAsString(wire));
        }
        verifyNoMoreInteractions(kafka);
    }
}
