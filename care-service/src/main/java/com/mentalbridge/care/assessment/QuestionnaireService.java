package com.mentalbridge.care.assessment;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.care.shared.ApiException;

@Service
public class QuestionnaireService {

	private final QuestionnaireDefinitionRepository definitions;
	private final QuestionnaireQuestionRepository questions;
	private final QuestionnaireScoreBandRepository scoreBands;

	public QuestionnaireService(QuestionnaireDefinitionRepository definitions,
			QuestionnaireQuestionRepository questions, QuestionnaireScoreBandRepository scoreBands) {
		this.definitions = definitions;
		this.questions = questions;
		this.scoreBands = scoreBands;
	}

	@Transactional(readOnly = true)
	public QuestionnaireView current(String instrument, String locale) {
		var definition = definitions.findByInstrumentAndLocaleAndStatus(instrument, locale, "PUBLISHED")
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUESTIONNAIRE_NOT_FOUND",
						"A published questionnaire is not available"));
		return view(definition);
	}

	@Transactional(readOnly = true)
	public QuestionnaireView definition(UUID definitionId) {
		var definition = definitions.findById(definitionId)
				.filter(QuestionnaireDefinitionEntity::readable)
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUESTIONNAIRE_NOT_FOUND",
						"Questionnaire definition was not found"));
		return view(definition);
	}

	private QuestionnaireView view(QuestionnaireDefinitionEntity definition) {
		var questionnaireQuestions = questions.findByDefinitionIdOrderByItemNumber(definition.id()).stream()
				.map(question -> new QuestionView(question.id(), question.itemNumber(), question.prompt())).toList();
		var responseOptions = new java.util.ArrayList<ResponseOptionView>();
		definition.responseOptions().forEach(option -> responseOptions
				.add(new ResponseOptionView(option.path("value").asInt(), option.path("label").asText())));
		var questionnaireScoreBands = scoreBands.findByDefinitionIdOrderByOrdinal(definition.id()).stream()
				.map(band -> new ScoreBandView(band.code(), band.minimumScore(), band.maximumScore())).toList();
		return new QuestionnaireView(definition.id(), definition.instrument(), definition.version(),
				definition.locale(), definition.title(), definition.referencePeriodDays(), definition.scoringVersion(),
				List.copyOf(responseOptions), questionnaireQuestions, questionnaireScoreBands);
	}

	public record QuestionnaireView(UUID definitionId, String instrument, String version, String locale, String title,
			int referencePeriodDays, String scoringVersion, List<ResponseOptionView> responseOptions,
			List<QuestionView> questions, List<ScoreBandView> scoreBands) {
	}

	public record ResponseOptionView(int value, String label) {
	}

	public record QuestionView(UUID questionId, int itemNumber, String prompt) {
	}

	public record ScoreBandView(ScreeningLevel screeningLevel, int minimumScore, int maximumScore) {
	}
}
