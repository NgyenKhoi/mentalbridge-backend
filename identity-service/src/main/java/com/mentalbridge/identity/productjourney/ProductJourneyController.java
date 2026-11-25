package com.mentalbridge.identity.productjourney;

import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/product-journey-metrics")
@PreAuthorize("hasRole('ADMIN')")
public class ProductJourneyController {

	private final ProductJourneyService service;

	public ProductJourneyController(ProductJourneyService service) { this.service = service; }

	@GetMapping
	public ProductJourneyService.Response metrics(@AuthenticationPrincipal Jwt jwt,
			@RequestParam String from, @RequestParam String to) {
		return service.metrics(instant(from), instant(to), jwt.getTokenValue());
	}

	private Instant instant(String value) {
		try { return Instant.parse(value); }
		catch (DateTimeParseException exception) { throw new ProductJourneyService.InvalidWindowException(); }
	}

	@ExceptionHandler(ProductJourneyService.InvalidWindowException.class)
	ProblemDetail invalidWindow(HttpServletRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
				"from and to must define a completed UTC window of at most 366 days");
		problem.setType(URI.create("https://mentalbridge.example/problems/invalid-product-journey-window"));
		problem.setTitle("INVALID_PRODUCT_JOURNEY_WINDOW");
		problem.setInstance(URI.create(request.getRequestURI()));
		return problem;
	}
}
