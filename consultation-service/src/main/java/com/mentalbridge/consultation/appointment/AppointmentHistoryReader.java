package com.mentalbridge.consultation.appointment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;

final class AppointmentHistoryReader {

	private AppointmentHistoryReader() {
	}

	static AppointmentResponse attach(JdbcClient jdbc, AppointmentResponse appointment) {
		return attach(jdbc, List.of(appointment)).getFirst();
	}

	static List<AppointmentResponse> attach(JdbcClient jdbc, List<AppointmentResponse> appointments) {
		if (appointments.isEmpty()) return appointments;
		var histories = new LinkedHashMap<UUID, List<AppointmentResponse.HistoryEntry>>();
		appointments.forEach(appointment -> histories.put(appointment.id(), new ArrayList<>()));
		jdbc.sql("""
				select id, appointment_id, from_status, to_status, changed_by, reason,
				       credit_outcome, changed_at
				from (
				  select history.*,
				         row_number() over (
				           partition by appointment_id order by changed_at desc, id desc
				         ) as history_rank
				  from appointment_status_history history
				  where appointment_id in (:appointmentIds)
				) bounded_history
				where history_rank <= 20
				order by changed_at, id
				""").param("appointmentIds", histories.keySet()).query((row, ignored) -> {
			var appointmentId = row.getObject("appointment_id", UUID.class);
			var entry = new AppointmentResponse.HistoryEntry(row.getObject("id", UUID.class),
					row.getString("from_status"), row.getString("to_status"),
					AppointmentRowMapper.actorType(row.getString("reason")), row.getObject("changed_by", UUID.class),
					row.getString("reason"), row.getString("credit_outcome"),
					row.getTimestamp("changed_at").toInstant());
			histories.get(appointmentId).add(entry);
			return appointmentId;
		}).list();
		return appointments.stream().map(appointment -> appointment.withHistory(histories.get(appointment.id()))).toList();
	}
}
