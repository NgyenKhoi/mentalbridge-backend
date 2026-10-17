package com.mentalbridge.consultation.dispute;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppointmentSettlementGate {

	private final JdbcClient jdbc;

	public AppointmentSettlementGate(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	public boolean earningEligible(UUID appointmentId) {
		return jdbc.sql("""
				select count(*)=1 from appointment a
				where a.id=:appointmentId and a.status='COMPLETED'
				  and a.session_outcome='COMPLETED' and a.session_settled_at is not null
				  and not exists (
				    select 1 from appointment_dispute d
				    where d.appointment_id=a.id
				      and (d.status='OPEN' or d.resolution_outcome='RELEASE_USER_CREDIT')
				  )
				""").param("appointmentId", appointmentId).query(Boolean.class).single();
	}
}
