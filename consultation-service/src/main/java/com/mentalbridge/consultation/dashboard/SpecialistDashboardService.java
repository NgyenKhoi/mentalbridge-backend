package com.mentalbridge.consultation.dashboard;

import static com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.DataState.AVAILABLE;
import static com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.DataState.BLOCKED;
import static com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.DataState.EMPTY;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.ActionItem;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.ActionType;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.AppointmentCollection;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.AppointmentItem;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.AvailabilityCollection;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.AvailabilityItem;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.DataState;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.NextAppointment;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.OperationalStatus;
import com.mentalbridge.consultation.dashboard.SpecialistDashboardResponse.Profile;

@Service
public class SpecialistDashboardService {

	private static final String SOURCE = "CONSULTATION";
	private static final int ITEM_LIMIT = 5;

	private final JdbcClient jdbc;
	private final Clock clock;

	public SpecialistDashboardService(JdbcClient jdbc, Clock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
	public SpecialistDashboardResponse dashboard(UUID specialistId) {
		var now = clock.instant();
		var profile = profile(specialistId);
		if (profile == null) return blocked(now, null, OperationalStatus.PROFILE_REQUIRED,
				ActionType.COMPLETE_PROFILE);

		var operationalStatus = operationalStatus(profile.approvalStatus());
		if (operationalStatus != OperationalStatus.READY) {
			return blocked(now, profile, operationalStatus, blockedAction(operationalStatus));
		}

		var zone = ZoneId.of(profile.timezone());
		var localDate = LocalDate.ofInstant(now, zone);
		var startOfDay = localDate.atStartOfDay(zone).toInstant();
		var endOfDay = localDate.plusDays(1).atStartOfDay(zone).toInstant();
		var today = appointments(specialistId, now, """
				a.status in ('CONFIRMED', 'IN_PROGRESS')
				and a.scheduled_start_at >= :windowStart and a.scheduled_start_at < :windowEnd
				""", startOfDay, endOfDay, "a.scheduled_start_at, a.id");
		var pending = appointments(specialistId, now, """
				a.status = 'REQUESTED' and a.decision_deadline_at > :now
				""", null, null, "a.decision_deadline_at, a.scheduled_start_at, a.id");
		var next = nextAppointment(specialistId, now);
		var availability = availability(specialistId, now);
		var actions = new ArrayList<ActionItem>();
		if (pending.count() > 0) {
			actions.add(new ActionItem(SOURCE, now, ActionType.REVIEW_APPOINTMENT_REQUESTS, pending.count()));
		}
		if (availability.count() == 0) {
			actions.add(new ActionItem(SOURCE, now, ActionType.PUBLISH_AVAILABILITY, 1));
		}

		return new SpecialistDashboardResponse(SOURCE, now, OperationalStatus.READY,
				new Profile(SOURCE, now, AVAILABLE, profile.displayName(), profile.timezone(), profile.approvalStatus()),
				new AppointmentCollection(SOURCE, now, state(today.items()), today.count(), localDate,
						profile.timezone(), today.items()),
				new AppointmentCollection(SOURCE, now, state(pending.items()), pending.count(), null,
						profile.timezone(), pending.items()),
				next, availability, List.copyOf(actions));
	}

	private SpecialistDashboardResponse blocked(Instant now, ProfileRow profile,
			OperationalStatus status, ActionType action) {
		var profileState = profile == null ? EMPTY : AVAILABLE;
		var profileProjection = new Profile(SOURCE, now, profileState,
				profile == null ? null : profile.displayName(), profile == null ? null : profile.timezone(),
				profile == null ? null : profile.approvalStatus());
		var appointments = new AppointmentCollection(SOURCE, now, BLOCKED, 0, null,
				profile == null ? null : profile.timezone(), List.of());
		return new SpecialistDashboardResponse(SOURCE, now, status, profileProjection, appointments,
				appointments, new NextAppointment(SOURCE, now, BLOCKED, null),
				new AvailabilityCollection(SOURCE, now, BLOCKED, 0, List.of()),
				List.of(new ActionItem(SOURCE, now, action, 1)));
	}

	private ProfileRow profile(UUID specialistId) {
		return jdbc.sql("""
				select display_name, timezone, approval_status
				from specialist_profile where account_id=:specialistId
				""").param("specialistId", specialistId)
				.query((row, ignored) -> new ProfileRow(row.getString("display_name"), row.getString("timezone"),
						row.getString("approval_status")))
				.optional().orElse(null);
	}

	private AppointmentRows appointments(UUID specialistId, Instant now, String predicate,
			Instant windowStart, Instant windowEnd, String orderBy) {
		var base = """
				from appointment a
				where a.specialist_account_id=:specialistId and %s
				""".formatted(predicate);
		var countQuery = jdbc.sql("select count(*) " + base)
				.param("specialistId", specialistId);
		var itemsQuery = jdbc.sql("""
				select a.id, a.status, a.modality, a.scheduled_start_at, a.scheduled_end_at,
				       a.display_timezone, a.decision_deadline_at
				%s order by %s limit :limit
				""".formatted(base, orderBy))
				.param("specialistId", specialistId).param("limit", ITEM_LIMIT);
		if (predicate.contains(":now")) {
			countQuery.param("now", database(now));
			itemsQuery.param("now", database(now));
		}
		if (windowStart != null) {
			countQuery.param("windowStart", database(windowStart)).param("windowEnd", database(windowEnd));
			itemsQuery.param("windowStart", database(windowStart)).param("windowEnd", database(windowEnd));
		}
		var count = Math.toIntExact(countQuery.query(Long.class).single());
		var items = itemsQuery.query((row, ignored) -> appointment(row, now)).list();
		return new AppointmentRows(count, items);
	}

	private NextAppointment nextAppointment(UUID specialistId, Instant now) {
		var item = jdbc.sql("""
				select a.id, a.status, a.modality, a.scheduled_start_at, a.scheduled_end_at,
				       a.display_timezone, a.decision_deadline_at
				from appointment a
				where a.specialist_account_id=:specialistId
				  and a.status in ('CONFIRMED', 'IN_PROGRESS') and a.scheduled_end_at > :now
				order by a.scheduled_start_at, a.id limit 1
				""").param("specialistId", specialistId).param("now", database(now))
				.query((row, ignored) -> appointment(row, now)).optional().orElse(null);
		return new NextAppointment(SOURCE, now, item == null ? EMPTY : AVAILABLE, item);
	}

	private AvailabilityCollection availability(UUID specialistId, Instant now) {
		var predicate = """
				s.specialist_account_id=:specialistId and s.status='ACTIVE' and s.start_at > :now
				and not exists (
				  select 1 from appointment a where a.availability_slot_id=s.id
				    and a.status in ('REQUESTED', 'CONFIRMED', 'IN_PROGRESS')
				)
				""";
		var count = Math.toIntExact(jdbc.sql("select count(*) from availability_slot s where " + predicate)
				.param("specialistId", specialistId).param("now", database(now)).query(Long.class).single());
		var items = jdbc.sql("""
				select s.id, s.modality, s.start_at, s.end_at, s.timezone
				from availability_slot s where %s order by s.start_at, s.id limit :limit
				""".formatted(predicate))
				.param("specialistId", specialistId).param("now", database(now)).param("limit", ITEM_LIMIT)
				.query((row, ignored) -> new AvailabilityItem(SOURCE, now, row.getObject("id", UUID.class),
						row.getString("modality"), instant(row, "start_at"), instant(row, "end_at"),
						row.getString("timezone"))).list();
		return new AvailabilityCollection(SOURCE, now, state(items), count, items);
	}

	private AppointmentItem appointment(ResultSet row, Instant asOf) throws SQLException {
		return new AppointmentItem(SOURCE, asOf, row.getObject("id", UUID.class), row.getString("status"),
				row.getString("modality"), instant(row, "scheduled_start_at"), instant(row, "scheduled_end_at"),
				row.getString("display_timezone"), instant(row, "decision_deadline_at"));
	}

	private Instant instant(ResultSet row, String column) throws SQLException {
		var timestamp = row.getTimestamp(column);
		return timestamp == null ? null : timestamp.toInstant();
	}

	private DataState state(List<?> items) {
		return items.isEmpty() ? EMPTY : AVAILABLE;
	}

	private OperationalStatus operationalStatus(String approvalStatus) {
		return switch (approvalStatus) {
			case "APPROVED" -> OperationalStatus.READY;
			case "PENDING" -> OperationalStatus.PENDING_APPROVAL;
			case "REJECTED" -> OperationalStatus.PROFILE_REJECTED;
			case "SUSPENDED" -> OperationalStatus.SUSPENDED;
			default -> OperationalStatus.SUSPENDED;
		};
	}

	private ActionType blockedAction(OperationalStatus status) {
		return switch (status) {
			case PROFILE_REQUIRED -> ActionType.COMPLETE_PROFILE;
			case PENDING_APPROVAL -> ActionType.AWAIT_PROFILE_APPROVAL;
			case PROFILE_REJECTED -> ActionType.UPDATE_REJECTED_PROFILE;
			case SUSPENDED, READY -> ActionType.CONTACT_SUPPORT;
		};
	}

	private OffsetDateTime database(Instant instant) {
		return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
	}

	private record ProfileRow(String displayName, String timezone, String approvalStatus) {
	}

	private record AppointmentRows(int count, List<AppointmentItem> items) {
	}
}
