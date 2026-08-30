package com.mentalbridge.care.assessment;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface AssessmentResultRepository extends JpaRepository<AssessmentResultEntity, UUID> {
}
