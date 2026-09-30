package com.mentalbridge.care.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.mentalbridge.care.analytics.ActivityDashboardContract.Bounds;
import com.mentalbridge.care.analytics.ActivityDashboardContract.DailyBucket;
import com.mentalbridge.care.analytics.ActivityDashboardContract.Response;
import com.mentalbridge.care.analytics.ActivityDashboardContract.Summary;
import com.mentalbridge.care.assessment.AssessmentService;
import com.mentalbridge.care.shared.ApiException;
import com.mentalbridge.care.supportplan.SupportPlanActivityOccurrenceService;

@Service
public class ActivityDashboardService {

	static final int ASSESSMENT_LIMIT = 50;
	static final int JOURNAL_LIMIT = 50;
	static final int EMOTION_LIMIT = 90;
	static final int APPOINTMENT_LIMIT = 100;
	private static final Logger LOGGER = LoggerFactory.getLogger(ActivityDashboardService.class);

	private final AssessmentService assessments;
	private final SupportPlanActivityOccurrenceService occurrences;
	private final JournalActivityHttpClient journal;
	private final ConsultationActivityHttpClient consultation;
	private final Clock clock;

	public ActivityDashboardService(AssessmentService assessments, SupportPlanActivityOccurrenceService occurrences,
			JournalActivityHttpClient journal, ConsultationActivityHttpClient consultation, Clock clock) {
		this.assessments = assessments;
		this.occurrences = occurrences;
		this.journal = journal;
		this.consultation = consultation;
		this.clock = clock;
	}

	public Response dashboard(UUID userId, String bearerToken, ZoneId timezone, int windowDays, UUID correlationId) {
		if (userId == null || bearerToken == null || bearerToken.isBlank() || timezone == null || correlationId == null) {
			throw new IllegalArgumentException("Authenticated activity dashboard context is required");
		}
		if (windowDays != 7 && windowDays != 30 && windowDays != 90) {
			throw new IllegalArgumentException("Activity dashboard range must be 7, 30, or 90 days");
		}

		LocalDate asOf = LocalDate.now(clock.withZone(timezone));
		LocalDate start = asOf.minusDays(windowDays - 1L);
		Map<String, String> sources = initialSources();
		List<Fact> facts = new ArrayList<>();

		var journals = CompletableFuture.supplyAsync(() -> loadJournals(bearerToken, correlationId));
		var emotions = CompletableFuture.supplyAsync(() -> loadEmotions(bearerToken, correlationId));
		var appointments = CompletableFuture.supplyAsync(() -> loadAppointments(bearerToken, correlationId));

		loadAssessments(userId, facts, sources, correlationId);
		loadSupportPlan(userId, start, asOf, facts, sources, correlationId);
		merge("journals", journals.join(), facts, sources, correlationId);
		merge("emotions", emotions.join(), facts, sources, correlationId);
		merge("appointments", appointments.join(), facts, sources, correlationId);

		Map<LocalDate, MutableBucket> buckets = new LinkedHashMap<>();
		List<Fact> includedFacts = new ArrayList<>();
		for (int offset = 0; offset < windowDays; offset++) {
			LocalDate day = start.plusDays(offset);
			buckets.put(day, new MutableBucket(day));
		}
		for (Fact fact : facts) {
			LocalDate day = fact.occurredAt().atZone(timezone).toLocalDate();
			MutableBucket bucket = buckets.get(day);
			if (bucket != null) {
				bucket.add(fact);
				includedFacts.add(fact);
			}
		}

		List<DailyBucket> daily = buckets.values().stream().map(MutableBucket::view).toList();
		Summary summary = summarize(daily, includedFacts);
		return new Response(asOf, start, timezone.getId(), summary, daily, Map.copyOf(sources),
				sources.containsValue("unavailable"),
				new Bounds(windowDays, ASSESSMENT_LIMIT, JOURNAL_LIMIT, EMOTION_LIMIT, APPOINTMENT_LIMIT));
	}

	private void loadAssessments(UUID userId, List<Fact> facts, Map<String, String> sources, UUID correlationId) {
		try {
			var page = assessments.history(userId, null, ASSESSMENT_LIMIT);
			for (var assessment : page.items()) {
				if (assessment.assessmentId() == null || assessment.submittedAt() == null) throw malformed("assessments");
				facts.add(new Fact(Metric.ASSESSMENT, assessment.submittedAt(), assessment.instrument(), null));
			}
			sources.put("assessments", state(page.items().size(), page.hasMore()));
		}
		catch (RuntimeException exception) {
			unavailable("assessments", correlationId, exception, sources);
		}
	}

