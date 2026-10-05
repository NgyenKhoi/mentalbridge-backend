package com.mentalbridge.consultation.dashboard;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/operations")
public class AdminOperationsController {

	private final AdminOperationsService operationsService;

	public AdminOperationsController(AdminOperationsService operationsService) {
		this.operationsService = operationsService;
	}

	@GetMapping("/summary")
	public ResponseEntity<AdminOperationsResponse> getSummary() {
		return ResponseEntity.ok(operationsService.getOperationsSummary());
	}
}

