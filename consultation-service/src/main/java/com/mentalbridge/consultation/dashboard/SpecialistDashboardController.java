package com.mentalbridge.consultation.dashboard;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
public class SpecialistDashboardController {

	private final SpecialistDashboardService dashboards;

	public SpecialistDashboardController(SpecialistDashboardService dashboards) {
		this.dashboards = dashboards;
	}

	@GetMapping("/api/v1/specialist/dashboard")
	SpecialistDashboardResponse dashboard(@AuthenticationPrincipal Jwt jwt) {
		return dashboards.dashboard(RequestIdentity.subject(jwt));
	}
}
