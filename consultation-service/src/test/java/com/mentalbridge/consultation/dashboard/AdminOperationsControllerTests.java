package com.mentalbridge.consultation.dashboard;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminOperationsControllerTests {

	private AdminOperationsService service;
	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		service = mock(AdminOperationsService.class);
		mockMvc = MockMvcBuilders.standaloneSetup(new AdminOperationsController(service))
				.build();
	}

	@Test
	void getOperationsSummaryReturns200WithAuthoritativeAggregates() throws Exception {
		var now = Instant.parse("2026-10-05T12:00:00Z");
		var specialists = new AdminOperationsResponse.AdminSpecialistOperationsSummary(
				10, 2, 7, 1, 0);
		var appointments = new AdminOperationsResponse.AdminAppointmentOperationsSummary(
				25, 3, 8, 2, 1, 7, 2, 1, 1, 0, 0, 0);
		var response = new AdminOperationsResponse("CONSULTATION", now, specialists, appointments);

		when(service.getOperationsSummary()).thenReturn(response);

		mockMvc.perform(get("/api/v1/admin/operations/summary")
				.accept(MediaType.APPLICATION_JSON))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.source").value("CONSULTATION"))
				.andExpect(jsonPath("$.asOf").value("2026-10-05T12:00:00Z"))
				.andExpect(jsonPath("$.specialists.total").value(10))
				.andExpect(jsonPath("$.specialists.pendingReview").value(2))
				.andExpect(jsonPath("$.specialists.active").value(7))
				.andExpect(jsonPath("$.specialists.rejected").value(1))
				.andExpect(jsonPath("$.specialists.suspended").value(0))
				.andExpect(jsonPath("$.appointments.total").value(25))
				.andExpect(jsonPath("$.appointments.requested").value(3))
				.andExpect(jsonPath("$.appointments.confirmed").value(8))
				.andExpect(jsonPath("$.appointments.inProgress").value(2))
				.andExpect(jsonPath("$.appointments.sessionEnded").value(1))
				.andExpect(jsonPath("$.appointments.completed").value(7))
				.andExpect(jsonPath("$.appointments.cancelled").value(2))
				.andExpect(jsonPath("$.appointments.rejected").value(1))
				.andExpect(jsonPath("$.appointments.expired").value(1))
				.andExpect(jsonPath("$.appointments.userNoShow").value(0))
				.andExpect(jsonPath("$.appointments.specialistNoShow").value(0))
				.andExpect(jsonPath("$.appointments.bothNoShow").value(0))
				// Assert strictly aggregate facts - no sensitive fields exist
				.andExpect(jsonPath("$.journal").doesNotExist())
				.andExpect(jsonPath("$.notes").doesNotExist())
				.andExpect(jsonPath("$.chatBody").doesNotExist())
				.andExpect(jsonPath("$.assessment").doesNotExist());
	}
}
