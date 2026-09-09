package com.mentalbridge.care.assessment;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.mentalbridge.care.shared.ApiException;

@Component
public class AssessmentScoringPolicy {

	public ScoredResult score(String instrument, List<QuestionAnswer> answers, List<ScoreBand> bands,
			List<Integer> safetyItemNumbers) {
		var totalScore = answers.stream().mapToInt(QuestionAnswer::value).sum();
		var screeningLevel = bands.stream()
				.filter(band -> totalScore >= band.minimumScore() && totalScore <= band.maximumScore())
				.findFirst()
				.map(ScoreBand::level)
				.orElseThrow(this::unavailable);

		return switch (instrument) {
			case "PHQ9" -> phq9(answers, screeningLevel, totalScore, safetyItemNumbers);
			case "GAD7" -> gad7(screeningLevel, totalScore, safetyItemNumbers);
			default -> throw unavailable();
		};
	}

	private ScoredResult phq9(List<QuestionAnswer> answers, ScreeningLevel screeningLevel, int totalScore,
			List<Integer> safetyItemNumbers) {
		if (safetyItemNumbers.size() != 1) {
			throw unavailable();
		}
		var safetyItemNumber = safetyItemNumbers.getFirst();
		var safetyAnswer = answers.stream().filter(answer -> answer.itemNumber() == safetyItemNumber).findFirst()
				.orElseThrow(this::unavailable);
		var safetyItemPositive = safetyAnswer.value() >= 1;
		return new ScoredResult(totalScore, screeningLevel,
				safetyItemPositive ? SafetyStatus.POSITIVE_SAFETY_SCREEN : SafetyStatus.NEGATIVE_SAFETY_SCREEN,
				safetyItemPositive);
	}

	private ScoredResult gad7(ScreeningLevel screeningLevel, int totalScore, List<Integer> safetyItemNumbers) {
		if (!safetyItemNumbers.isEmpty()) {
			throw unavailable();
		}
		return new ScoredResult(totalScore, screeningLevel, SafetyStatus.NOT_APPLICABLE, null);
	}

	private ApiException unavailable() {
		return new ApiException(HttpStatus.CONFLICT, "QUESTIONNAIRE_VERSION_UNAVAILABLE",
				"Questionnaire scoring or safety metadata is unavailable");
	}

	public record QuestionAnswer(int itemNumber, int value) {
	}

	public record ScoreBand(ScreeningLevel level, int minimumScore, int maximumScore) {
	}

	public record ScoredResult(int totalScore, ScreeningLevel screeningLevel, SafetyStatus safetyStatus,
			Boolean safetyItemPositive) {
	}
}