	private void loadSupportPlan(UUID userId, LocalDate start, LocalDate asOf, List<Fact> facts,
			Map<String, String> sources, UUID correlationId) {
		try {
			var page = occurrences.list(userId, start, asOf);
			int count = 0;
			for (var occurrence : page.occurrences()) {
				if ("COMPLETED".equals(occurrence.state()) && occurrence.completedAt() != null) {
					facts.add(new Fact(Metric.SUPPORT_COMPLETED, occurrence.completedAt(), null, null));
					count++;
				}
				else if ("SKIPPED".equals(occurrence.state()) && occurrence.skippedAt() != null) {
					facts.add(new Fact(Metric.SUPPORT_SKIPPED, occurrence.skippedAt(), null, null));
					count++;
				}
			}
			sources.put("supportPlans", state(count, false));
		}
		catch (ApiException exception) {
			if (exception.status() == HttpStatus.NOT_FOUND) sources.put("supportPlans", "empty");
			else unavailable("supportPlans", correlationId, exception, sources);
		}
		catch (RuntimeException exception) {
			unavailable("supportPlans", correlationId, exception, sources);
		}
	}

	private SourceResult loadJournals(String bearerToken, UUID correlationId) {
		try {
			var page = journal.journals("Bearer " + bearerToken, correlationId.toString(), JOURNAL_LIMIT);
			if (page == null || page.items() == null || page.page() == null) throw malformed("journals");
			List<Fact> facts = page.items().stream().map(value -> {
				if (value == null || value.id() == null || value.occurredAt() == null) throw malformed("journals");
				return new Fact(Metric.JOURNAL, value.occurredAt(), null, null);
			}).toList();
			return new SourceResult(facts, state(facts.size(), page.page().hasMore()), null);
		}
		catch (RuntimeException exception) {
			return failed(exception);
		}
	}

	private SourceResult loadEmotions(String bearerToken, UUID correlationId) {
		try {
			var page = journal.emotions("Bearer " + bearerToken, correlationId.toString(), EMOTION_LIMIT);
			if (page == null || page.items() == null || page.page() == null) throw malformed("emotions");
			List<Fact> facts = page.items().stream().map(value -> {
				if (value == null || value.id() == null || value.emotion() == null || value.recordedAt() == null) {
					throw malformed("emotions");
				}
				return new Fact(Metric.EMOTION, value.recordedAt(), null, emotionLevel(value.emotion()));
			}).toList();
			return new SourceResult(facts, state(facts.size(), page.page().hasMore()), null);
		}
		catch (RuntimeException exception) {
			return failed(exception);
		}
	}

	private SourceResult loadAppointments(String bearerToken, UUID correlationId) {
		try {
			var page = consultation.appointments("Bearer " + bearerToken, correlationId.toString());
			if (page == null || page.items() == null || page.count() != page.items().size()) throw malformed("appointments");
			List<Fact> facts = new ArrayList<>();
			for (var appointment : page.items()) {
				if (appointment == null || appointment.history() == null) throw malformed("appointments");
				for (var event : appointment.history()) {
					if (event == null || event.eventId() == null || event.reason() == null || event.occurredAt() == null) {
						throw malformed("appointments");
					}
					if ("APPOINTMENT_REQUESTED".equals(event.reason())) {
						facts.add(new Fact(Metric.APPOINTMENT, event.occurredAt(), null, null));
					}
				}
			}
			return new SourceResult(List.copyOf(facts), state(facts.size(), page.count() >= APPOINTMENT_LIMIT), null);
		}
		catch (RuntimeException exception) {
			return failed(exception);
		}
	}

	private void merge(String source, SourceResult result, List<Fact> facts, Map<String, String> sources,
			UUID correlationId) {
		facts.addAll(result.facts());
		sources.put(source, result.state());
		if (result.failure() != null) unavailable(source, correlationId, result.failure(), null);
	}

