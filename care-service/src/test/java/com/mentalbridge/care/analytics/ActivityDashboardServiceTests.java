package com.mentalbridge.care.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.mentalbridge.care.assessment.AssessmentService;
import com.mentalbridge.care.assessment.AssessmentService.AssessmentView;
import com.mentalbridge.care.assessment.AssessmentService.HistoryPage;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.OccurrenceListView;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService.OccurrenceView;

class ActivityDashboardServiceTests {

	private final AssessmentService assessments = mock(AssessmentService.class);
	private final SupportPlanActivityOccurrenceService occurrences = mock(SupportPlanActivityOccurrenceService.class);
	private final JournalActivityHttpClient journal = mock(JournalActivityHttpClient.class);
	private final ConsultationActivityHttpClient consultation = mock(ConsultationActivityHttpClient.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"), ZoneOffset.UTC);
	private final UUID userId = UUID.randomUUID();
	private final UUID correlationId = UUID.randomUUID();
	private ActivityDashboardService service;

	@BeforeEach
	void setUp() {
		service = new ActivityDashboardService(assessments, occurrences, journal, consultation, clock);
		when(assessments.history(any(), isNull(), anyInt())).thenReturn(new HistoryPage(List.of(), null, false));
		when(occurrences.list(any(), any(), any())).thenReturn(
				new OccurrenceListView(UUID.randomUUID(), "ACTIVE", "v1", LocalDate.parse("2026-09-01"),
						LocalDate.parse("2026-09-30"), List.of(), "FACTUAL"));
		when(journal.journals(any(), any(), anyInt())).thenReturn(
				new JournalActivityHttpClient.JournalPage(List.of(), new JournalActivityHttpClient.Page(50, false)));
		when(journal.emotions(any(), any(), anyInt())).thenReturn(
				new JournalActivityHttpClient.EmotionPage(List.of(), new JournalActivityHttpClient.Page(90, false)));
		when(consultation.appointments(any(), any())).thenReturn(
				new ConsultationActivityHttpClient.AppointmentPage(List.of(), 0));
	}

	@Test
	void aggregatesFactsIntoThirtyLocalDateBucketsWithoutRecordPayloads() {
		AssessmentView assessment = new AssessmentView(UUID.randomUUID(), UUID.randomUUID(), "PHQ9", "v1", "privacy",
				Instant.parse("2026-09-29T20:00:00Z"), null, null, null);
		when(assessments.history(userId, null, 50)).thenReturn(new HistoryPage(List.of(assessment), null, false));

		OccurrenceView completed = mock(OccurrenceView.class);
		when(completed.state()).thenReturn("COMPLETED");
		when(completed.completedAt()).thenReturn(Instant.parse("2026-09-28T03:00:00Z"));
		when(occurrences.list(userId, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30")))
				.thenReturn(new OccurrenceListView(UUID.randomUUID(), "ACTIVE", "v1",
						LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"), List.of(completed), "FACTUAL"));

		when(journal.journals(any(), any(), anyInt())).thenReturn(new JournalActivityHttpClient.JournalPage(
				List.of(new JournalActivityHttpClient.JournalItem(UUID.randomUUID(),
						Instant.parse("2026-09-29T03:00:00Z"))), new JournalActivityHttpClient.Page(50, false)));
		when(journal.emotions(any(), any(), anyInt())).thenReturn(new JournalActivityHttpClient.EmotionPage(
				List.of(new JournalActivityHttpClient.EmotionItem(UUID.randomUUID(), "GOOD",
						Instant.parse("2026-09-30T03:00:00Z"))), new JournalActivityHttpClient.Page(90, false)));
		when(consultation.appointments(any(), any())).thenReturn(new ConsultationActivityHttpClient.AppointmentPage(
				List.of(new ConsultationActivityHttpClient.Appointment(List.of(
						new ConsultationActivityHttpClient.HistoryEntry(UUID.randomUUID(), "APPOINTMENT_REQUESTED",
								Instant.parse("2026-09-27T03:00:00Z"))))), 1));

		var result = service.dashboard(userId, "owner-token", ZoneId.of("Asia/Bangkok"), 30, correlationId);

		assertThat(result.daily()).hasSize(30);
		assertThat(result.startLocalDate()).isEqualTo(LocalDate.parse("2026-09-01"));
		assertThat(result.asOfLocalDate()).isEqualTo(LocalDate.parse("2026-09-30"));
		assertThat(result.summary().totalActivities()).isEqualTo(5);
		assertThat(result.summary().activeDays()).isEqualTo(4);
		assertThat(result.summary().assessmentSubmissions()).isEqualTo(1);
		assertThat(result.summary().journalEntries()).isEqualTo(1);
		assertThat(result.summary().journalActiveDays()).isEqualTo(1);
		assertThat(result.summary().emotionCheckIns()).isEqualTo(1);
		assertThat(result.summary().supportCompleted()).isEqualTo(1);
		assertThat(result.summary().appointmentEvents()).isEqualTo(1);
		assertThat(result.summary().currentEmotionStreak()).isEqualTo(1);
		assertThat(result.summary().latestAssessmentInstrument()).isEqualTo("PHQ9");
		assertThat(result.daily()).filteredOn(day -> day.localDate().equals(LocalDate.parse("2026-09-30")))
				.singleElement().satisfies(day -> {
					assertThat(day.assessments()).isEqualTo(1);
					assertThat(day.emotions()).isEqualTo(1);
					assertThat(day.emotionLevel()).isEqualTo(4);
					assertThat(day.total()).isEqualTo(2);
				});
		assertThat(result.sources()).doesNotContainValue("unavailable");
	}

	@Test
	void keepsDependencyFailuresSourceSpecificAndMissingPlanEmpty() {
		when(journal.journals(any(), any(), anyInt())).thenThrow(new IllegalStateException("private failure"));
		when(occurrences.list(any(), any(), any())).thenThrow(
				new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_PLAN_CURRENT_NOT_FOUND", "No current plan"));

		var result = service.dashboard(userId, "owner-token", ZoneId.of("Asia/Bangkok"), 30, correlationId);

		assertThat(result.partial()).isTrue();
		assertThat(result.sources()).containsEntry("journals", "unavailable")
				.containsEntry("supportPlans", "empty")
				.containsEntry("emotions", "empty")
				.containsEntry("appointments", "empty");
		assertThat(result.summary().totalActivities()).isZero();
	}

	@Test
	void appliesTheSelectedRangeToEveryAggregate() {
		AssessmentView recent = new AssessmentView(UUID.randomUUID(), UUID.randomUUID(), "GAD7", "v1", "privacy",
				Instant.parse("2026-09-29T03:00:00Z"), null, null, null);
		AssessmentView old = new AssessmentView(UUID.randomUUID(), UUID.randomUUID(), "PHQ9", "v1", "privacy",
				Instant.parse("2026-09-20T03:00:00Z"), null, null, null);
		when(assessments.history(userId, null, 50)).thenReturn(new HistoryPage(List.of(recent, old), null, false));

		var result = service.dashboard(userId, "owner-token", ZoneId.of("Asia/Bangkok"), 7, correlationId);

		assertThat(result.daily()).hasSize(7);
		assertThat(result.startLocalDate()).isEqualTo(LocalDate.parse("2026-09-24"));
		assertThat(result.summary().assessmentSubmissions()).isEqualTo(1);
		assertThat(result.summary().latestAssessmentInstrument()).isEqualTo("GAD7");
		assertThat(result.bounded().windowDays()).isEqualTo(7);
	}
}
