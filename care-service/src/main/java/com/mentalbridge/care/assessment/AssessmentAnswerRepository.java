package com.mentalbridge.care.assessment;

import org.springframework.data.jpa.repository.JpaRepository;

interface AssessmentAnswerRepository extends JpaRepository<AssessmentAnswerEntity, AssessmentAnswerId> {
}
