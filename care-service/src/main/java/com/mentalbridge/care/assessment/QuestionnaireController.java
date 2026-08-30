package com.mentalbridge.care.assessment;

import jakarta.validation.constraints.Size;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/questionnaires")
@Validated
public class QuestionnaireController {

	private final QuestionnaireService questionnaires;

	public QuestionnaireController(QuestionnaireService questionnaires) {
		this.questionnaires = questionnaires;
	}

	@GetMapping("/{instrument}/current")
	QuestionnaireService.QuestionnaireView current(@PathVariable Instrument instrument,
			@RequestParam(defaultValue = "vi-VN") @Size(min = 2, max = 16) String locale) {
		return questionnaires.current(instrument.name(), locale);
	}

	public enum Instrument {
		PHQ9,
		GAD7
	}
}
