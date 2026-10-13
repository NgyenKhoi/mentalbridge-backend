package com.mentalbridge.consultation.video;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

final class VideoAttendance {

	private VideoAttendance() { }

	static Result evaluate(List<VideoEvent> events, Instant start, Instant end) {
		var sessions = events.stream().filter(e -> !"room_finished".equals(e.type))
				.collect(Collectors.groupingBy(e -> new Session(e.roomSid, e.participantSid, e.identity)));
		var intervals = new ArrayList<Interval>();
		for (var session : sessions.values()) {
			var joins = times(session, "participant_joined");
			var leaves = times(session, "participant_left");
			if (joins.size() != 1 || leaves.size() != 1 || leaves.getFirst().isBefore(joins.getFirst())
					|| session.stream().map(e -> e.role).distinct().count() != 1) return new Result(false, 0);
			var joined = joins.getFirst().isBefore(start) ? start : joins.getFirst();
			var left = leaves.getFirst().isAfter(end) ? end : leaves.getFirst();
			if (left.isAfter(joined)) intervals.add(new Interval(session.getFirst().roomSid, session.getFirst().role, joined, left));
		}
		var common = new ArrayList<Interval>();
		for (var room : intervals.stream().collect(Collectors.groupingBy(Interval::roomSid)).values()) {
			var users = merge(room, "USER");
			var specialists = merge(room, "SPECIALIST");
			for (var user : users) for (var specialist : specialists) {
				var joined = user.start.isAfter(specialist.start) ? user.start : specialist.start;
				var left = user.end.isBefore(specialist.end) ? user.end : specialist.end;
				if (left.isAfter(joined)) common.add(new Interval("common", "COMMON", joined, left));
			}
		}
		var overlapMillis = merge(common, "COMMON").stream().mapToLong(i -> Duration.between(i.start, i.end).toMillis()).sum();
		return new Result(!sessions.isEmpty(), overlapMillis / 1000);
	}

	private static List<Instant> times(List<VideoEvent> session, String type) {
		return session.stream().filter(e -> type.equals(e.type)).map(e -> e.occurredAt).distinct().sorted().toList();
	}

	private static List<Interval> merge(List<Interval> intervals, String role) {
		var sorted = intervals.stream().filter(i -> role.equals(i.role)).sorted(Comparator.comparing(Interval::start)).toList();
		var merged = new ArrayList<Interval>();
		for (var next : sorted) {
			if (merged.isEmpty() || next.start.isAfter(merged.getLast().end)) merged.add(next);
			else {
				var previous = merged.removeLast();
				merged.add(new Interval(previous.roomSid, role, previous.start,
						previous.end.isAfter(next.end) ? previous.end : next.end));
			}
		}
		return merged;
	}

	record Result(boolean completePairs, long overlapSeconds) { }
	private record Session(String roomSid, String participantSid, String identity) { }
	private record Interval(String roomSid, String role, Instant start, Instant end) { }
}
