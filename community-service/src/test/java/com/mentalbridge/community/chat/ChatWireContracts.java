package com.mentalbridge.community.chat;

import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;

final class ChatWireContracts {
    private ChatWireContracts() {}
    static boolean validFact(String wire) throws Exception {
        var schema = Files.readString(Path.of("..", "contracts", "events", "community", "community-chat-fact-v1.schema.json"));
        var validator = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(schema, InputFormat.JSON);
        return validator.validate(new ObjectMapper().readTree(wire), context -> context.executionConfig(
                configuration -> configuration.formatAssertionsEnabled(true))).isEmpty();
    }
    static boolean validResponse(String path, String method, int status, String wire) throws Exception {
        var mapper = new ObjectMapper();
        var api = mapper.readTree(Files.readString(Path.of("..", "contracts", "openapi", "community-chat-v1.json")));
        var paths = api.path("paths").fields();
        while (paths.hasNext()) {
            var entry = paths.next();
            if (!path.matches(entry.getKey().replaceAll("\\{[^}]+}", "[^/]+"))) continue;
            var response = entry.getValue().path(method.toLowerCase(java.util.Locale.ROOT)).path("responses").path(Integer.toString(status));
            if (response.has("$ref")) response = api.at(response.path("$ref").asText().substring(1));
            var content = response.path("content").elements();
            if (!content.hasNext()) return false;
            var schema = content.next().path("schema");
            var document = mapper.createObjectNode();
            document.set("$defs", api.path("components").path("schemas"));
            document.setAll((com.fasterxml.jackson.databind.node.ObjectNode) schema);
            var validator = SchemaRegistry.withDialect(Dialects.getDraft202012()).getSchema(
                    document.toString().replace("#/components/schemas/", "#/$defs/"), InputFormat.JSON);
            return validator.validate(mapper.readTree(wire), context -> context.executionConfig(configuration -> configuration.formatAssertionsEnabled(true))).isEmpty();
        }
        return false;
    }
}
