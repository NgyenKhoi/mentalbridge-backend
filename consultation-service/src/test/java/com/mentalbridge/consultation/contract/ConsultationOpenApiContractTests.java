package com.mentalbridge.consultation.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.oas.models.media.Schema;

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
			"GET /api/v1/bookable-slots",
			"GET /api/v1/appointments",
			"POST /api/v1/appointments",
			"GET /api/v1/specialist/appointments",
			"POST /api/v1/specialist/appointments/{appointmentId}/accept",
			"POST /api/v1/specialist/appointments/{appointmentId}/reject",
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
		var capacity = api.getComponents().getSchemas().get("AppointmentReservationCapacity");
		var policyVersion = api.getComponents().getSchemas().get("ConsultationCreditPolicyVersion");
		var ledgerEvent = api.getComponents().getSchemas().get("ServiceCreditLedgerEvent");

		assertThat(account.getProperties()).containsKeys("packageCode", "source", "periodStart", "periodEnd",
				"policyVersion", "balance", "reservationCapacity", "history");
		assertThat(balance.getProperties()).containsOnlyKeys("available", "held", "consumed", "forfeited", "total",
				"releasedTransitions");
		assertThat(((Schema<?>) balance.getProperties().get("total")).getMaximum()).isEqualByComparingTo("10");
		assertThat(capacity.getProperties()).containsOnlyKeys("active", "maximum", "remaining");
		assertThat(policyVersion.getEnum()).containsExactly("consultation-credit-v1", "consultation-credit-v2");
		assertThat(ledgerEvent.getProperties()).containsKey("policyVersion");
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
	void appointmentRequestUsesOnlyAnExactOnlineSlotAndModality() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var request = api.getComponents().getSchemas().get("RequestAppointment");
		var appointment = api.getComponents().getSchemas().get("Appointment");

		assertThat(request.getProperties()).containsOnlyKeys("slotId", "modality", "replacesAppointmentId");
		assertThat(request.getRequired()).containsExactlyInAnyOrder("slotId", "modality");
		assertThat(appointment.getProperties()).containsKeys("status", "decisionDeadlineAt", "heldCreditId",
				"replacesAppointmentId", "decidedAt", "decisionReason", "creditState", "version");
		var statuses = ((Schema<?>) appointment.getProperties().get("status")).getEnum().stream()
				.map(String::valueOf).toList();
		assertThat(statuses).containsExactly("REQUESTED", "CONFIRMED", "IN_PROGRESS", "REJECTED", "EXPIRED",
				"CANCELLED");
		assertThat(appointment.getRequired()).contains("replacesAppointmentId");
		assertThat(appointment.getProperties()).doesNotContainKeys("practiceLocationId", "phone", "meetingLink", "url");
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
