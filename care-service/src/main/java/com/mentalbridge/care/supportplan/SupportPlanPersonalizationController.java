package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.ActivityMode;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.CommonBarrier;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.DesiredPace;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationCommand;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PersonalizationView;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredDaypart;
import com.mentalbridge.care.supportplan.SupportPlanPersonalizationService.PreferredFocus;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v2/support-plan-personalization")
@Validated
public class SupportPlanPersonalizationController {

	private final SupportPlanPersonalizationService personalizations;

	public SupportPlanPersonalizationController(SupportPlanPersonalizationService personalizations) {
		this.personalizations = personalizations;
	}

	@GetMapping
	ResponseEntity<PersonalizationView> current(@AuthenticationPrincipal Jwt jwt) {
		var view = personalizations.current(subject(jwt));
		return ResponseEntity.ok().eTag(Long.toString(view.revision())).body(view);
	}

	@PutMapping
	ResponseEntity<PersonalizationView> replace(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@Valid @RequestBody PersonalizationRequest request) {
		var view = personalizations.replace(subject(jwt), version(ifMatch), request.command());
		return ResponseEntity.ok().eTag(Long.toString(view.revision())).body(view);
	}

	private long version(String ifMatch) {
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
	}

	private UUID subject(Jwt jwt) {
		try { return UUID.fromString(jwt.getSubject()); }
		catch (IllegalArgumentException exception) {
			throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
					"Authenticated account identifier is invalid");
		}
	}

	public record PersonalizationRequest(PreferredFocus preferredFocus, DesiredPace desiredPace,
			Integer dailyTimeBudgetMinutes, PreferredDaypart preferredDaypart,
			@NotNull @Size(max = 5) List<@NotNull ActivityMode> preferredActivityModes,
			@NotNull @Size(max = 3) List<@NotNull CommonBarrier> commonBarriers) {
		PersonalizationCommand command() {
			return new PersonalizationCommand(preferredFocus, desiredPace, dailyTimeBudgetMinutes,
					preferredDaypart, preferredActivityModes, commonBarriers);
		}
	}
}
