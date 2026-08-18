package com.mentalbridge.identity.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class IdentityOpenApiContractTests {

	@Test
	void identityContractIsValidAndFullyResolved() {
		var contract = Path.of("..", "contracts", "openapi", "identity-service-v1.yaml").toAbsolutePath();
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var result = new OpenAPIV3Parser().readLocation(contract.toString(), null, options);

		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI()).isNotNull();
		assertThat(result.getOpenAPI().getPaths()).isNotEmpty();
	}

}
