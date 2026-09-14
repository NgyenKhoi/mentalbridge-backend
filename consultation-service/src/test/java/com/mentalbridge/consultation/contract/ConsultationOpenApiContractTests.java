package com.mentalbridge.consultation.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

class ConsultationOpenApiContractTests {

	private static final Set<String> OPERATIONS = Set.of(
			"GET /api/v1/specialist-profile",
			"PUT /api/v1/specialist-profile",
			"POST /api/v1/specialist-profile/submit",
			"GET /api/v1/admin/specialist-profiles",
			"GET /api/v1/admin/specialist-profiles/{specialistAccountId}",
			"POST /api/v1/admin/specialist-profiles/{specialistAccountId}/approve");

	@Test
	void contractIsValidAndMatchesTheImplementedSpecialistApprovalSurface() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toAbsolutePath();
		var options = new ParseOptions();
		options.setResolve(true);
		options.setResolveFully(true);
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, options);

		assertThat(result.getMessages()).isEmpty();
		assertThat(result.getOpenAPI()).isNotNull();
		var operations = new HashSet<String>();
		result.getOpenAPI().getPaths().forEach((path, item) -> {
			assertThat(item.getExtensions()).containsEntry("x-mentalbridge-status", "implemented");
			item.readOperationsMap().forEach((method, operation) -> {
				operations.add(method.name() + " " + path);
				assertThat(operation.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
			});
		});
		assertThat(operations).isEqualTo(OPERATIONS);
	}

	@Test
	void publicProfileContainsOnlyApprovedFieldsAndOperationalReviewFacts() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var request = api.getComponents().getSchemas().get("SpecialistProfileRequest");
		var response = api.getComponents().getSchemas().get("SpecialistProfile");

		assertThat(request.getProperties()).containsOnlyKeys("displayName", "bio", "supportAreas", "languages",
				"yearsOfExperience", "timezone");
		assertThat(response.getProperties()).doesNotContainKeys("credentials", "license", "certificates", "documents",
				"diagnosis", "price", "video");
	}
}
