package com.mentalbridge.care.supportguide;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.mentalbridge.care.assessment.ScreeningLevel;

class SupportGuideResourceMapTests {

	private static final Set<String> SELF_GUIDED_ONLY = Set.of("SELF_GUIDED_SUPPORT");
	private static final Set<String> ALL_TIERS = Set.of("SELF_GUIDED_SUPPORT", "PROFESSIONAL_SUPPORT_RECOMMENDED",
			"SAFETY_FOLLOW_UP_RECOMMENDED");
	private static final Set<String> PHQ9_ALL = Set.of("MINIMAL", "MILD", "MODERATE", "MODERATELY_SEVERE", "SEVERE");
	private static final Set<String> GAD7_ALL = Set.of("MINIMAL", "MILD", "MODERATE", "SEVERE");
	private static final Set<String> LOW = Set.of("MINIMAL", "MILD");

	private static final Map<String, Declaration> REVIEWED_PRIMARY = Map.of(
			"00000000-0000-4000-8000-000000000201:ANXIETY_SYMPTOMS", new Declaration(GAD7_ALL, ALL_TIERS),
			"00000000-0000-4000-8000-000000000202:ANXIETY_SYMPTOMS", new Declaration(LOW, SELF_GUIDED_ONLY),
			"00000000-0000-4000-8000-000000000205:DEPRESSIVE_SYMPTOMS", new Declaration(PHQ9_ALL, ALL_TIERS),
			"00000000-0000-4000-8000-000000000206:ANXIETY_SYMPTOMS", new Declaration(GAD7_ALL, ALL_TIERS),
			"00000000-0000-4000-8000-000000000208:DEPRESSIVE_SYMPTOMS", new Declaration(PHQ9_ALL, ALL_TIERS),
			"00000000-0000-4000-8000-000000000211:ANXIETY_SYMPTOMS", new Declaration(LOW, SELF_GUIDED_ONLY));

	static Stream<Arguments> legalPairs() {
		return Stream.of(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, ScreeningLevel.MODERATE,
				ScreeningLevel.MODERATELY_SEVERE, ScreeningLevel.SEVERE)
				.flatMap(phq9 -> Stream.of(ScreeningLevel.MINIMAL, ScreeningLevel.MILD, ScreeningLevel.MODERATE,
						ScreeningLevel.SEVERE).map(gad7 -> Arguments.of(phq9, gad7)));
	}

	@Test
	void coversExactlyTheTwentyApprovedMeaningRows() {
		assertThat(SupportGuideResourceMap.ROWS).hasSize(20);
		assertThat(SupportGuideResourceMap.ROWS.keySet()).containsExactlyInAnyOrderElementsOf(legalPairs()
				.map(pair -> "PHQ9_" + ((ScreeningLevel) pair.get()[0]).name() + "_GAD7_"
						+ ((ScreeningLevel) pair.get()[1]).name())
				.toList());
		assertThat(SupportGuideResourceMap.VERSION).isEqualTo("mb-support-guide-resource-map-v1");
	}

	@ParameterizedTest
	@MethodSource("legalPairs")
	void everyCandidateIsAReviewedPrimaryResourceForThatRowsDomainLevelAndPathway(ScreeningLevel phq9,
			ScreeningLevel gad7) {
		List<SupportGuideResourceMap.Candidate> row = SupportGuideResourceMap.candidates(phq9, gad7);
		var identities = new HashSet<String>();

		assertThat(row).isNotEmpty().hasSizeLessThanOrEqualTo(4);
		for (var candidate : row) {
			assertThat(identities.add(candidate.key())).as("duplicate %s", candidate.key()).isTrue();
			assertThat(candidate.contentVersion()).isEqualTo("0");
			var declaration = REVIEWED_PRIMARY.get(candidate.resourceId() + ":" + candidate.domain());
			assertThat(declaration).as("PRIMARY declaration for %s", candidate).isNotNull();
			var level = "DEPRESSIVE_SYMPTOMS".equals(candidate.domain()) ? phq9 : gad7;
			var pathway = level == ScreeningLevel.MINIMAL || level == ScreeningLevel.MILD
					? "SELF_GUIDED_SUPPORT" : "PROFESSIONAL_SUPPORT_RECOMMENDED";
			assertThat(declaration.levels()).contains(level.name());
			assertThat(declaration.tiers()).contains(pathway);
		}
	}

	@Test
	void professionalRowsLeadWithTheDomainTheApprovedMeaningNamesFirst() {
		assertThat(SupportGuideResourceMap.candidates(ScreeningLevel.MODERATE, ScreeningLevel.SEVERE).getFirst().domain())
				.isEqualTo("ANXIETY_SYMPTOMS");
		assertThat(SupportGuideResourceMap.candidates(ScreeningLevel.SEVERE, ScreeningLevel.MODERATE).getFirst().domain())
				.isEqualTo("DEPRESSIVE_SYMPTOMS");
		assertThat(SupportGuideResourceMap.candidates(ScreeningLevel.MILD, ScreeningLevel.MODERATE))
				.allSatisfy(candidate -> assertThat(candidate.domain()).isEqualTo("ANXIETY_SYMPTOMS"));
	}

	@Test
	void rejectsAPairOutsideTheReviewedMatrix() {
		assertThatThrownBy(() -> SupportGuideResourceMap.candidates(ScreeningLevel.MILD,
				ScreeningLevel.MODERATELY_SEVERE)).isInstanceOf(IllegalStateException.class);
	}

	private record Declaration(Set<String> levels, Set<String> tiers) { }
}
