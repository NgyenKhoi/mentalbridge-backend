package com.mentalbridge.care.supportplan;

import java.time.Instant;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_schedule_command")
@IdClass(SupportPlanScheduleCommandEntity.Key.class)
class SupportPlanScheduleCommandEntity {

	@Id private UUID userId;
	@Id private String idempotencyKey;
	@JdbcTypeCode(SqlTypes.CHAR)
	@Column(length = 64)
	private String requestHash;
	private UUID supportPlanId;
	private UUID actionId;
	private long expectedPlanVersion;
	private long resultingPlanVersion;
	private UUID resultingScheduleId;
	private Integer resultingScheduleVersion;
	private Instant createdAt;

	protected SupportPlanScheduleCommandEntity() { }

	SupportPlanScheduleCommandEntity(UUID userId, String idempotencyKey, String requestHash,
			UUID supportPlanId, UUID actionId, long expectedPlanVersion, long resultingPlanVersion,
			UUID resultingScheduleId, Integer resultingScheduleVersion, Instant createdAt) {
		this.userId = userId;
		this.idempotencyKey = idempotencyKey;
		this.requestHash = requestHash;
		this.supportPlanId = supportPlanId;
		this.actionId = actionId;
		this.expectedPlanVersion = expectedPlanVersion;
		this.resultingPlanVersion = resultingPlanVersion;
		this.resultingScheduleId = resultingScheduleId;
		this.resultingScheduleVersion = resultingScheduleVersion;
		this.createdAt = createdAt;
	}

	UUID userId() { return userId; }
	String idempotencyKey() { return idempotencyKey; }
	String requestHash() { return requestHash; }
	UUID supportPlanId() { return supportPlanId; }
	UUID actionId() { return actionId; }
	long resultingPlanVersion() { return resultingPlanVersion; }
	UUID resultingScheduleId() { return resultingScheduleId; }
	Integer resultingScheduleVersion() { return resultingScheduleVersion; }

	static class Key implements java.io.Serializable {
		private UUID userId;
		private String idempotencyKey;

		public Key() { }

		Key(UUID userId, String idempotencyKey) {
			this.userId = userId;
			this.idempotencyKey = idempotencyKey;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Key value
					&& java.util.Objects.equals(userId, value.userId)
					&& java.util.Objects.equals(idempotencyKey, value.idempotencyKey);
		}

		@Override
		public int hashCode() {
			return java.util.Objects.hash(userId, idempotencyKey);
		}
	}
}
