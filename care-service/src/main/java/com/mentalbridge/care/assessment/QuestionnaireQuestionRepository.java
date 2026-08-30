package com.mentalbridge.care.assessment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuestionnaireQuestionRepository extends JpaRepository<QuestionnaireQuestionEntity, UUID> {

	List<QuestionnaireQuestionEntity> findByDefinitionIdOrderByItemNumber(UUID definitionId);
}
