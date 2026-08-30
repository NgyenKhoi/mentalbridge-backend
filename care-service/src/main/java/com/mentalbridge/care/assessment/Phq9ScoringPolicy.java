package com.mentalbridge.care.assessment;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.shared.ApiException;

@Component
public class Phq9ScoringPolicy {

	public ScoredResult score(List<QuestionAnswer> answers, List<ScoreBand> bands, int safetyItemNumber) {
		var totalScore = answers.stream().mapToInt(QuestionAnswer::value).sum();
		var screeningLevel = bands.stream()
				.filter(band -> totalScore >= band.minimumScore() && totalScore <= band.maximumScore())
				.findFirst()
				.map(ScoreBand::level)
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUESTIONNAIRE_VERSION_UNAVAILABLE",
						"Questionnaire scoring bands are unavailable"));
		var safetyAnswer = answers.stream().filter(answer -> answer.itemNumber() == safetyItemNumber).findFirst()
				.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "QUESTIONNAIRE_VERSION_UNAVAILABLE",
						"Questionnaire safety item is unavailable"));
		var safetyStatus = safetyAnswer.value() >= 1 ? SafetyStatus.POSITIVE_SAFETY_SCREEN
				: SafetyStatus.NEGATIVE_SAFETY_SCREEN;
		return new ScoredResult(totalScore, screeningLevel, safetyStatus);
	}

	public record QuestionAnswer(int itemNumber, int value) {
	}

	public record ScoreBand(ScreeningLevel level, int minimumScore, int maximumScore) {
	}

	public record ScoredResult(int totalScore, ScreeningLevel screeningLevel, SafetyStatus safetyStatus) {
	}
}
