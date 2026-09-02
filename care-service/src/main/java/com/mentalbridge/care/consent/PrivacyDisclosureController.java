package com.mentalbridge.care.consent;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/privacy-disclosures")
public class PrivacyDisclosureController {

	private final PrivacyDisclosureService disclosures;

	public PrivacyDisclosureController(PrivacyDisclosureService disclosures) {
		this.disclosures = disclosures;
	}

	@GetMapping("/current")
	PrivacyDisclosureResponse current(@RequestParam(defaultValue = "vi-VN") String locale) {
		return PrivacyDisclosureResponse.from(disclosures.current(locale));
	}

	public record PrivacyDisclosureResponse(String consentType, String version, String locale, String title,
			String content, boolean capstoneOnly) {
		static PrivacyDisclosureResponse from(PrivacyDisclosureService.DisclosureView value) {
			return new PrivacyDisclosureResponse(value.consentType(), value.version(), value.locale(), value.title(),
					value.content(), value.capstoneOnly());
		}
	}
}
