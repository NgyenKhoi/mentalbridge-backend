package com.mentalbridge.consultation.credits;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/api/v1/service-credits")
public class ServiceCreditController {

	private final ServiceCreditService credits;

	public ServiceCreditController(ServiceCreditService credits) {
		this.credits = credits;
	}

	@GetMapping
	ServiceCreditResponse current(@AuthenticationPrincipal Jwt jwt) {
		return credits.current(RequestIdentity.subject(jwt));
	}
}
