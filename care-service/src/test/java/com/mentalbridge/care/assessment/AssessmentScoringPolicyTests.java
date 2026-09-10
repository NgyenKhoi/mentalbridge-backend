package com.mentalbridge.care.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AssessmentScoringPolicyTests {

	private final AssessmentScoringPolicy policy = new AssessmentScoringPolicy();
	private final List<AssessmentScoringPolicy.ScoreBand> phq9Bands = List.of(
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.MINIMAL, 0, 4),
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.MILD, 5, 9),
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.MODERATE, 10, 14),
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.MODERATELY_SEVERE, 15, 19),
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.SEVERE, 20, 27));
	private final List<AssessmentScoringPolicy.ScoreBand> gad7Bands = List.of(
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.MINIMAL, 0, 4),
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.MILD, 5, 9),
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.MODERATE, 10, 14),
			new AssessmentScoringPolicy.ScoreBand(ScreeningLevel.SEVERE, 15, 21));

	@ParameterizedTest
	@CsvSource({
			"0,0,MINIMAL,NEGATIVE_SAFETY_SCREEN",
			"4,0,MINIMAL,NEGATIVE_SAFETY_SCREEN",
			"5,0,MILD,NEGATIVE_SAFETY_SCREEN",
			"9,1,MILD,POSITIVE_SAFETY_SCREEN",
			"10,0,MODERATE,NEGATIVE_SAFETY_SCREEN",
			"14,1,MODERATE,POSITIVE_SAFETY_SCREEN",
			"15,0,MODERATELY_SEVERE,NEGATIVE_SAFETY_SCREEN",
			"19,1,MODERATELY_SEVERE,POSITIVE_SAFETY_SCREEN",
			"20,0,SEVERE,NEGATIVE_SAFETY_SCREEN",
			"27,3,SEVERE,POSITIVE_SAFETY_SCREEN"
	})
	void resolvesEveryPhq9BoundaryWithoutAllowingItemNineToOverrideIt(int total, int itemNine,
			ScreeningLevel expectedLevel, SafetyStatus expectedSafety) {
		var result = policy.score("PHQ9", answers(9, total, itemNine), phq9Bands, List.of(9));

		assertThat(result.totalScore()).isEqualTo(total);
		assertThat(result.screeningLevel()).isEqualTo(expectedLevel);
		assertThat(result.safetyStatus()).isEqualTo(expectedSafety);
		assertThat(result.safetyItemPositive()).isEqualTo(itemNine >= 1);
	}

	@ParameterizedTest
	@CsvSource({
			"0,MINIMAL",
			"4,MINIMAL",
			"5,MILD",
			"9,MILD",
			"10,MODERATE",
			"14,MODERATE",
			"15,SEVERE",
			"21,SEVERE"
	})
	void resolvesEveryGad7BoundaryWithNotApplicableSafety(int total, ScreeningLevel expectedLevel) {
		var result = policy.score("GAD7", answers(7, total, 0), gad7Bands, List.of());

		assertThat(result.totalScore()).isEqualTo(total);
		assertThat(result.screeningLevel()).isEqualTo(expectedLevel);
		assertThat(result.safetyStatus()).isEqualTo(SafetyStatus.NOT_APPLICABLE);
		assertThat(result.safetyItemPositive()).isNull();
	}

	private List<AssessmentScoringPolicy.QuestionAnswer> answers(int count, int total, int lastItemValue) {
		var remaining = total - lastItemValue;
		var answers = new java.util.ArrayList<AssessmentScoringPolicy.QuestionAnswer>();
		for (var item = 1; item < count; item++) {
			var value = Math.min(3, remaining);
			answers.add(new AssessmentScoringPolicy.QuestionAnswer(item, value));
			remaining -= value;
		}
		answers.add(new AssessmentScoringPolicy.QuestionAnswer(count, lastItemValue + remaining));
		return answers;
	}
}
