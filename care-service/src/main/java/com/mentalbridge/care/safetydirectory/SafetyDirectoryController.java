package com.mentalbridge.care.safetydirectory;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.LookupRequest;
import com.mentalbridge.care.safetydirectory.SafetyDirectoryContract.LookupResponse;

@RestController
@RequestMapping("/api/v1/safety-directory-lookups")
public class SafetyDirectoryController {
	private final SafetyDirectoryService service;

	public SafetyDirectoryController(SafetyDirectoryService service) { this.service = service; }

	@PostMapping
	ResponseEntity<LookupResponse> lookup(@RequestBody LookupRequest request,
			@RequestHeader(value = "X-Correlation-Id", required = false) String correlationId) {
		var safeCorrelationId = correlationId == null || correlationId.isBlank() ? UUID.randomUUID().toString() : correlationId;
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(service.lookup(request, safeCorrelationId));
	}
}
