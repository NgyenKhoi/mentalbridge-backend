package com.mentalbridge.care.consent;

import java.util.Optional;
import java.util.Collection;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface ConsentDecisionRepository extends JpaRepository<ConsentDecisionEntity, UUID> {
	Optional<ConsentDecisionEntity> findByUserIdAndConsentTypeAndIdempotencyKey(UUID userId, String consentType,
			String idempotencyKey);
	Optional<ConsentDecisionEntity> findFirstByUserIdAndConsentTypeOrderByDecidedAtDescIdDesc(UUID userId,
			String consentType);
	Optional<ConsentDecisionEntity> findFirstByUserIdAndConsentTypeInOrderByDecidedAtDescIdDesc(UUID userId,
			Collection<String> consentTypes);
}
