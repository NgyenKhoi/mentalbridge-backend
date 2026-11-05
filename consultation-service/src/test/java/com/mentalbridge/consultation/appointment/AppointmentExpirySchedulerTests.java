package com.mentalbridge.consultation.appointment;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.mentalbridge.consultation.shared.ApiException;

class AppointmentExpirySchedulerTests {

	private final AppointmentDecisionService decisions = mock(AppointmentDecisionService.class);
	private final AppointmentExpiryScheduler scheduler = new AppointmentExpiryScheduler(decisions);

	@Test
	void fullPagesOfPermanentlyFailingRequestsDoNotStarveLaterValidRequests() {
		var start = new UUID(0, 0);
		var failing = LongStream.rangeClosed(1, 100).mapToObj(value -> new UUID(0, value)).toList();
		var valid = new UUID(0, 101);
		when(decisions.dueRequestIds(start)).thenReturn(failing);
		when(decisions.dueRequestIds(failing.getLast())).thenReturn(List.of(valid));
		when(decisions.dueRequestIds(valid)).thenReturn(List.of());
		failing.forEach(id -> doThrow(new ApiException(HttpStatus.CONFLICT, "SERVICE_CREDIT_NOT_FOUND", "anomaly"))
				.when(decisions).expire(id));

		scheduler.expireDueRequests();

		var order = inOrder(decisions);
		order.verify(decisions).dueRequestIds(start);
		failing.forEach(id -> order.verify(decisions).expire(id));
		order.verify(decisions).dueRequestIds(failing.getLast());
		order.verify(decisions).expire(valid);
		order.verify(decisions).dueRequestIds(valid);
		order.verifyNoMoreInteractions();
	}
}
