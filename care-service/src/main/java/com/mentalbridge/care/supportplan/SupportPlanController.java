package com.mentalbridge.care.supportplan;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanService.ProposeCommand;
import com.mentalbridge.care.supportplan.SupportPlanService.ReplaceChoicesCommand;
import com.mentalbridge.care.supportplan.SupportPlanService.SlotSelection;
import com.mentalbridge.care.supportplan.SupportPlanService.SupportPlanView;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/support-plans")
@Validated
public class SupportPlanController {

	private final SupportPlanService plans;

	public SupportPlanController(SupportPlanService plans) {
		this.plans = plans;
	}

	@PostMapping
	ResponseEntity<SupportPlanView> propose(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody ProposeRequest request) {
		var plan = plans.propose(subject(jwt), jwt.getTokenValue(), key,
				correlationId == null ? UUID.randomUUID() : correlationId,
				new ProposeCommand(request.sourceSupportEvaluationId()));
		return ResponseEntity.created(URI.create("/api/v1/support-plans/current-draft"))
				.header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@GetMapping("/current-draft")
	ResponseEntity<SupportPlanView> currentDraft(@AuthenticationPrincipal Jwt jwt) {
		var plan = plans.currentDraft(subject(jwt));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@GetMapping("/current")
	ResponseEntity<SupportPlanView> currentPlan(@AuthenticationPrincipal Jwt jwt) {
		var plan = plans.currentPlan(subject(jwt));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@PutMapping("/{supportPlanId}/choices")
	ResponseEntity<SupportPlanView> choices(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId,
			@Valid @RequestBody ReplaceChoicesRequest request) {
		var selections = request.slotSelections().stream().map(selection -> new SlotSelection(selection.slotId(),
				selection.resourceId(), selection.contentVersion())).toList();
		var plan = plans.changeChoices(subject(jwt), jwt.getTokenValue(), supportPlanId, version(ifMatch),
				correlationId == null ? UUID.randomUUID() : correlationId, new ReplaceChoicesCommand(selections));
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
	}

	@PostMapping("/{supportPlanId}/activate")
	ResponseEntity<SupportPlanView> activate(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID supportPlanId,
			@RequestHeader("If-Match") @Pattern(regexp = "^\"[0-9]+\"$") String ifMatch,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) @Pattern(regexp = "^[!-~]+$") String key,
			@RequestHeader(name = "X-Correlation-Id", required = false) UUID correlationId) {
		var plan = plans.activate(subject(jwt), jwt.getTokenValue(), supportPlanId, version(ifMatch), key,
				correlationId == null ? UUID.randomUUID() : correlationId);
		return ResponseEntity.ok().header(HttpHeaders.ETAG, '"' + Long.toString(plan.version()) + '"').body(plan);
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

	private long version(String ifMatch) {
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
	}

	public record ProposeRequest(@NotNull UUID sourceSupportEvaluationId) { }
	public record ReplaceChoicesRequest(@NotNull @Size(min = 1, max = 5) List<@Valid SlotSelectionRequest> slotSelections) { }
	public record SlotSelectionRequest(@NotNull @Size(min = 1, max = 64) String slotId,
			@NotNull UUID resourceId, @NotNull @Pattern(regexp = "^[0-9]+$") String contentVersion) { }
}
