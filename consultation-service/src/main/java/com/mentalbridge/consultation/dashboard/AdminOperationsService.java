package com.mentalbridge.consultation.dashboard;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminOperationsService {

	private static final String SOURCE = "CONSULTATION";

	private final JdbcClient jdbc;
	private final Clock clock;

	public AdminOperationsService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public AdminOperationsResponse getOperationsSummary() {
		var now = clock.instant();

		Map<String, Long> specialistCounts = new HashMap<>();
		jdbc.sql("select approval_status, count(*) as cnt from specialist_profile group by approval_status")
				.query((row, ignored) -> {
					specialistCounts.put(row.getString("approval_status"), row.getLong("cnt"));
					return null;
				})
				.list();

		long pendingReview = specialistCounts.getOrDefault("PENDING", 0L);
		long active = specialistCounts.getOrDefault("APPROVED", 0L);
		long rejected = specialistCounts.getOrDefault("REJECTED", 0L);
		long suspended = specialistCounts.getOrDefault("SUSPENDED", 0L);
		long totalSpecialists = pendingReview + active + rejected + suspended;

		var specialistSummary = new AdminOperationsResponse.AdminSpecialistOperationsSummary(
				totalSpecialists, pendingReview, active, rejected, suspended);

		long totalAppointments = jdbc.sql("select count(*) from appointment").query(Long.class).single();

		Map<String, Long> appointmentCounts = new HashMap<>();
		jdbc.sql("select status, count(*) as cnt from appointment group by status")
				.query((row, ignored) -> {
					appointmentCounts.put(row.getString("status"), row.getLong("cnt"));
					return null;
				})
				.list();

		long requested = appointmentCounts.getOrDefault("REQUESTED", 0L);
		long confirmed = appointmentCounts.getOrDefault("CONFIRMED", 0L);
		long inProgress = appointmentCounts.getOrDefault("IN_PROGRESS", 0L);
		long sessionEnded = appointmentCounts.getOrDefault("SESSION_ENDED", 0L);
		long completed = appointmentCounts.getOrDefault("COMPLETED", 0L);
		long cancelled = appointmentCounts.getOrDefault("CANCELLED", 0L);
		long rejectedAppt = appointmentCounts.getOrDefault("REJECTED", 0L);
		long expired = appointmentCounts.getOrDefault("EXPIRED", 0L);

		Map<String, Long> outcomeCounts = new HashMap<>();
		jdbc.sql("select session_outcome, count(*) as cnt from appointment where session_outcome is not null group by session_outcome")
				.query((row, ignored) -> {
					outcomeCounts.put(row.getString("session_outcome"), row.getLong("cnt"));
					return null;
				})
				.list();

		long userNoShow = outcomeCounts.getOrDefault("USER_NO_SHOW", 0L);
		long specialistNoShow = outcomeCounts.getOrDefault("SPECIALIST_NO_SHOW", 0L);
		long bothNoShow = outcomeCounts.getOrDefault("BOTH_NO_SHOW", 0L);

		var appointmentSummary = new AdminOperationsResponse.AdminAppointmentOperationsSummary(
				totalAppointments, requested, confirmed, inProgress, sessionEnded, completed, cancelled, rejectedAppt, expired,
				userNoShow, specialistNoShow, bothNoShow);

		return new AdminOperationsResponse(SOURCE, now, specialistSummary, appointmentSummary);
	}
}
