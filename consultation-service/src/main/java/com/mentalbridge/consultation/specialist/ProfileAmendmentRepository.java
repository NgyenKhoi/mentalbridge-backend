package com.mentalbridge.consultation.specialist;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

interface ProfileAmendmentRepository extends JpaRepository<ProfileAmendmentEntity, UUID> {

	Optional<ProfileAmendmentEntity> findFirstBySpecialistAccountIdOrderByCreatedAtDescIdDesc(UUID specialistAccountId);
	@Query("select a.specialistAccountId from ProfileAmendmentEntity a where a.id=:id")
	Optional<UUID> findOwnerById(UUID id);

	@Query("""
			select a from ProfileAmendmentEntity a, SpecialistProfileEntity p
			where a.specialistAccountId=p.accountId
			and a.status=com.mentalbridge.consultation.specialist.ProfileAmendmentEntity.Status.PENDING_REVIEW
			and p.approvalStatus=com.mentalbridge.consultation.specialist.SpecialistApprovalStatus.APPROVED
			order by a.submittedAt asc, a.id asc
			""")
	Slice<ProfileAmendmentEntity> findReviewable(Pageable pageable);
}
