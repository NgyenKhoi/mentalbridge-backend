package com.mentalbridge.consultation.appointment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.springframework.stereotype.Component;

@Component
public class ChatSessionCompletionPolicy {

	public static final String VERSION = "chat-session-completion-v1";
	public static final Duration GRACE = Duration.ofMinutes(5);
	public static final Duration RECONCILIATION = Duration.ofMinutes(35);
	private static final Duration COMPLETION_PRESENCE = Duration.ofMinutes(30);
	private static final Duration NO_SHOW_WITNESS_PRESENCE = Duration.ofMinutes(15);

	public Decision evaluate(Instant sessionStart, Instant sessionEnd, List<Evidence> evidence) {
		var user = participant("USER", sessionStart, sessionEnd, evidence);
		var specialist = participant("SPECIALIST", sessionStart, sessionEnd, evidence);
		var commonPresence = overlap(user.intervals(), specialist.intervals());
		if (user.checkedIn() && specialist.checkedIn() && user.hasMessage() && specialist.hasMessage()
				&& commonPresence.compareTo(COMPLETION_PRESENCE) >= 0) {
			return new Decision(ChatSessionOutcome.COMPLETED, "EVIDENCE_REQUIREMENTS_MET");
		}
		if (!user.anyEvidence() && specialist.checkedIn()
				&& specialist.presence().compareTo(NO_SHOW_WITNESS_PRESENCE) >= 0) {
			return new Decision(ChatSessionOutcome.USER_NO_SHOW, "USER_ZERO_EVIDENCE");
		}
		if (!specialist.anyEvidence() && user.checkedIn()
				&& user.presence().compareTo(NO_SHOW_WITNESS_PRESENCE) >= 0) {
			return new Decision(ChatSessionOutcome.SPECIALIST_NO_SHOW, "SPECIALIST_ZERO_EVIDENCE");
		}
		if (!user.anyEvidence() && !specialist.anyEvidence()) {
			return new Decision(ChatSessionOutcome.BOTH_NO_SHOW, "BOTH_ZERO_EVIDENCE");
		}
		return new Decision(ChatSessionOutcome.INSUFFICIENT_EVIDENCE, "COMPLETION_REQUIREMENTS_NOT_MET");
	}

	private Participant participant(String role, Instant sessionStart, Instant sessionEnd, List<Evidence> evidence) {
		var checkedIn = false;
		var hasMessage = false;
		var anyEvidence = false;
		var intervals = new ArrayList<Interval>();
		for (var fact : evidence) {
			if (!fact.role().equals(role)) continue;
			if (fact.type() == ChatEvidenceRequest.Type.CHECK_IN && fact.occurredAt().isBefore(sessionEnd)) {
				checkedIn = true;
				anyEvidence = true;
			}
			if (fact.type() == ChatEvidenceRequest.Type.ACCEPTED_MESSAGE
					&& !fact.occurredAt().isBefore(sessionStart) && fact.occurredAt().isBefore(sessionEnd)) {
				hasMessage = true;
				anyEvidence = true;
			}
			if (fact.type() == ChatEvidenceRequest.Type.PRESENCE_INTERVAL && fact.intervalStart() != null) {
				var start = fact.intervalStart().isBefore(sessionStart) ? sessionStart : fact.intervalStart();
				var end = fact.occurredAt().isAfter(sessionEnd) ? sessionEnd : fact.occurredAt();
				if (start.isBefore(end)) {
					intervals.add(new Interval(start, end));
					anyEvidence = true;
				}
			}
		}
		var merged = merge(intervals);
		var presence = merged.stream().map(value -> Duration.between(value.start(), value.end()))
				.reduce(Duration.ZERO, Duration::plus);
		return new Participant(checkedIn, hasMessage, anyEvidence, merged, presence);
	}

	private List<Interval> merge(List<Interval> values) {
		if (values.isEmpty()) return List.of();
		var sorted = values.stream().sorted(Comparator.comparing(Interval::start)).toList();
		var merged = new ArrayList<Interval>();
		var current = sorted.getFirst();
		for (var next : sorted.subList(1, sorted.size())) {
			if (!next.start().isAfter(current.end())) {
				current = new Interval(current.start(), next.end().isAfter(current.end()) ? next.end() : current.end());
			}
			else {
				merged.add(current);
				current = next;
			}
		}
		merged.add(current);
		return List.copyOf(merged);
	}

	private Duration overlap(List<Interval> left, List<Interval> right) {
		var total = Duration.ZERO;
		var leftIndex = 0;
		var rightIndex = 0;
		while (leftIndex < left.size() && rightIndex < right.size()) {
			var first = left.get(leftIndex);
			var second = right.get(rightIndex);
			var start = first.start().isAfter(second.start()) ? first.start() : second.start();
			var end = first.end().isBefore(second.end()) ? first.end() : second.end();
			if (start.isBefore(end)) total = total.plus(Duration.between(start, end));
			if (first.end().isBefore(second.end())) leftIndex++;
			else rightIndex++;
		}
		return total;
	}

	public record Evidence(String role, ChatEvidenceRequest.Type type, Instant intervalStart, Instant occurredAt) { }

	public record Decision(ChatSessionOutcome outcome, String reason) { }

	private record Interval(Instant start, Instant end) { }

	private record Participant(boolean checkedIn, boolean hasMessage, boolean anyEvidence,
			List<Interval> intervals, Duration presence) { }
}
