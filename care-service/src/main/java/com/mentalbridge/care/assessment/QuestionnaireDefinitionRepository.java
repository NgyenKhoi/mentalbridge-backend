package com.mentalbridge.care.assessment;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface QuestionnaireDefinitionRepository extends JpaRepository<QuestionnaireDefinitionEntity, UUID> {

	Optional<QuestionnaireDefinitionEntity> findByInstrumentAndLocaleAndStatus(String instrument, String locale,
			String status);
}
