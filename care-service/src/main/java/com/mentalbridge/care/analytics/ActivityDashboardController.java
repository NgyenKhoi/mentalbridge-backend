package com.mentalbridge.care.analytics;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.analytics.ActivityDashboardContract.Response;
import com.mentalbridge.care.shared.ApiException;

import jakarta.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/api/v1/activity-dashboard")
public class ActivityDashboardController {

	private static final Set<String> PARAMETERS = Set.of("timezone", "range");
	private static final Set<Integer> RANGES = Set.of(7, 30, 90);
	private final ActivityDashboardService dashboards;

	public ActivityDashboardController(ActivityDashboardService dashboards) {
		this.dashboards = dashboards;
	}

	@GetMapping
	ResponseEntity<Response> dashboard(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID suppliedCorrelationId,
			HttpServletRequest request) {
		var input = request.getParameterMap();
		if (input.keySet().stream().anyMatch(key -> !PARAMETERS.contains(key))
				|| input.values().stream().anyMatch(values -> values.length != 1)) throw invalid();

		String timezoneValue = request.getParameter("timezone");
		if (timezoneValue == null || timezoneValue.isBlank() || timezoneValue.length() > 64) throw invalid();
		int range;
		try {
			range = Integer.parseInt(request.getParameter("range"));
		}
		catch (NumberFormatException exception) {
			throw invalid();
		}
		if (!RANGES.contains(range)) throw invalid();
		ZoneId timezone;
		try {
			timezone = ZoneId.of(timezoneValue);
		}
		catch (DateTimeException exception) {
			throw invalid();
		}

		UUID correlationId = suppliedCorrelationId == null ? UUID.randomUUID() : suppliedCorrelationId;
		Response body = dashboards.dashboard(subject(jwt), jwt.getTokenValue(), timezone, range, correlationId);
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Correlation-Id", correlationId.toString())
				.body(body);
	}

	private UUID subject(Jwt jwt) {
		try {
			return UUID.fromString(jwt.getSubject());
		}
		catch (IllegalArgumentException exception) {
			throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	private ApiException invalid() {
		return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Activity dashboard request is invalid");
	}
}
