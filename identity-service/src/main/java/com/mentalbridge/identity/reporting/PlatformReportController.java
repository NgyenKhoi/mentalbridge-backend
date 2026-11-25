package com.mentalbridge.identity.reporting;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/v1/admin/platform-reports")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class PlatformReportController {

	private final PlatformReportService service;

	public PlatformReportController(PlatformReportService service) {
		this.service = service;
	}

	@GetMapping("/catalogue")
	public List<PlatformReportService.ReportTypeResponse> catalogue() {
		return service.catalogue();
	}

	@GetMapping
	public PlatformReportService.ReportPage history(
			@RequestParam(required = false) @Size(max = 512) String cursor,
			@RequestParam(required = false) @Min(1) @Max(50) Integer limit) {
		return service.history(cursor, limit);
	}

	@PostMapping
	public ResponseEntity<PlatformReportService.ReportResponse> request(
			@AuthenticationPrincipal Jwt jwt,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey,
			@Valid @RequestBody PlatformReportService.ReportRequest request) {
		var response = service.request(subject(jwt), request, idempotencyKey);
		return ResponseEntity.accepted()
				.location(URI.create("/api/v1/admin/platform-reports/" + response.reportId()))
				.body(response);
	}

	@PostMapping("/{reportId}/retries")
	public ResponseEntity<PlatformReportService.ReportResponse> retry(
			@AuthenticationPrincipal Jwt jwt, @PathVariable UUID reportId,
			@RequestHeader("Idempotency-Key") @Size(min = 16, max = 128) String idempotencyKey) {
		var response = service.retry(subject(jwt), reportId, idempotencyKey);
		return ResponseEntity.accepted()
				.location(URI.create("/api/v1/admin/platform-reports/" + response.reportId()))
				.body(response);
	}

	@GetMapping("/{reportId}/artifact")
	public ResponseEntity<byte[]> download(@PathVariable UUID reportId) {
		var artifact = service.download(reportId);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(artifact.mediaType()))
				.contentLength(artifact.content().length)
				.header(HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment().filename(artifact.fileName()).build().toString())
				.header("X-Content-SHA256", artifact.contentSha256())
				.header("X-Retained-Until", artifact.retainedUntil().toString())
				.body(artifact.content());
	}

	private UUID subject(Jwt jwt) {
		return jwt == null ? null : UUID.fromString(jwt.getSubject());
	}

}