	private Summary summarize(List<DailyBucket> daily, List<Fact> includedFacts) {
		int assessments = daily.stream().mapToInt(DailyBucket::assessments).sum();
		int journals = daily.stream().mapToInt(DailyBucket::journals).sum();
		int emotions = daily.stream().mapToInt(DailyBucket::emotions).sum();
		int supportCompleted = daily.stream().mapToInt(DailyBucket::supportCompleted).sum();
		int supportSkipped = daily.stream().mapToInt(DailyBucket::supportSkipped).sum();
		int appointments = daily.stream().mapToInt(DailyBucket::appointments).sum();
		int total = daily.stream().mapToInt(DailyBucket::total).sum();
		int activeDays = (int) daily.stream().filter(bucket -> bucket.total() > 0).count();
		int journalActiveDays = (int) daily.stream().filter(bucket -> bucket.journals() > 0).count();
		int emotionActiveDays = (int) daily.stream().filter(bucket -> bucket.emotions() > 0).count();
		int currentStreak = 0;
		for (int index = daily.size() - 1; index >= 0 && daily.get(index).emotions() > 0; index--) currentStreak++;
		Fact latestAssessment = includedFacts.stream().filter(fact -> fact.metric() == Metric.ASSESSMENT)
				.max(java.util.Comparator.comparing(Fact::occurredAt)).orElse(null);
		return new Summary(total, activeDays, assessments, journals, journalActiveDays, emotions, emotionActiveDays,
				supportCompleted, supportSkipped, appointments, currentStreak,
				latestAssessment == null ? null : latestAssessment.detail(),
				latestAssessment == null ? null : latestAssessment.occurredAt());
	}

	private int emotionLevel(String emotion) {
		return switch (emotion) {
			case "GREAT" -> 5;
			case "GOOD" -> 4;
			case "OKAY" -> 3;
			case "LOW" -> 2;
			case "VERY_LOW" -> 1;
			default -> throw malformed("emotions");
		};
	}

	private SourceResult failed(RuntimeException exception) {
		return new SourceResult(List.of(), "unavailable", exception);
	}

	private String state(int count, boolean limited) {
		if (limited) return "limited";
		return count == 0 ? "empty" : "available";
	}

	private Map<String, String> initialSources() {
		Map<String, String> sources = new LinkedHashMap<>();
		for (String source : List.of("assessments", "journals", "emotions", "supportPlans", "appointments")) {
			sources.put(source, "unavailable");
		}
		return sources;
	}

	private void unavailable(String source, UUID correlationId, RuntimeException exception, Map<String, String> sources) {
		if (sources != null) sources.put(source, "unavailable");
		LOGGER.warn("activity_dashboard_source_unavailable source={} correlationId={} category={}", source,
				correlationId, exception.getClass().getSimpleName());
	}

	private IllegalStateException malformed(String source) {
		return new IllegalStateException("Malformed " + source + " activity response");
	}

	private enum Metric { ASSESSMENT, JOURNAL, EMOTION, SUPPORT_COMPLETED, SUPPORT_SKIPPED, APPOINTMENT }
	private record Fact(Metric metric, Instant occurredAt, String detail, Integer emotionLevel) { }
	private record SourceResult(List<Fact> facts, String state, RuntimeException failure) { }

	private static final class MutableBucket {
		private final LocalDate localDate;
		private int assessments;
		private int journals;
		private int emotions;
		private Integer emotionLevel;
		private Instant latestEmotionAt;
		private int supportCompleted;
		private int supportSkipped;
		private int appointments;

		private MutableBucket(LocalDate localDate) {
			this.localDate = localDate;
		}

		private void add(Fact fact) {
			switch (fact.metric()) {
				case ASSESSMENT -> assessments++;
				case JOURNAL -> journals++;
				case EMOTION -> {
					emotions++;
					if (latestEmotionAt == null || fact.occurredAt().isAfter(latestEmotionAt)) {
						latestEmotionAt = fact.occurredAt();
						emotionLevel = fact.emotionLevel();
					}
				}
				case SUPPORT_COMPLETED -> supportCompleted++;
				case SUPPORT_SKIPPED -> supportSkipped++;
				case APPOINTMENT -> appointments++;
			}
		}

		private DailyBucket view() {
			int total = assessments + journals + emotions + supportCompleted + supportSkipped + appointments;
			return new DailyBucket(localDate, assessments, journals, emotions, emotionLevel, supportCompleted, supportSkipped,
					appointments, total);
		}
	}
}
