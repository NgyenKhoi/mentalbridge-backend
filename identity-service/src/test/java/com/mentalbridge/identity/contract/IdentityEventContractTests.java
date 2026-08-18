package com.mentalbridge.identity.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

class IdentityEventContractTests {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void accountLifecycleSchemasAreValidJsonSchemaDocuments() throws Exception {
		var directory = Path.of("..", "contracts", "events", "identity").toAbsolutePath();
		for (var name : new String[] { "account-registered-v1.schema.json",
				"account-email-verified-v1.schema.json" }) {
			var schema = objectMapper.readTree(Files.readString(directory.resolve(name)));
			assertThat(schema.get("$schema").asText()).isEqualTo("https://json-schema.org/draft/2020-12/schema");
			assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
			assertThat(schema.at("/properties/payload").get("additionalProperties").asBoolean()).isFalse();
		}
	}

}
