package com.mentalbridge.care.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;

/**
 * Contract tests for the Care-owned safety directory lookup boundary.
 *
 * These tests verify that the care-service-v1.yaml contract:
 *   - exposes the lookup endpoint as public (no auth)
 *   - mandates synchronous fallback fields on every response
 *   - uses truthful area wording with no nearest/coordinates claim
 *   - includes UNAVAILABLE as a Care-only state absent from Content
 *   - treats the trigger as presentation provenance, not a risk label
 *
 * Counterpart: content-notification-service.yaml is verified by
 * safety-directory.contract.test.ts in content-notification-service.
 */
class SafetyDirectoryContractTests {

	private static final ParseOptions RESOLVE = new ParseOptions();

	static {
		RESOLVE.setResolve(true);
		RESOLVE.setResolveFully(true);
	}

	@Test
	void careContractIsValidAndSafetyDirectoryEndpointIsPublic() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var result = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, RESOLVE);

		assertThat(result.getMessages()).isEmpty();

		var post = result.getOpenAPI().getPaths()
				.get("/api/v1/safety-directory-lookups").getPost();
		assertThat(post).as("POST /api/v1/safety-directory-lookups must exist").isNotNull();
		assertThat(post.getSecurity())
				.as("safety directory lookup must be public — no auth required")
				.isNullOrEmpty();
		assertThat(post.getResponses().keySet())
				.as("lookup must always return 200 and document 400 for validation")
				.contains("200", "400");
	}

	@Test
	void lookupResponseAlwaysIncludesSynchronousFallbackFieldsRegardlessOfState() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();

		var response = openApi.getComponents().getSchemas().get("CareSafetyDirectoryLookupResponse");
		assertThat(response).as("CareSafetyDirectoryLookupResponse schema must exist").isNotNull();
		assertThat(response.getRequired())
				.as("all fallback fields must be required so they are present in every state")
				.containsExactlyInAnyOrder("trigger", "state", "areaWording", "safetyGuidance", "limitation", "entries");
		// additionalProperties: false is represented as Boolean.FALSE by OpenAPIV3Parser
		// but may also be represented as a Schema with booleanSchemaValue=false
		var additionalProperties = response.getAdditionalProperties();
		boolean additionalPropertiesAllowed = Boolean.TRUE.equals(additionalProperties)
				|| (additionalProperties instanceof Schema<?> s && Boolean.TRUE.equals(s.getBooleanSchemaValue()));
		assertThat(additionalPropertiesAllowed)
				.as("response schema must be closed — no undocumented fields")
				.isFalse();
	}

	@Test
	void lookupResponseFallbackCopyIsConstantAndContainsNoNearestOrCoordinateClaim() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();

		var response = openApi.getComponents().getSchemas().get("CareSafetyDirectoryLookupResponse");
		var properties = response.getProperties();

		var areaWording = (Schema<?>) properties.get("areaWording");
		assertThat(areaWording.getConst())
				.as("areaWording must be the constant approved Vietnamese text")
				.isEqualTo("Cơ sở trong khu vực đã chọn");
		assertThat(areaWording.getConst().toString())
				.as("areaWording must not claim nearest-distance proximity")
				.doesNotContainIgnoringCase("gần nhất")
				.doesNotContainIgnoringCase("nearest");

		var safetyGuidance = (Schema<?>) properties.get("safetyGuidance");
		assertThat(safetyGuidance.getConst())
				.as("safetyGuidance must be the approved local fallback copy")
				.isNotNull();
		assertThat(safetyGuidance.getConst().toString())
				.as("safetyGuidance must reference emergency services, not MentalBridge as a responder")
				.contains("dịch vụ khẩn cấp");

		var limitation = (Schema<?>) properties.get("limitation");
		assertThat(limitation.getConst())
				.as("limitation must be the approved disclaimer copy")
				.isNotNull();
		assertThat(limitation.getConst().toString())
				.as("limitation must disclaim 24/7 monitoring and automatic contact")
				.contains("không tự động liên hệ bên thứ ba");
	}

	@SuppressWarnings("unchecked")
	@Test
	void careStateEnumIncludesUnavailableWhichIsAbsentFromContentOwnerContract() {
		var careContract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var contentContract = Path.of("..", "contracts", "openapi", "content-notification-service.yaml").toAbsolutePath();
		var careApi = new OpenAPIV3Parser().readLocation(careContract.toUri().toString(), null, null).getOpenAPI();
		var contentApi = new OpenAPIV3Parser().readLocation(contentContract.toUri().toString(), null, null).getOpenAPI();

		var careStates = (List<String>) careApi.getComponents().getSchemas()
				.get("CareSafetyDirectoryState").getEnum();
		var contentStates = (List<String>) contentApi.getComponents().getSchemas()
				.get("SafetyDirectoryLookupState").getEnum();

		assertThat(careStates)
				.as("Care state must include UNAVAILABLE for dependency-failure fallback")
				.contains("UNAVAILABLE", "RESULTS", "EMPTY", "INVALID_AREA");
		assertThat(contentStates)
				.as("Content is the owner and does not produce UNAVAILABLE — that is a Care-side mapping")
				.doesNotContain("UNAVAILABLE")
				.containsExactlyInAnyOrder("RESULTS", "EMPTY", "INVALID_AREA");
	}

	@SuppressWarnings("unchecked")
	@Test
	void triggerEnumIsLabelledPresentationProvenanceAndNotARiskClassification() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();

		var trigger = openApi.getComponents().getSchemas().get("SafetyDirectoryTrigger");
		assertThat(trigger).as("SafetyDirectoryTrigger schema must exist").isNotNull();
		assertThat((List<String>) trigger.getEnum())
				.as("only approved trigger values are allowed")
				.containsExactlyInAnyOrder("POSITIVE_ITEM_9", "HELP_NOW");
		assertThat(trigger.getDescription())
				.as("trigger description must clarify it is presentation provenance, not a clinical label")
				.containsIgnoringCase("provenance")
				.doesNotContainIgnoringCase("diagnosis");
	}

	@Test
	void entrySchemaRequiresProvenanceFieldsAndForbidsCoordinatesOrDistanceClaims() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();

		var entry = openApi.getComponents().getSchemas().get("CareSafetyDirectoryEntry");
		assertThat(entry).as("CareSafetyDirectoryEntry schema must exist").isNotNull();
		assertThat(entry.getRequired())
				.as("entry must mandate identity, contact, coverage, and provenance fields")
				.contains("directoryEntryId", "name", "type", "phone", "address",
						"coverage", "sourceName", "sourceReference", "reviewedAt", "verifiedAt");
		assertThat(entry.getProperties().keySet())
				.as("entry must not expose coordinates, distance, or nearest-proximity fields")
				.doesNotContain("latitude", "longitude", "coordinates", "distance",
						"distanceMeters", "nearest", "location");
	}

	@Test
	void lookupRequestRequiresTriggerAndExactlyOneLocationInput() {
		var contract = Path.of("..", "contracts", "openapi", "care-service-v1.yaml").toAbsolutePath();
		var openApi = new OpenAPIV3Parser().readLocation(contract.toUri().toString(), null, null).getOpenAPI();

		var request = openApi.getComponents().getSchemas().get("CareSafetyDirectoryLookupRequest");
		assertThat(request).as("CareSafetyDirectoryLookupRequest schema must exist").isNotNull();
		assertThat(request.getRequired())
				.as("trigger is always required")
				.contains("trigger");
		assertThat(request.getAdditionalProperties())
				.as("request schema must be closed")
				.satisfiesAnyOf(
						ap -> assertThat(ap).isEqualTo(Boolean.FALSE),
						ap -> assertThat(ap).isInstanceOfSatisfying(Schema.class,
								s -> assertThat(s.getBooleanSchemaValue()).isEqualTo(Boolean.FALSE)));
		assertThat(request.getProperties().keySet())
				.as("request allows only approved location fields — no raw coordinates or geolocation")
				.containsExactlyInAnyOrder("trigger", "provinceCode", "districtCode", "manualLocation")
				.doesNotContain("latitude", "longitude", "coordinates", "geoLocation", "ipAddress");
	}
}
