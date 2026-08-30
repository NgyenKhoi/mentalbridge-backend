package com.mentalbridge.care.assessment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class Phq9ScoringPolicyTests {

	private final Phq9ScoringPolicy policy = new Phq9ScoringPolicy();
	private final List<Phq9ScoringPolicy.ScoreBand> bands = List.of(
			new Phq9ScoringPolicy.ScoreBand(ScreeningLevel.MINIMAL, 0, 4),
			new Phq9ScoringPolicy.ScoreBand(ScreeningLevel.MILD, 5, 9),
			new Phq9ScoringPolicy.ScoreBand(ScreeningLevel.MODERATE, 10, 14),
			new Phq9ScoringPolicy.ScoreBand(ScreeningLevel.MODERATELY_SEVERE, 15, 19),
			new Phq9ScoringPolicy.ScoreBand(ScreeningLevel.SEVERE, 20, 27));

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
	void resolvesEveryBandBoundaryWithoutAllowingItemNineToOverrideIt(int total, int itemNine,
			ScreeningLevel expectedLevel, SafetyStatus expectedSafety) {
		var otherTotal = total - itemNine;
		var answers = new java.util.ArrayList<Phq9ScoringPolicy.QuestionAnswer>();
		for (var item = 1; item <= 8; item++) {
			var value = Math.min(3, otherTotal);
			answers.add(new Phq9ScoringPolicy.QuestionAnswer(item, value));
			otherTotal -= value;
		}
		answers.add(new Phq9ScoringPolicy.QuestionAnswer(9, itemNine));

		var result = policy.score(answers, bands, 9);

		assertThat(result.totalScore()).isEqualTo(total);
		assertThat(result.screeningLevel()).isEqualTo(expectedLevel);
		assertThat(result.safetyStatus()).isEqualTo(expectedSafety);
	}
}
