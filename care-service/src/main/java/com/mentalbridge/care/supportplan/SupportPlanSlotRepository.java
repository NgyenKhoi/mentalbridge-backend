package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportPlanSlotRepository extends JpaRepository<SupportPlanSlotEntity, UUID> {
	List<SupportPlanSlotEntity> findBySupportPlanIdOrderByOrdinal(UUID supportPlanId);
}
