package com.mentalbridge.consultation.specialist;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpecialistCredentialDocumentRepository extends JpaRepository<SpecialistCredentialDocumentEntity, UUID> {
	List<SpecialistCredentialDocumentEntity> findTop100BySpecialistAccountIdOrderByCreatedAtDescIdDesc(UUID accountId);
	List<SpecialistCredentialDocumentEntity> findTop100ByVerificationStatusOrderByCreatedAtAscIdAsc(
			SpecialistCredentialDocumentEntity.Status status);
	List<SpecialistCredentialDocumentEntity> findTop100BySpecialistAccountIdAndVerificationStatusAndBadgeVisibleTrueOrderByCreatedAtAscIdAsc(
			UUID accountId, SpecialistCredentialDocumentEntity.Status status);
}
