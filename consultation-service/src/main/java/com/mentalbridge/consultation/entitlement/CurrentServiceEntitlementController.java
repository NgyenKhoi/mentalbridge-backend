package com.mentalbridge.consultation.entitlement;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/internal/v1/entitlements")
public class CurrentServiceEntitlementController {

	private final CurrentServiceEntitlementService entitlements;

	public CurrentServiceEntitlementController(CurrentServiceEntitlementService entitlements) {
		this.entitlements = entitlements;
	}

	@GetMapping("/current")
	CurrentServiceEntitlementResponse current(@AuthenticationPrincipal Jwt jwt) {
		return CurrentServiceEntitlementResponse.from(entitlements.current(RequestIdentity.subject(jwt)));
	}
}
