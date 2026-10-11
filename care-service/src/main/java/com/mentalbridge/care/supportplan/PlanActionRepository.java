package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface PlanActionRepository extends JpaRepository<PlanActionEntity, UUID> {

	List<PlanActionEntity> findBySupportPlanIdAndUserIdOrderByPlanFocusIdAscOrdinalAsc(
			UUID supportPlanId, UUID userId);
}
