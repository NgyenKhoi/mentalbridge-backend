package com.mentalbridge.care.supportplan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.ExplicitSelfReport;
import com.mentalbridge.care.reassessment.ReassessmentSummaryView.Period;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog;
import com.mentalbridge.care.reassessment.ReassessmentSymptomCatalog.Checklist;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewPolicy.Candidate;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.Change;
import com.mentalbridge.care.supportplan.AdaptiveSupportPlanReviewView.EvidenceRef;
import com.mentalbridge.care.supportplan.ReassessmentSymptomMapping.ResourceReason;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Proposal;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.ResourceDraft;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotDraft;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.SlotSpec;
import com.mentalbridge.care.supportplan.SupportPlanPolicy.Template;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanActionView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.PlanFocusView;
import com.mentalbridge.care.supportplan.SupportPlanV3Service.SupportPlanV3View;

class ReassessmentSymptomMappingPolicyTests {
	private static final Instant NOW = Instant.parse("2026-11-06T00:00:00Z");
	private static final UUID SUMMARY = UUID.randomUUID();
	private static final UUID REPORT = UUID.randomUUID();
	private static final UUID EVALUATION = UUID.randomUUID();
	private static final Template STANDARD = new Template(List.of(), List.of(
			new SlotSpec("depression-maintenance", "CORE", null, null, null, null, null, null, List.of(id("208"), id("205"))),
			new SlotSpec("anxiety-maintenance", "CORE", null, null, null, null, null, null, List.of(id("201"), id("206")))));
	private final AdaptiveSupportPlanReviewPolicy policy = new AdaptiveSupportPlanReviewPolicy();

	@Test
	void worryCreatesAnAlternativeWithoutRepeatedBarrierAndBindsExactAdmission() {
		var base = proposal("anxiety-maintenance", resource("201"), resource("211"));
		var options = ReassessmentSymptomMappingPolicy.options(policy, plan(base), base, STANDARD, SUMMARY,
				source("PERSISTENT_WORRY"), EVALUATION, List.of(), List.of());
		assertThat(options).singleElement().satisfies(candidate -> {
			assertThat(candidate.option().rationaleCodes()).containsExactly("SELF_REPORTED_SYMPTOM_MATCH");
			assertThat(candidate.option().kind()).isEqualTo("ALTERNATIVE_ACTION");
			assertThat(candidate.option().executionKind()).isEqualTo("PREPARE_REPLACEMENT");
			assertThat(candidate.option().evidenceRefs().getFirst().source()).isEqualTo("SELF_REPORTED_EXPERIENCE");
			assertThat(candidate.proposal().symptomMapping()).isEqualTo(candidate.option().symptomMapping());
			assertThat(candidate.option().symptomMapping().resourceReasons()).containsExactly(reason("PERSISTENT_WORRY", "anxiety-maintenance", "211"));
		});
	}

	@Test
	void sharedResourceMergesBothCodesAndSelectionsRemainComplete() {
		var base = proposal("depression-maintenance", resource("205"), resource("208"));
		var source = source("APPETITE_CHANGE", "LOW_ENERGY", "LOW_MOOD");
		var option = ReassessmentSymptomMappingPolicy.options(policy, plan(base), base, STANDARD, SUMMARY, source,
				EVALUATION, List.of(), List.of()).getFirst().option();
		assertThat(option.symptomMapping().selectedCodes()).containsExactly("APPETITE_CHANGE", "LOW_ENERGY", "LOW_MOOD");
		assertThat(option.symptomMapping().resourceReasons()).extracting(ResourceReason::code).containsExactly("LOW_ENERGY", "LOW_MOOD");
		assertThat(option.symptomMapping().resourceReasons()).extracting(ResourceReason::resourceId).containsOnly(resource("208").resourceId());
	}

