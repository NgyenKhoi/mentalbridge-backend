package com.mentalbridge.identity.reporting;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.constraints.Min;

@RestController
@RequestMapping("/api/v1/admin/platform-report-schedules")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class ReportScheduleController {
	private final ReportScheduleService service;

	ReportScheduleController(ReportScheduleService service) { this.service = service; }

	@GetMapping
	public List<ReportScheduleService.ScheduleResponse> list() { return service.list(); }

	@PostMapping
	public ResponseEntity<ReportScheduleService.ScheduleResponse> create(@AuthenticationPrincipal Jwt jwt,
			@RequestBody ReportScheduleService.ScheduleRequest request) {
		return ResponseEntity.status(201).body(service.create(UUID.fromString(jwt.getSubject()), request));
	}

	@PutMapping("/{scheduleId}")
	public ReportScheduleService.ScheduleResponse update(@PathVariable UUID scheduleId,
			@RequestParam @Min(0) long expectedVersion, @RequestBody ReportScheduleService.ScheduleRequest request) {
		return service.update(scheduleId, request, expectedVersion);
	}

	@DeleteMapping("/{scheduleId}")
	public ResponseEntity<Void> delete(@PathVariable UUID scheduleId, @RequestParam @Min(0) long expectedVersion) {
		service.delete(scheduleId, expectedVersion);
		return ResponseEntity.noContent().build();
	}
}
