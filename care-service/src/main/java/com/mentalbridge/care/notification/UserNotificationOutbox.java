package com.mentalbridge.care.notification;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class UserNotificationOutbox {

	public enum FactType {
		ASSESSMENT_RESULT_AVAILABLE,
		SUPPORT_GUIDE_AVAILABLE
	}

	private final JdbcTemplate jdbc;
	private final ObjectMapper json;

	public UserNotificationOutbox(JdbcTemplate jdbc, ObjectMapper json) {
		this.jdbc = jdbc;
		this.json = json;
	}

	public void append(UUID recipientId, FactType factType, UUID targetId, long targetVersion,
			UUID correlationId, Instant availableAt) {
		try {
			var payload = json.writeValueAsString(Map.of(
					"recipientId", recipientId.toString(),
					"factType", factType.name(),
					"targetId", targetId.toString(),
					"targetVersion", targetVersion,
					"availableAt", availableAt.toString()));
			jdbc.update("""
					insert into outbox_event
						(id,message_type,schema_version,aggregate_type,aggregate_id,aggregate_version,
						 correlation_id,payload,occurred_at,attempt_count,created_at)
					values (?, 'care.user-notification.fact', '1.0', ?, ?, ?, ?, cast(? as jsonb), ?, 0, ?)
					""", UUID.randomUUID(), factType.name(), targetId, targetVersion, correlationId,
					payload, Timestamp.from(availableAt), Timestamp.from(availableAt));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Care user notification fact could not be serialized", exception);
		}
	}
}
