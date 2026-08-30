package com.mentalbridge.care.assessment;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuestionnaireScoreBandRepository
		extends JpaRepository<QuestionnaireScoreBandEntity, QuestionnaireScoreBandId> {

	List<QuestionnaireScoreBandEntity> findByDefinitionIdOrderByOrdinal(UUID definitionId);
}