	@Test
	void noResourceNoCompatibleSlotLegacyAndUnchangedIntentProduceNoAlternative() {
		var base = proposal("anxiety-maintenance", resource("201"), resource("211"));
		for (var source : List.of(source("APPETITE_CHANGE"), source("SOCIAL_WITHDRAWAL"), source("SLEEP_DIFFICULTY"))) {
			assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan(base), base, STANDARD, SUMMARY, source, EVALUATION, List.of(), List.of())).isEmpty();
		}
		var unchanged = proposal("anxiety-maintenance", resource("211"));
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan(unchanged), unchanged, STANDARD, SUMMARY,
				source("PERSISTENT_WORRY"), EVALUATION, List.of(), List.of())).isEmpty();
		var legacy = plan(base);
		when(legacy.representationMode()).thenReturn("V2_RESOURCE_ADAPTER");
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, legacy, base, STANDARD, SUMMARY,
				source("PERSISTENT_WORRY"), EVALUATION, List.of(), List.of())).isEmpty();
	}

	@Test
	void catalogOrderChoosesOneAlternativePerSlotAndExistingIntentWins() {
		var base = proposal("anxiety-maintenance", resource("201"), resource("203"), resource("209"), resource("211"));
		var source = source("CONCENTRATION_DIFFICULTY", "PERSISTENT_WORRY", "STRESS_OVERLOAD");
		var plan = plan(base);
		var result = ReassessmentSymptomMappingPolicy.options(policy, plan, base, STANDARD, SUMMARY, source, EVALUATION, List.of(), List.of());
		assertThat(result).singleElement().satisfies(candidate ->
				assertThat(candidate.proposal().slots().getFirst().selectedResource().resourceId()).isEqualTo(resource("211").resourceId()));
		var existing = new ArrayList<>(result);
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan, base, STANDARD, SUMMARY, source, EVALUATION, List.of(), existing)).isEmpty();
		assertThat(existing).isEqualTo(result);
	}

	@Test
	void laterReassessmentIgnoresObsoleteReasonForAStandardResourceButKeepsGuardForChecklistOnlyResource() {
		var depression = new SlotDraft("depression-maintenance", "CORE", "DEPRESSIVE_SYMPTOMS", "MAINTENANCE", resource("208"), List.of(resource("205")));
		var anxiety = new SlotDraft("anxiety-maintenance", "CORE", "ANXIETY_SYMPTOMS", "MAINTENANCE", resource("201"), List.of(resource("206"), resource("211")));
		var standardRetained = new Proposal(List.of(), List.of(depression, anxiety), "content-eligibility-v1", NOW,
				mapping(List.of("LOW_MOOD"), List.of(reason("LOW_MOOD", "depression-maintenance", "208"))));
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan(standardRetained), standardRetained, STANDARD, SUMMARY,
				source("PERSISTENT_WORRY"), EVALUATION, List.of(), List.of())).singleElement().satisfies(candidate -> {
			assertThat(candidate.proposal().slots()).extracting(slot -> slot.selectedResource().resourceId())
					.containsExactly(resource("208").resourceId(), resource("211").resourceId());
			assertThat(candidate.proposal().symptomMapping().resourceReasons()).containsExactly(reason("PERSISTENT_WORRY", "anxiety-maintenance", "211"));
		});
		var checklistOnly = new SlotDraft("anxiety-maintenance", "CORE", "ANXIETY_SYMPTOMS", "MAINTENANCE", resource("211"), List.of(resource("201")));
		var depressionArticle = new SlotDraft("depression-maintenance", "CORE", "DEPRESSIVE_SYMPTOMS", "MAINTENANCE", resource("205"), List.of(resource("208")));
		var checklistRetained = new Proposal(List.of(), List.of(depressionArticle, checklistOnly), "content-eligibility-v1", NOW,
				mapping(List.of("PERSISTENT_WORRY"), List.of(reason("PERSISTENT_WORRY", "anxiety-maintenance", "211"))));
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan(checklistRetained), checklistRetained, STANDARD, SUMMARY,
				source("LOW_MOOD"), EVALUATION, List.of(), List.of())).isEmpty();
	}

	@Test
	void equivalentGenericAlternativeAbsorbsChecklistReasonsEvidenceAndMappingInPlace() {
		var base = proposal("depression-maintenance", resource("205"), resource("208"));
		var changed = List.of(new SlotDraft("depression-maintenance", "CORE", "DEPRESSIVE_SYMPTOMS", "MAINTENANCE", resource("208"), List.of()));
		var pattern = new EvidenceRef("PATTERN", "not-a-good-fit-1");
		var genericOption = policy.option("ALTERNATIVE_ACTION", "PREPARE_REPLACEMENT", UUID.randomUUID(), null,
				List.of("REPEATED_NOT_A_GOOD_FIT", "PRACTICAL_FIT_ONLY"), List.of(pattern), List.of(), List.of(new Change("ACTION", "Resource 205", "Resource 208")));
		var keep = new Candidate(policy.option("KEEP_CURRENT", "NO_CHANGE", null, null, List.of("CURRENT_PLAN_ADMISSIBLE"), List.of(), List.of(), List.of()), null, null, null);
		var existing = new ArrayList<>(List.of(keep, new Candidate(genericOption, null, new Proposal(List.of(), changed, "content-eligibility-v1", NOW), EVALUATION)));
		var plan = plan(base);
		var source = source("LOW_ENERGY", "LOW_MOOD");
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan, base, STANDARD, SUMMARY, source, EVALUATION, List.of(), existing)).isEmpty();
		assertThat(existing).hasSize(2).first().isEqualTo(keep);
		var merged = existing.get(1);
		assertThat(merged.option().optionId()).isNotEqualTo(genericOption.optionId());
		assertThat(merged.option().kind()).isEqualTo("ALTERNATIVE_ACTION");
		assertThat(merged.option().actionId()).isEqualTo(genericOption.actionId());
		assertThat(merged.option().changes()).isEqualTo(genericOption.changes());
		assertThat(merged.option().rationaleCodes()).containsExactly("REPEATED_NOT_A_GOOD_FIT", "PRACTICAL_FIT_ONLY", "SELF_REPORTED_SYMPTOM_MATCH");
		assertThat(merged.option().evidenceRefs()).containsExactly(pattern, new EvidenceRef("SELF_REPORTED_EXPERIENCE", REPORT.toString()));
		assertThat(merged.option().symptomMapping().resourceReasons()).extracting(ResourceReason::code).containsExactly("LOW_ENERGY", "LOW_MOOD");
		assertThat(merged.proposal().symptomMapping()).isEqualTo(merged.option().symptomMapping());
		assertThat(merged.proposal().slots()).isEqualTo(changed);
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan, base, STANDARD, SUMMARY, source, EVALUATION, List.of(), existing)).isEmpty();
		assertThat(existing.get(1)).isEqualTo(merged);
	}

	@Test
	void sameResourceWithDifferentReportOrRevisionHasDifferentOptionIdentity() {
		var base = proposal("anxiety-maintenance", resource("201"), resource("211"));
		var original = source("PERSISTENT_WORRY");
		var changed = new ExplicitSelfReport(UUID.randomUUID(), original.sourceVersion(), 1, original.currentPeriod(),
				original.currentExperience(), null, null, NOW, NOW, original.symptomChecklist(), original.symptomSelectionReasons());
		var plan = plan(base);
		var first = ReassessmentSymptomMappingPolicy.options(policy, plan, base, STANDARD, SUMMARY, original, EVALUATION, List.of(), List.of()).getFirst();
		var second = ReassessmentSymptomMappingPolicy.options(policy, plan, base, STANDARD, SUMMARY, changed, EVALUATION, List.of(), List.of()).getFirst();
		assertThat(first.proposal().slots()).isEqualTo(second.proposal().slots());
		assertThat(first.option().optionId()).isNotEqualTo(second.option().optionId());
		assertThat(first.option().symptomMapping()).isNotEqualTo(second.option().symptomMapping());
	}

	@Test
	void mappingSurvivesJsonRoundTripAndRejectsUnknownSlotsCodesAndTupleChanges() throws Exception {
		var mapping = mapping(List.of("PERSISTENT_WORRY"), List.of(reason("PERSISTENT_WORRY", "anxiety-maintenance", "211")));
		var mapper = new ObjectMapper().findAndRegisterModules();
		assertThat(mapper.readValue(mapper.writeValueAsString(mapping), ReassessmentSymptomMapping.class)).isEqualTo(mapping);
		assertThatThrownBy(() -> mapping(List.of("PERSISTENT_WORRY"), List.of(reason("PERSISTENT_WORRY", "anxiety-psychoeducation", "211"))))
				.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> mapping(List.of("UNKNOWN"), mapping.resourceReasons())).isInstanceOf(IllegalArgumentException.class);
		assertThat(mapping.matches(proposal("anxiety-maintenance", resource("211")).slots())).isTrue();
		assertThat(mapping.matches(proposal("anxiety-maintenance", resource("201")).slots())).isFalse();
		assertThat(mapping.retaining(proposal("anxiety-maintenance", resource("201")).slots())).isNull();
	}

	@Test
	void storedSelectionsAndReasonsAreImmutable() {
		var codes = new ArrayList<>(List.of("PERSISTENT_WORRY"));
		var reasons = new ArrayList<>(List.of(reason("PERSISTENT_WORRY", "anxiety-maintenance", "211")));
		var mapping = mapping(codes, reasons);
		codes.clear();
		reasons.clear();
		assertThat(mapping.selectedCodes()).containsExactly("PERSISTENT_WORRY");
		assertThat(mapping.resourceReasons()).hasSize(1);
		assertThatThrownBy(() -> mapping.selectedCodes().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> mapping.resourceReasons().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void mappingRetainsOnlyAnUnchangedExactPublicationAndRole() {
		var selected = resource("211");
		var mapping = mapping(List.of("PERSISTENT_WORRY"), List.of(reason("PERSISTENT_WORRY", "anxiety-maintenance", "211")));
		var republished = new ResourceDraft(selected.resourceId(), selected.contentVersion(), UUID.randomUUID(), selected.role(),
				selected.category(), selected.title(), selected.summary(), selected.externalUrl());
		var changedRole = new ResourceDraft(selected.resourceId(), selected.contentVersion(), selected.publicationId(), "ADJUNCT",
				selected.category(), selected.title(), selected.summary(), selected.externalUrl());
		for (var changed : List.of(republished, changedRole)) {
			assertThat(mapping.matches(proposal("anxiety-maintenance", changed).slots())).isFalse();
			assertThat(mapping.retaining(proposal("anxiety-maintenance", changed).slots())).isNull();
		}
		assertThat(mapping.retaining(proposal("anxiety-maintenance", selected).slots())).isEqualTo(mapping);
	}

	@Test
	void aCandidateAlreadySelectedInAnotherSlotIsNotDuplicated() {
		var first = proposal("depression-maintenance", resource("205"), resource("208")).slots().getFirst();
		var second = new SlotDraft("anxiety-adjunct", "OPTIONAL", "ANXIETY_SYMPTOMS", "ANXIETY_SUPPORT_ADJUNCT", resource("208"), List.of());
		var base = new Proposal(List.of(), List.of(first, second), "content-eligibility-v1", NOW);
		assertThat(ReassessmentSymptomMappingPolicy.options(policy, plan(base), base, STANDARD, SUMMARY, source("LOW_ENERGY"),
				EVALUATION, List.of(), List.of())).isEmpty();
	}

	private ReassessmentSymptomMapping mapping(List<String> codes, List<ResourceReason> reasons) {
		return new ReassessmentSymptomMapping(SUMMARY, REPORT, 0, new Period(NOW.minusSeconds(14 * 86400), NOW),
				ReassessmentSymptomCatalog.CATALOG_REVISION, ReassessmentSymptomCatalog.MAPPING_REVISION, codes, reasons);
	}
	private ResourceReason reason(String code, String slot, String suffix) {
		var resource = resource(suffix);
		return new ResourceReason(code, slot, resource.resourceId(), "0", resource.publicationId(), "PRIMARY", "content-eligibility-v1", "SELF_REPORTED_SYMPTOM_MATCH");
	}
	private ExplicitSelfReport source(String... codes) {
		var checklist = new Checklist(ReassessmentSymptomCatalog.CATALOG_REVISION, ReassessmentSymptomCatalog.MAPPING_REVISION,
				List.of(codes).stream().sorted().toList());
		return new ExplicitSelfReport(REPORT, "reassessment-self-report-v1", 0, new Period(NOW.minusSeconds(14 * 86400), NOW),
				"UNSURE", null, null, NOW, NOW, checklist, ReassessmentSymptomCatalog.reasons(checklist));
	}
	private static String id(String suffix) { return "00000000-0000-4000-8000-000000000" + suffix; }
	private ResourceDraft resource(String suffix) {
		var id = UUID.fromString(id(suffix));
		return new ResourceDraft(id, 0, UUID.nameUUIDFromBytes(id.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
				"PRIMARY", "ARTICLE", "Resource " + suffix, "Summary", null);
	}
	private Proposal proposal(String slot, ResourceDraft selected, ResourceDraft... alternatives) {
		return new Proposal(List.of(), List.of(new SlotDraft(slot, "CORE", slot.startsWith("anxiety") ? "ANXIETY_SYMPTOMS" : "DEPRESSIVE_SYMPTOMS",
				"MAINTENANCE", selected, List.of(alternatives))), "content-eligibility-v1", NOW);
	}
	private SupportPlanV3View plan(Proposal proposal) {
		var plan = mock(SupportPlanV3View.class);
		var focus = mock(PlanFocusView.class);
		when(plan.representationMode()).thenReturn("NATIVE_V3");
		when(plan.focuses()).thenReturn(List.of(focus));
		var actions = proposal.slots().stream().map(slot -> {
			var action = mock(PlanActionView.class);
			var resource = slot.selectedResource();
			when(action.actionType()).thenReturn("RESOURCE");
			when(action.actionId()).thenReturn(UUID.randomUUID().toString());
			when(action.provenance()).thenReturn(new SupportPlanV3Service.ActionProvenanceView("SYSTEM_POLICY", slot.slotId(), "policy", NOW));
			when(action.source()).thenReturn(new SupportPlanV3Service.ActionSourceReferenceView("CONTENT", "RESOURCE_VERSION", resource.resourceId().toString(),
					Long.toString(resource.contentVersion()), "content-eligibility-v1", resource.publicationId(), "content-eligibility-v1", NOW));
			when(action.presentation()).thenReturn(new SupportPlanV3Service.ActionPresentationMetadataView("Resource", "Summary", null, null));
			return action;
		}).toList();
		when(focus.actions()).thenReturn(actions);
		return plan;
	}
}
