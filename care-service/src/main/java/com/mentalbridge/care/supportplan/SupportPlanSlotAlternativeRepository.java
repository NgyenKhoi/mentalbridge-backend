package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportPlanSlotAlternativeRepository extends JpaRepository<SupportPlanSlotAlternativeEntity, UUID> {
	List<SupportPlanSlotAlternativeEntity> findBySupportPlanSlotIdOrderByOrdinal(UUID supportPlanSlotId);
}
