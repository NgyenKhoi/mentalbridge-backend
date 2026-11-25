package com.mentalbridge.care.supportplan;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportPlanScheduleCommandRepository
		extends JpaRepository<SupportPlanScheduleCommandEntity, SupportPlanScheduleCommandEntity.Key> {

	Optional<SupportPlanScheduleCommandEntity> findByUserIdAndIdempotencyKey(
			UUID userId, String idempotencyKey);
}
