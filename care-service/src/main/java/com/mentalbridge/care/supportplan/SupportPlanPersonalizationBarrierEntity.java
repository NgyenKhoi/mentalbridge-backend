package com.mentalbridge.care.supportplan;

import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "support_plan_personalization_barrier")
class SupportPlanPersonalizationBarrierEntity {

	@Id private UUID id;
	private UUID personalizationId;
	private short ordinal;
	private String barrier;

	protected SupportPlanPersonalizationBarrierEntity() { }

	SupportPlanPersonalizationBarrierEntity(UUID personalizationId, int ordinal,
			SupportPlanPersonalizationService.CommonBarrier barrier) {
		this.id = UUID.randomUUID();
		this.personalizationId = personalizationId;
		this.ordinal = (short) ordinal;
		this.barrier = barrier.name();
	}

	String barrier() { return barrier; }
}
