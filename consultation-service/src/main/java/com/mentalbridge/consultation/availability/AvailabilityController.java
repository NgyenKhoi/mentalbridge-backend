package com.mentalbridge.consultation.availability;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.consultation.shared.ApiException;
import com.mentalbridge.consultation.shared.RequestIdentity;

@RestController
@RequestMapping("/api/v1/availability-slots")
public class AvailabilityController {

	private final AvailabilityService availability;

	public AvailabilityController(AvailabilityService availability) {
		this.availability = availability;
	}

	@GetMapping
	AvailabilitySlotResponse.ListResponse list(@AuthenticationPrincipal Jwt jwt,
			@RequestParam(required = false) String from, @RequestParam(required = false) String to,
			@RequestParam(defaultValue = "true") boolean includeWithdrawn) {
		return AvailabilitySlotResponse.ListResponse.from(
				availability.list(RequestIdentity.subject(jwt), from, to, includeWithdrawn));
	}

	@PostMapping
	ResponseEntity<AvailabilitySlotResponse> publish(@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @NotBlank @Size(min = 16, max = 128)
			@Pattern(regexp = "^[!-~]+$") String idempotencyKey,
			@Valid @RequestBody PublishAvailabilityRequest request) {
		var slot = availability.publish(RequestIdentity.subject(jwt), idempotencyKey, request.command());
		return ResponseEntity.created(URI.create("/api/v1/availability-slots/" + slot.id()))
				.eTag(Long.toString(slot.version())).body(AvailabilitySlotResponse.from(slot));
	}

	@DeleteMapping("/{slotId}")
	ResponseEntity<AvailabilitySlotResponse> withdraw(@AuthenticationPrincipal Jwt jwt,
			@PathVariable UUID slotId, @RequestHeader(name = "If-Match", required = false) String ifMatch) {
		var slot = availability.withdraw(RequestIdentity.subject(jwt), slotId, requiredVersion(ifMatch));
		return ResponseEntity.ok().eTag(Long.toString(slot.version())).body(AvailabilitySlotResponse.from(slot));
	}

	private long requiredVersion(String ifMatch) {
		if (ifMatch == null || !ifMatch.matches("\\\"[0-9]+\\\"")) {
			throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "AVAILABILITY_SLOT_VERSION_REQUIRED",
					"If-Match must contain the quoted current availability slot version");
		}
		return Long.parseLong(ifMatch.substring(1, ifMatch.length() - 1));
	}

	public record PublishAvailabilityRequest(
			@NotBlank @Pattern(regexp = ".*Z$") String startAt,
			@NotBlank @Pattern(regexp = ".*Z$") String endAt,
			@NotBlank @Size(max = 64) String timezone,
			@NotNull AvailabilityModality modality) {

		AvailabilityService.PublishCommand command() {
			return new AvailabilityService.PublishCommand(startAt, endAt, timezone, modality);
		}
	}
}
