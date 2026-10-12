package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportPlanFocusRepository extends JpaRepository<SupportPlanFocusEntity, UUID> {

	List<SupportPlanFocusEntity> findBySupportPlanIdAndUserIdOrderByOrdinal(UUID supportPlanId, UUID userId);
}
