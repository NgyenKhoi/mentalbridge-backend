package com.mentalbridge.consultation.video;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.mentalbridge.consultation.shared.ApiException;

class VideoSessionSchedulerTests {

	private final VideoSessionTransactions transactions = mock(VideoSessionTransactions.class);
	private final VideoProvider provider = mock(VideoProvider.class);
	private final VideoSessionService sessions = new VideoSessionService(transactions, provider);

	@Test
	void failedEndAndClosureItemsDoNotStarveLaterItemsOrTheOtherLoopAndLogOnlySafeIdentifiers() {
		var badEnd = UUID.randomUUID();
		var goodEnd = UUID.randomUUID();
		var badClose = UUID.randomUUID();
		var goodClose = UUID.randomUUID();
		var privateDetail = "user-identity-secret account 0d7281d1 provider payload";
		when(transactions.dueEndIds()).thenReturn(List.of(badEnd, goodEnd));
		doThrow(new DataIntegrityViolationException(privateDetail)).when(transactions).end(badEnd);
		when(transactions.dueCloseIds()).thenReturn(List.of(badClose, goodClose));
		when(transactions.claimClosure(badClose))
				.thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "VIDEO_PROVIDER_UNAVAILABLE", privateDetail));
		when(transactions.claimClosure(goodClose)).thenReturn(new VideoSessionTransactions.Closure(goodClose, "room-good",
				List.of("user-good", "specialist-good"), Instant.parse("2026-11-05T00:00:00Z")));

		var logger = (Logger) LoggerFactory.getLogger(VideoSessionService.class);
		var events = new ListAppender<ILoggingEvent>();
		events.start();
		logger.addAppender(events);
		try {
			sessions.closeDueSessions();
		}
		finally {
			logger.detachAppender(events);
		}

		verify(transactions).end(goodEnd);
		verify(provider).closeRoom(any(), any(), any());
		verify(transactions).closureResult(goodClose, true);
		assertThat(events.list).extracting(ILoggingEvent::getFormattedMessage).containsExactly(
				"Video session end failed for appointmentId=" + badEnd + " error=DataIntegrityViolationException",
				"Video room close failed for appointmentId=" + badClose + " error=VIDEO_PROVIDER_UNAVAILABLE");
		assertThat(events.list).allSatisfy(event -> assertThat(event.getThrowableProxy()).isNull());
	}
}
