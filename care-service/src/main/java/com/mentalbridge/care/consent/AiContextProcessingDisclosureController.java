package com.mentalbridge.care.consent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-context-processing-disclosures")
public class AiContextProcessingDisclosureController {
	private final AiContextProcessingDisclosureService disclosures;

	public AiContextProcessingDisclosureController(AiContextProcessingDisclosureService disclosures) {
		this.disclosures = disclosures;
	}

	@GetMapping("/current")
	AiProcessingDisclosureController.AiProcessingDisclosureResponse current(
			@RequestParam(defaultValue = "vi-VN") String locale) {
		return AiProcessingDisclosureController.AiProcessingDisclosureResponse.from(disclosures.current(locale));
	}
}
