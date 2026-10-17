package com.mentalbridge.care.support;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportEvaluationV2SafetyRepository extends JpaRepository<SupportEvaluationV2SafetyEntity, UUID> {
}
