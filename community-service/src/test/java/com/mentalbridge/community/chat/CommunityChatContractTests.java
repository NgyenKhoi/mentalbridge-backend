package com.mentalbridge.community.chat;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import org.junit.jupiter.api.Test;

class CommunityChatContractTests {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    @Test
    void activeProviderSubsetMatchesTheAcceptedContractWithoutClaimingDownstreamRoutes() throws Exception {
        var active = Path.of("..", "contracts", "openapi", "community-chat-v1.json");
        var baseline = Path.of("..", "contracts", "proposals", "community-chat-v1.openapi.json");
        var options = new ParseOptions(); options.setResolve(true); options.setResolveFully(true);
        var parsed = new OpenAPIV3Parser().readLocation(active.toAbsolutePath().toUri().toString(), null, options);
        assertThat(parsed.getMessages()).isEmpty();
        var source = mapper.readTree(Files.readString(baseline));
        var delivered = mapper.readTree(Files.readString(active));
        assertThat(delivered.path("components")).isEqualTo(source.path("components"));
        assertThat(parsed.getOpenAPI().getPaths().keySet()).containsExactlyInAnyOrder(
                "/api/v1/community/chat/privacy", "/api/v1/community/chat/direct-conversations",
                "/api/v1/community/chat/requests", "/api/v1/community/chat/requests/{requestId}/decision",
                "/api/v1/community/chat/conversations", "/api/v1/community/chat/conversations/{conversationId}");
        parsed.getOpenAPI().getPaths().forEach((path, item) -> {
            assertThat(item.getExtensions().get("x-mentalbridge-status")).isEqualTo("implemented");
            item.readOperationsMap().forEach((method, operation) -> {
                assertThat(operation.getSecurity()).anySatisfy(auth -> assertThat(auth).containsKey("BearerAuth"));
                var expected = source.path("paths").path(path).path(method.name().toLowerCase()).deepCopy();
                var parameters = mapper.createArrayNode();
                for (var parameter : expected.path("parameters")) {
                    parameters.add(parameter.has("$ref") ? source.path("components").path("parameters")
                            .path(parameter.path("$ref").asText().substring("#/components/parameters/".length())) : parameter);
                }
                ((com.fasterxml.jackson.databind.node.ObjectNode) expected).set("parameters", parameters);
                assertThat(delivered.path("paths").path(path).path(method.name().toLowerCase())).isEqualTo(expected);
            });
        });
    }
    @Test
    void serializedDirectResultsContainOnlyTheExactActiveOrPendingVariant() {
        var id = java.util.UUID.randomUUID();
        var active = mapper.valueToTree(ChatModels.DirectResult.active(id));
        var pending = mapper.valueToTree(ChatModels.DirectResult.pending(id));
        assertThat(active.size()).isEqualTo(2);
        assertThat(active.has("conversationId")).isTrue();
        assertThat(active.has("requestId")).isFalse();
        assertThat(pending.size()).isEqualTo(2);
        assertThat(pending.has("requestId")).isTrue();
        assertThat(pending.has("conversationId")).isFalse();
    }
}
