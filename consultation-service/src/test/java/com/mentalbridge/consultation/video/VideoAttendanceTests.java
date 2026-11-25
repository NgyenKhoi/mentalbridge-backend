package com.mentalbridge.consultation.video;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class VideoAttendanceTests {

	private final Instant start = Instant.parse("2026-10-12T10:00:00Z");

	@Test
	void missingPairsAndImpossibleOrderCannotVerify() {
		var joined = event("r", "u", "USER", "participant_joined", 10);
		assertThat(evaluate(List.of(joined))).isEqualTo(new VideoAttendance.Result(false, 0));
		assertThat(evaluate(List.of(joined, event("r", "u", "USER", "participant_left", 9))))
				.isEqualTo(new VideoAttendance.Result(false, 0));
	}

	@Test
	void differentRoomIncarnationsCannotCreateCommonPresence() {
		var events = new ArrayList<VideoEvent>();
		pair(events, "r1", "u", "USER", 0, 120);
		pair(events, "r2", "s", "SPECIALIST", 0, 120);
		assertThat(evaluate(events)).isEqualTo(new VideoAttendance.Result(true, 0));
	}

	@Test
	void reconnectIntervalsAreClippedUnionedAndNotDoubleCountedAcrossRooms() {
		var events = new ArrayList<VideoEvent>();
		pair(events, "r1", "u1", "USER", -10, 50);
		pair(events, "r1", "u2", "USER", 20, 80);
		pair(events, "r1", "s1", "SPECIALIST", 30, 100);
		pair(events, "r2", "u3", "USER", 30, 100);
		pair(events, "r2", "s2", "SPECIALIST", 30, 100);
		assertThat(VideoAttendance.evaluate(events, start, start.plusSeconds(90)))
				.isEqualTo(new VideoAttendance.Result(true, 60));
	}

	@Test
	void semanticDuplicatesDoNotInflateAttendanceAndConflictingJoinsFailClosed() {
		var events = new ArrayList<VideoEvent>();
		pair(events, "r", "u", "USER", 0, 60);
		pair(events, "r", "s", "SPECIALIST", 0, 60);
		events.add(event("r", "u", "USER", "participant_joined", 0));
		assertThat(evaluate(events)).isEqualTo(new VideoAttendance.Result(true, 60));
		events.add(event("r", "u", "USER", "participant_joined", 1));
		assertThat(evaluate(events)).isEqualTo(new VideoAttendance.Result(false, 0));
	}

	private VideoAttendance.Result evaluate(List<VideoEvent> events) {
		return VideoAttendance.evaluate(events, start, start.plusSeconds(3600));
	}

	private void pair(List<VideoEvent> events, String room, String sid, String role, long joined, long left) {
		events.add(event(room, sid, role, "participant_joined", joined));
		events.add(event(room, sid, role, "participant_left", left));
	}

	private VideoEvent event(String room, String sid, String role, String type, long seconds) {
		return new VideoEvent(UUID.randomUUID(), new VideoProvider.VerifiedVideoEvent(UUID.randomUUID().toString(),
				type, "opaque-room", room, role, sid, start.plusSeconds(seconds)), role, start.plusSeconds(3601));
	}
}
