package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportPlanTemplateFamilyRepository extends JpaRepository<SupportPlanTemplateFamilyEntity, UUID> {
	List<SupportPlanTemplateFamilyEntity> findBySupportPlanIdOrderByOrdinal(UUID supportPlanId);
}
