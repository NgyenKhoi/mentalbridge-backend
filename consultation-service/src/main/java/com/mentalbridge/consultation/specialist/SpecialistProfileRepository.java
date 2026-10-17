package com.mentalbridge.consultation.specialist;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface SpecialistProfileRepository extends JpaRepository<SpecialistProfileEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select profile from SpecialistProfileEntity profile where profile.accountId = :accountId")
	Optional<SpecialistProfileEntity> findByIdForUpdate(@Param("accountId") UUID accountId);

	@Query("""
			select profile from SpecialistProfileEntity profile
			where profile.approvalStatus = com.mentalbridge.consultation.specialist.SpecialistApprovalStatus.PENDING
			and profile.submittedAt is not null
			order by profile.submittedAt asc, profile.accountId asc
			""")
	List<SpecialistProfileEntity> findSubmittedPending(Pageable pageable);

	List<SpecialistProfileEntity> findByApprovalStatusOrderByUpdatedAtDescAccountIdAsc(
			SpecialistApprovalStatus approvalStatus, Pageable pageable);
}
