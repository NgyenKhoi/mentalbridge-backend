package com.mentalbridge.care.consent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ai-processing-disclosures")
public class AiProcessingDisclosureController {

	private final AiProcessingDisclosureService disclosures;

	public AiProcessingDisclosureController(AiProcessingDisclosureService disclosures) {
		this.disclosures = disclosures;
	}

	@GetMapping("/current")
	AiProcessingDisclosureResponse current(@RequestParam(defaultValue = "vi-VN") String locale) {
		return AiProcessingDisclosureResponse.from(disclosures.current(locale));
	}

	public record AiProcessingDisclosureResponse(String consentType, String version, String locale, String title,
			String content, boolean capstoneOnly) {
		static AiProcessingDisclosureResponse from(AiProcessingDisclosureService.DisclosureView value) {
			return new AiProcessingDisclosureResponse(value.consentType(), value.version(), value.locale(), value.title(),
					value.content(), value.capstoneOnly());
		}
	}
}
