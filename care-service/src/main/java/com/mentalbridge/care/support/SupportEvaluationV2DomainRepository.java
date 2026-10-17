package com.mentalbridge.care.support;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportEvaluationV2DomainRepository extends JpaRepository<SupportEvaluationV2DomainEntity, UUID> {

	List<SupportEvaluationV2DomainEntity> findBySupportEvaluationIdOrderByOrdinal(UUID supportEvaluationId);
}
