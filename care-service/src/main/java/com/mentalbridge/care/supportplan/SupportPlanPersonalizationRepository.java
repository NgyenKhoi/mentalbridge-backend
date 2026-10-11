package com.mentalbridge.care.supportplan;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportPlanPersonalizationRepository extends JpaRepository<SupportPlanPersonalizationEntity, UUID> {

	Optional<SupportPlanPersonalizationEntity> findFirstByUserIdOrderByRevisionDesc(UUID userId);

	Optional<SupportPlanPersonalizationEntity> findByIdAndUserIdAndRevision(UUID id, UUID userId, long revision);
}

interface SupportPlanPersonalizationActivityModeRepository
		extends JpaRepository<SupportPlanPersonalizationActivityModeEntity, UUID> {

	List<SupportPlanPersonalizationActivityModeEntity> findByPersonalizationIdOrderByOrdinal(UUID personalizationId);
}

interface SupportPlanPersonalizationBarrierRepository
		extends JpaRepository<SupportPlanPersonalizationBarrierEntity, UUID> {

	List<SupportPlanPersonalizationBarrierEntity> findByPersonalizationIdOrderByOrdinal(UUID personalizationId);
}
