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

	public QuestionnaireService(QuestionnaireDefinitionRepository definitions,
			QuestionnaireQuestionRepository questions) {
		this.definitions = definitions;
		this.questions = questions;
	}

	@Transactional(readOnly = true)
	public QuestionnaireView current(String instrument, String locale) {
		var definition = definitions.findByInstrumentAndLocaleAndStatus(instrument, locale, "PUBLISHED")
				.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "QUESTIONNAIRE_NOT_FOUND",
						"A published questionnaire is not available"));
		var questionnaireQuestions = questions.findByDefinitionIdOrderByItemNumber(definition.id()).stream()
				.map(question -> new QuestionView(question.id(), question.itemNumber(), question.prompt())).toList();
		var responseOptions = new java.util.ArrayList<ResponseOptionView>();
		definition.responseOptions().forEach(option -> responseOptions
				.add(new ResponseOptionView(option.path("value").asInt(), option.path("label").asText())));
		return new QuestionnaireView(definition.id(), definition.instrument(), definition.version(),
				definition.locale(), definition.title(), definition.referencePeriodDays(), List.copyOf(responseOptions),
				questionnaireQuestions);
	}

	public record QuestionnaireView(UUID definitionId, String instrument, String version, String locale, String title,
			int referencePeriodDays, List<ResponseOptionView> responseOptions, List<QuestionView> questions) {
	}

	public record ResponseOptionView(int value, String label) {
	}

	public record QuestionView(UUID questionId, int itemNumber, String prompt) {
	}
}
