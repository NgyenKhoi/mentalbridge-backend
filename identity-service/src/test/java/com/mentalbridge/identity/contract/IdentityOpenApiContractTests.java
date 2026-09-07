package com.mentalbridge.identity.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class IdentityOpenApiContractTests {

	private static final Set<String> IMPLEMENTED_OPERATIONS = Set.of(
			"POST /api/v1/auth/registrations",
			"POST /api/v1/auth/email-verifications",
			"POST /api/v1/auth/login",
			"POST /api/v1/auth/refresh",
			"POST /api/v1/auth/logout",
			"POST /api/v1/auth/logout-all",
			"GET /api/v1/account");

	private static final Set<String> PLANNED_OPERATIONS = Set.of(
			"POST /api/v1/auth/email-verification-requests",
			"POST /api/v1/auth/password-recovery-requests",
			"POST /api/v1/auth/password-resets",
			"PUT /api/v1/account/password",
			"GET /api/v1/admin/accounts",
			"GET /api/v1/admin/accounts/{accountId}",
			"PUT /api/v1/admin/accounts/{accountId}/state");
	private static final Set<String> PROTECTED_OPERATIONS = Set.of(
			"POST /api/v1/auth/logout",
			"POST /api/v1/auth/logout-all",
			"GET /api/v1/account");
	private static final Map<String, Set<String>> IMPLEMENTED_RESPONSES = Map.ofEntries(
			Map.entry("POST /api/v1/auth/registrations", Set.of("201", "400", "409", "429")),
			Map.entry("POST /api/v1/auth/email-verifications", Set.of("200", "400", "429")),
			Map.entry("POST /api/v1/auth/login", Set.of("200", "400", "401", "429")),
			Map.entry("POST /api/v1/auth/refresh", Set.of("200", "400", "401", "409", "429")),
			Map.entry("POST /api/v1/auth/logout", Set.of("204", "400", "401")),
			Map.entry("POST /api/v1/auth/logout-all", Set.of("204", "401")),
			Map.entry("GET /api/v1/account", Set.of("200", "401")));

	@Test
	void identityContractIsValidAndFullyResolved() {
		var contract = Path.of("..", "contracts", "openapi", "identity-service-v1.yaml").toAbsolutePath();
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);

		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI()).isNotNull();
		assertThat(result.getOpenAPI().getPaths()).isNotEmpty();

		var implemented = new HashSet<String>();
		var planned = new HashSet<String>();
		result.getOpenAPI().getPaths().forEach((path, pathItem) -> {
			assertThat(pathItem.getExtensions())
					.as("contract status for %s", path)
					.containsKey("x-mentalbridge-status");
			var status = pathItem.getExtensions().get("x-mentalbridge-status");
			assertThat(status).isIn("implemented", "planned");
			pathItem.readOperationsMap().forEach((method, operation) -> {
				var key = method.name() + " " + path;
				if ("implemented".equals(status)) {
					implemented.add(key);
					assertThat(operation.getResponses().keySet())
							.as("documented responses for %s", key)
							.isEqualTo(IMPLEMENTED_RESPONSES.get(key));
					var protectedOperation = operation.getSecurity() != null && !operation.getSecurity().isEmpty();
					assertThat(protectedOperation)
							.as("authentication requirement for %s", key)
							.isEqualTo(PROTECTED_OPERATIONS.contains(key));
				} else {
					planned.add(key);
				}
			});
		});

		assertThat(implemented).isEqualTo(IMPLEMENTED_OPERATIONS);
		assertThat(planned).isEqualTo(PLANNED_OPERATIONS);
		var summaryRoles = (Schema<?>) result.getOpenAPI().getComponents().getSchemas().get("AccountSummary")
				.getProperties().get("roles");
		var detailRoles = (Schema<?>) result.getOpenAPI().getComponents().getSchemas().get("AccountDetail")
				.getProperties().get("roles");
		var registrationActor = (Schema<?>) result.getOpenAPI().getComponents().getSchemas().get("RegistrationRequest")
				.getProperties().get("actorType");
		assertThat(summaryRoles.getMaxItems()).isEqualTo(1);
		assertThat(detailRoles.getMaxItems()).isEqualTo(1);
		assertThat(registrationActor.getEnum()).extracting(Object::toString).containsExactly("USER", "SPECIALIST");
	}

}
