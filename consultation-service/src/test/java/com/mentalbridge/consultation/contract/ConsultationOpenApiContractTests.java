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
			"GET /internal/v1/appointments/{appointmentId}/consultation-brief-context",
			"GET /internal/v1/appointments/{appointmentId}/reusable-session-summaries/{summaryId}",
			"GET /internal/v1/specialist/client-relationships",
			"GET /internal/v1/resource-proposals/{proposalId}",
			"GET /internal/v1/appointments/{conversationId}/chat-eligibility",
			"POST /internal/v1/appointments/{appointmentId}/chat-evidence",
			"GET /internal/v1/appointments/{appointmentId}/notification-eligibility",
			"GET /api/v1/specialist-profile",
			"PUT /api/v1/specialist-profile",
			"POST /api/v1/specialist-profile/submit",
			"POST /api/v1/specialist-profile/resubmit",
			"GET /api/v1/availability-slots",
			"POST /api/v1/availability-slots",
			"DELETE /api/v1/availability-slots/{slotId}",
			"GET /api/v1/bookable-slots",
			"GET /api/v1/specialists",
			"GET /api/v1/specialists/{specialistAccountId}",
			"GET /api/v1/appointments",
			"GET /api/v1/admin/appointments",
			"POST /api/v1/appointments",
			"POST /api/v1/appointments/{appointmentId}/cancel",
			"GET /api/v1/appointments/{appointmentId}/rating",
			"PUT /api/v1/appointments/{appointmentId}/rating",
			"GET /api/v1/appointments/{appointmentId}/session-summaries",
			"PUT /api/v1/session-summaries/{summaryId}/reuse-consent",
			"PUT /api/v1/agreed-next-steps/{nextStepId}",
			"GET /api/v1/specialist/dashboard",
			"GET /api/v1/specialist/appointments",
			"GET /api/v1/specialist/appointments/{appointmentId}/session-summaries",
			"POST /api/v1/specialist/appointments/{appointmentId}/session-summaries",
			"POST /api/v1/specialist/appointments/{appointmentId}/accept",
			"POST /api/v1/specialist/appointments/{appointmentId}/reject",
			"GET /api/v1/admin/operations/summary",
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
				if (path.contains("notification-eligibility")) {
					assertThat(operation.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("serviceToken"));
				}
				else {
					assertThat(operation.getSecurity()).anySatisfy(requirement -> assertThat(requirement).containsKey("bearerAuth"));
				}
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
				"replacesAppointmentId", "replacedByAppointmentId", "decidedAt", "decisionReason", "cancelledAt",
				"cancellationReason", "cancellationActor", "cancellationCreditOutcome", "sessionOutcome",
				"sessionPolicyVersion", "sessionEndedAt", "sessionSettledAt", "completionFactId",
				"creditState", "history", "version");
		var statuses = ((Schema<?>) appointment.getProperties().get("status")).getEnum().stream()
				.map(String::valueOf).toList();
		assertThat(statuses).containsExactly("REQUESTED", "CONFIRMED", "IN_PROGRESS", "SESSION_ENDED",
				"COMPLETED", "REJECTED", "EXPIRED", "CANCELLED");
		assertThat(appointment.getRequired()).contains("replacesAppointmentId", "replacedByAppointmentId",
				"cancelledAt", "cancellationReason", "cancellationActor", "cancellationCreditOutcome", "history");
		assertThat(appointment.getProperties()).doesNotContainKeys("practiceLocationId", "phone", "meetingLink", "url");
	}

	@Test
	void adminAppointmentContractIsReadOnlyBoundedAndContentFree() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var item = api.getComponents().getSchemas().get("AdminAppointmentItem");
		var page = api.getComponents().getSchemas().get("AdminAppointmentPage");

		assertThat(item.getProperties()).containsOnlyKeys("appointmentId", "availabilitySlotId", "userAccountId",
				"specialistAccountId", "status", "modality", "scheduledStartAt", "scheduledEndAt", "timezone",
				"requestedAt", "decisionDeadlineAt", "decidedAt", "decisionReasonCode", "cancelledAt",
				"cancellationReasonCode", "cancellationCreditOutcome", "sessionEndedAt", "sessionSettledAt",
				"sessionOutcome", "sessionOutcomeReasonCode", "settlementState", "updatedAt", "version");
		assertThat(item.getProperties()).doesNotContainKeys("consultationBrief", "sessionSummary", "chatMessages",
				"journal", "assessmentAnswers", "privateNotes", "aiPayload", "heldCreditId");
		assertThat(page.getProperties()).containsKeys("source", "dataState", "queryFrom", "queryTo", "nextCursor");
	}

	@Test
	void specialistDashboardIsBoundedOperationalAndContainsNoHealthData() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var dashboard = api.getComponents().getSchemas().get("SpecialistDashboard");
		var appointment = api.getComponents().getSchemas().get("SpecialistDashboardAppointmentItem");
		var appointments = api.getComponents().getSchemas().get("SpecialistDashboardAppointmentCollection");

		assertThat(dashboard.getProperties()).containsOnlyKeys("source", "generatedAt", "operationalStatus",
				"profile", "ratingAggregate", "todayConfirmedSessions", "pendingAppointmentRequests", "nextAppointment",
				"availability", "actionRequired");
		assertThat(appointment.getProperties()).containsOnlyKeys("source", "asOf", "appointmentId", "status",
				"modality", "scheduledStartAt", "scheduledEndAt", "timezone", "decisionDeadlineAt");
		assertThat(appointment.getProperties()).doesNotContainKeys("userAccountId", "clientName", "checkIns",
				"riskScore", "recovery", "adherence", "journal", "assessmentAnswers", "notes");
		assertThat(((Schema<?>) appointments.getProperties().get("items")).getMaxItems()).isEqualTo(5);
	}

	@Test
	void specialistClientRelationshipProjectionContainsOnlyAppointmentAuthorityFacts() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var item = api.getComponents().getSchemas().get("SpecialistClientRelationship");

		assertThat(item.getProperties()).containsOnlyKeys("appointmentId", "userAccountId", "status", "modality",
				"scheduledStartAt", "scheduledEndAt", "appointmentVersion")
				.doesNotContainKeys("displayName", "journal", "assessment", "chat", "supportPlan", "privateNotes");
	}

	@Test
	void chatEvidenceContractIsContentFreeAndServerBounded() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var request = api.getComponents().getSchemas().get("ChatEvidenceRequest");
		var eligibility = api.getComponents().getSchemas().get("AppointmentChatEligibility");

		assertThat(request.getProperties()).containsOnlyKeys("evidenceId", "type", "occurredAt",
				"intervalStartedAt", "messageId");
		assertThat(request.getProperties()).doesNotContainKeys("content", "messageContent", "diagnosis", "notes");
		assertThat(eligibility.getProperties()).containsKeys("checkInAllowed", "participantCheckedIn",
				"sessionOutcome", "creditState");
	}

	@Test
	void consultationBriefContextExposesOnlyAppointmentAuthorityAndTiming() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var context = api.getComponents().getSchemas().get("ConsultationBriefAppointmentContext");

		assertThat(context.getProperties()).containsOnlyKeys("appointmentId", "userAccountId", "specialistAccountId",
				"status", "scheduledStartAt", "scheduledEndAt", "version");
		assertThat(context.getProperties()).doesNotContainKeys("heldCreditId", "history", "decisionReason",
				"cancellationReason", "specialistDisplayName");
	}

	@Test
	void sessionSummaryIsBoundedUserVisibleAndKeepsNextStepsSeparate() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var request = api.getComponents().getSchemas().get("PublishSessionSummaryRequest");
		var response = api.getComponents().getSchemas().get("SessionSummary");
		var step = api.getComponents().getSchemas().get("AgreedNextStep");

		assertThat(request.getProperties()).containsOnlyKeys("topicsDiscussed", "progressSummary",
				"specialistNoteForUser", "followUpSuggested", "agreedNextSteps");
		assertThat(response.getProperties()).containsKeys("version", "schemaVersion", "amendsSummaryId",
				"reuseConsent", "agreedNextSteps", "publishedAt");
		assertThat(request.getProperties()).doesNotContainKeys("diagnosis", "riskLevel", "privateNotes",
				"journal", "assessmentAnswers", "chatTranscript");
		assertThat(step.getProperties()).containsKeys("state", "hidden", "resourceId", "resourceVersion",
				"resourceProposalReasonCode");
		var proposal = api.getComponents().getSchemas().get("ResourceProposal");
		assertThat(proposal.getProperties()).containsKeys("proposalId", "summaryId", "summaryVersion",
				"completionFactId", "resourceId", "resourceVersion", "reasonCode", "proposedAt");
	}

	@Test
	void discoveryContractIsApprovedOnlineMinimalAndBookingNeutral() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var item = api.getComponents().getSchemas().get("SpecialistDiscoveryItem");
		var slot = api.getComponents().getSchemas().get("DiscoverySlot");
		var explanation = api.getComponents().getSchemas().get("DiscoveryExplanation");
		var page = api.getComponents().getSchemas().get("SpecialistDiscoveryPage");

		assertThat(item.getProperties()).containsOnlyKeys("specialistAccountId", "displayName", "bio",
				"supportAreas", "languages", "yearsOfExperience", "timezone", "ratingAggregate", "explanation",
				"selectableSlots");
		assertThat(item.getProperties()).doesNotContainKeys("approvalStatus", "practiceLocation", "address", "phone",
				"price", "credentials", "license", "certificates", "specialties", "journal", "assessmentAnswers",
				"chatContent", "meetingLink");
		assertThat(((Schema<?>) item.getProperties().get("selectableSlots")).getMinItems()).isEqualTo(1);
		assertThat(slot.getProperties()).containsOnlyKeys("id", "specialistAccountId", "startAt", "endAt",
				"timezone", "modality", "version");
		assertThat(explanation.getProperties()).containsKeys("compatibility", "languageMatched", "hasSelectableSlot",
				"timezoneMatch", "ratingTieBreakerApplied", "codes");
		assertThat(page.getProperties()).containsKeys("rankingPolicyVersion", "contextState", "packageCode",
				"bookingHandoff", "videoEnabled", "nextCursor");
	}

	@Test
	void appointmentRatingContractIsBoundedAndVersioned() {
		var contract = Path.of("..", "contracts", "openapi", "consultation-service-v1.yaml").toString();
		var api = new OpenAPIV3Parser().read(contract);
		var rating = api.getComponents().getSchemas().get("AppointmentRating");
		var input = api.getComponents().getSchemas().get("SaveAppointmentRating");

		assertThat(api.getPaths()).containsKey("/api/v1/appointments/{appointmentId}/rating");
		assertThat(input.getProperties()).containsOnlyKeys("rating");
		assertThat(rating.getProperties()).containsOnlyKeys("appointmentId", "specialistAccountId", "rating",
				"createdAt", "updatedAt", "version", "specialistAggregate");
		assertThat(rating.getProperties()).doesNotContainKeys("comment", "anonymous", "diagnosis", "journal");
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
