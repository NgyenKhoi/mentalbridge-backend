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
			"GET /api/v1/service-credits",
			"GET /internal/v1/entitlements/current",
			"GET /api/v1/specialist-profile",
			"PUT /api/v1/specialist-profile",
			"POST /api/v1/specialist-profile/submit",
			"POST /api/v1/specialist-profile/resubmit",
			"GET /api/v1/availability-slots",
			"POST /api/v1/availability-slots",
			"DELETE /api/v1/availability-slots/{slotId}",
			"GET /api/v1/admin/specialist-profiles",
			"GET /api/v1/admin/specialist-profiles/{specialistAccountId}",
			"POST /api/v1/admin/specialist-profiles/{specialistAccountId}/approve",
			"POST /api/v1/admin/specialist-profiles/{specialistAccountId}/reject",
			"POST /api/v1/admin/specialist-profiles/{specialistAccountId}/suspend",
			"POST /api/v1/admin/specialist-profiles/{specialistAccountId}/restore");

	@Test
	void contractIsValidAndMatchesTheImplementedSurface() {
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
	void creditContractPublishesOnlyServerAuthoritativeBalanceAndBoundedHistory() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var account = api.getComponents().getSchemas().get("ServiceCreditAccount");
		var balance = api.getComponents().getSchemas().get("ServiceCreditBalance");

		assertThat(account.getProperties()).containsKeys("packageCode", "source", "periodStart", "periodEnd",
				"balance", "history");
		assertThat(balance.getProperties()).containsOnlyKeys("available", "held", "consumed", "forfeited", "total",
				"releasedTransitions");
	}

	@Test
	void availabilityContractAllowsOnlyExactOnlineSlotsWithoutLocationOrLinks() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var request = api.getComponents().getSchemas().get("PublishAvailabilitySlotRequest");
		var modality = api.getComponents().getSchemas().get("AvailabilityModality");

		assertThat(request.getProperties()).containsOnlyKeys("startAt", "endAt", "timezone", "modality");
		assertThat(request.getProperties()).doesNotContainKeys("practiceLocationId", "phone", "meetingLink", "url");
		assertThat(modality.getEnum()).containsExactly("IN_APP_CHAT", "IN_APP_VIDEO");
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

	@Test
	void lifecycleContractUsesClosedReasonsAndBoundedSuspensionEffects() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var rejection = api.getComponents().getSchemas().get("SpecialistRejectionReasonCode");
		var suspension = api.getComponents().getSchemas().get("SpecialistSuspensionReasonCode");
		var effects = api.getComponents().getSchemas().get("SpecialistSuspensionEffects");

		assertThat(rejection.getEnum()).containsExactly("PROFILE_INFORMATION_INCOMPLETE",
				"PROFILE_CONTENT_NOT_APPROVED", "OUTSIDE_SUPPORTED_SCOPE");
		assertThat(suspension.getEnum()).containsExactly("POLICY_VIOLATION", "QUALITY_REVIEW_REQUIRED",
				"ACCOUNT_REVIEW_REQUIRED");
		assertThat(effects.getProperties()).containsOnlyKeys("withdrawnAvailabilitySlots",
				"cancelledAppointments", "releasedCredits");
	}
}
