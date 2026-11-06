package com.mentalbridge.care.reassessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Checklist;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Selection;
import com.mentalbridge.care.shared.ApiException;

class ReassessmentSymptomCatalogTests {

	@Test
	void exposesTheApprovedImmutableBundleInCatalogOrder() {
		var catalog = ReassessmentSymptomCatalog.current();
		assertThat(catalog.catalogRevision()).isEqualTo("reassessment-symptoms-v1");
		assertThat(catalog.mappingRevision()).isEqualTo("reassessment-symptom-resource-map-v1");
		assertThat(catalog.maxSelections()).isEqualTo(8);
		assertThat(catalog.items()).extracting(ReassessmentSymptomCatalog.Item::code).containsExactly(
				"SLEEP_DIFFICULTY", "LOW_ENERGY", "PERSISTENT_WORRY", "LOW_MOOD", "CONCENTRATION_DIFFICULTY",
				"APPETITE_CHANGE", "SOCIAL_WITHDRAWAL", "STRESS_OVERLOAD");
		assertThat(catalog.items().getFirst().label()).isEqualTo("Khó ngủ hoặc giấc ngủ bị gián đoạn");
		assertThatThrownBy(() -> catalog.items().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void keepsOmittedAndExplicitEmptyDistinctAndCanonicalizesSets() {
		assertThat(ReassessmentSymptomCatalog.normalize(null)).isNull();
		var empty = accepted(List.of());
		assertThat(empty.mappingRevision()).isEqualTo(ReassessmentSymptomCatalog.MAPPING_REVISION);
		assertThat(ReassessmentSymptomCatalog.reasons(empty)).isEmpty();
		assertThat(accepted(List.of("LOW_MOOD", "LOW_ENERGY")).selectedCodes())
				.containsExactly("LOW_ENERGY", "LOW_MOOD");
	}

	@Test
	void rejectsNullUnknownDuplicateAndOverLimitBeforeCanonicalizingWithoutPrivateEchoes() {
		var invalid = new ArrayList<List<String>>();
		invalid.add(null);
		invalid.add(Arrays.asList((String) null));
		invalid.add(List.of("low_energy"));
		invalid.add(List.of("PRIVATE_UNKNOWN_CODE"));
		invalid.add(List.of("LOW_ENERGY", "LOW_ENERGY"));
		invalid.add(java.util.Collections.nCopies(9, "LOW_ENERGY"));
		for (var codes : invalid) {
			assertThatThrownBy(() -> ReassessmentSymptomCatalog.normalize(new Selection(
					ReassessmentSymptomCatalog.CATALOG_REVISION, codes))).isInstanceOf(ApiException.class)
					.satisfies(exception -> {
						var error = (ApiException) exception;
						assertThat(error.code()).isEqualTo("VALIDATION_FAILED");
						assertThat(error.getMessage()).doesNotContain("PRIVATE_UNKNOWN_CODE");
						assertThat(error.violations().getFirst().field()).isEqualTo("symptomChecklist.selectedCodes");
					});
		}
	}

	@Test
	void separatesRevisionShapeFromCurrentRevisionAdmissionForRecordedReplay() {
		var old = ReassessmentSymptomCatalog.normalize(new Selection("reassessment-symptoms-retired", List.of()));
		assertThatThrownBy(() -> ReassessmentSymptomCatalog.accept(old)).isInstanceOf(ApiException.class)
				.extracting("code").isEqualTo("REASSESSMENT_SYMPTOM_CATALOG_UNSUPPORTED");
		for (var revision : Arrays.asList(null, "", " ", "a".repeat(129))) {
			assertThatThrownBy(() -> ReassessmentSymptomCatalog.normalize(new Selection(revision, List.of())))
					.isInstanceOf(ApiException.class).extracting("code").isEqualTo("VALIDATION_FAILED");
		}
	}

	@Test
	void keepsEvidenceOnlyCodesAndSeparateReasonsForTwoCodesMappingTheSameResource() {
		var checklist = accepted(List.of("SOCIAL_WITHDRAWAL", "LOW_MOOD", "APPETITE_CHANGE", "LOW_ENERGY"));
		var reasons = ReassessmentSymptomCatalog.reasons(checklist);
		assertThat(reasons).extracting(ReassessmentSymptomCatalog.SelectionReason::code)
				.containsExactly("LOW_ENERGY", "LOW_MOOD", "APPETITE_CHANGE", "SOCIAL_WITHDRAWAL");
		assertThat(reasons.get(0).resourceIds()).isEqualTo(reasons.get(1).resourceIds());
		assertThat(reasons.get(2).resourceIds()).isEmpty();
		assertThat(reasons.get(2).reasonCode()).isEqualTo("NO_APPROVED_RESOURCE_MAPPING");
		assertThat(reasons).allSatisfy(reason -> assertThat(reason.mappingRevision())
				.isEqualTo(ReassessmentSymptomCatalog.MAPPING_REVISION));
		assertThat(ReassessmentSymptomCatalog.candidates(checklist)).hasSize(2);
	}

	@Test
	void boundsCandidatesToTheApprovedResourceAndSlotPairs() {
		var codes = ReassessmentSymptomCatalog.current().items().stream().map(ReassessmentSymptomCatalog.Item::code).toList();
		var candidates = ReassessmentSymptomCatalog.candidates(accepted(codes));
		assertThat(candidates).hasSize(6);
		assertThat(candidates.getFirst().resourceId().toString()).endsWith("207");
		assertThat(candidates.getFirst().slotIds()).containsExactly("depression-adjunct", "anxiety-adjunct");
		assertThat(candidates.get(1).slotIds()).containsExactly("depression-maintenance",
				"depression-support-activity", "anxiety-adjunct");
		assertThat(candidates.get(2).resourceId().toString()).endsWith("211");
		assertThat(candidates.get(2).slotIds()).containsExactly("anxiety-maintenance", "anxiety-support-activity");
		assertThat(candidates.get(4).resourceId().toString()).endsWith("203");
		assertThat(candidates.get(5).resourceId().toString()).endsWith("209");
		assertThat(candidates).allSatisfy(candidate -> assertThat(candidate.slotIds())
				.noneMatch(slot -> slot.contains("psychoeducation")));
	}

	@Test
	void acceptsOnlyTheReleasedCanonicalBundleAndCopiesInputCollections() {
		var codes = new ArrayList<>(List.of("LOW_ENERGY"));
		var checklist = new Checklist(ReassessmentSymptomCatalog.CATALOG_REVISION,
				ReassessmentSymptomCatalog.MAPPING_REVISION, codes);
		codes.clear();
		assertThat(checklist.selectedCodes()).containsExactly("LOW_ENERGY");
		assertThat(ReassessmentSymptomCatalog.released(checklist)).isTrue();
		assertThat(ReassessmentSymptomCatalog.released(new Checklist(checklist.catalogRevision(), "other-map",
				checklist.selectedCodes()))).isFalse();
		assertThat(ReassessmentSymptomCatalog.released(new Checklist(checklist.catalogRevision(), checklist.mappingRevision(),
				List.of("LOW_MOOD", "LOW_ENERGY")))).isFalse();
	}

	@Test
	void requestDeserializationDistinguishesOmissionFromExplicitNull() throws Exception {
		var mapper = new ObjectMapper().findAndRegisterModules();
		assertThat(mapper.readValue("{\"currentExperience\":\"UNSURE\"}",
				ReassessmentSelfReportController.ReplaceRequest.class).symptomChecklist()).isNull();
		assertThatThrownBy(() -> mapper.readValue("{\"currentExperience\":\"UNSURE\",\"symptomChecklist\":null}",
				ReassessmentSelfReportController.ReplaceRequest.class)).isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
		var empty = mapper.readValue("""
				{"currentExperience":"UNSURE","symptomChecklist":{"catalogRevision":"reassessment-symptoms-v1","selectedCodes":[]}}
				""", ReassessmentSelfReportController.ReplaceRequest.class);
		assertThat(empty.symptomChecklist().selectedCodes()).isEmpty();
	}

	@Test
	void historicalExplicitSourceJsonDoesNotHydrateAnyChecklist() throws Exception {
		var mapper = new ObjectMapper().findAndRegisterModules();
		var source = mapper.readValue("""
				{"selfReportId":"00000000-0000-4000-8000-000000000001","sourceVersion":"reassessment-self-report-v1",
				 "sourceRevision":0,"currentPeriod":{"startAt":"2026-10-01T00:00:00Z","endAt":"2026-10-15T00:00:00Z"},
				 "currentExperience":"UNSURE","authoredAt":"2026-10-15T01:00:00Z","updatedAt":"2026-10-15T01:00:00Z"}
				""", ReassessmentSummaryView.ExplicitSelfReport.class);
		assertThat(source.symptomChecklist()).isNull();
		assertThat(source.symptomSelectionReasons()).isNull();
		assertThat(mapper.writeValueAsString(source)).doesNotContain("symptomChecklist", "symptomSelectionReasons");
	}

	private Checklist accepted(List<String> codes) {
		return ReassessmentSymptomCatalog.accept(ReassessmentSymptomCatalog.normalize(
				new Selection(ReassessmentSymptomCatalog.CATALOG_REVISION, codes)));
	}
}
