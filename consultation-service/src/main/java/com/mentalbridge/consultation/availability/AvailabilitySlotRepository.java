package com.mentalbridge.consultation.availability;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

interface AvailabilitySlotRepository extends JpaRepository<AvailabilitySlotEntity, UUID> {

	Optional<AvailabilitySlotEntity> findBySpecialistAccountIdAndIdempotencyKey(
			UUID specialistAccountId, String idempotencyKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("""
			select slot from AvailabilitySlotEntity slot
			where slot.id = :slotId and slot.specialistAccountId = :specialistAccountId
			""")
	Optional<AvailabilitySlotEntity> findOwnedByIdForUpdate(@Param("slotId") UUID slotId,
			@Param("specialistAccountId") UUID specialistAccountId);

	@Query("""
			select (count(slot) > 0) from AvailabilitySlotEntity slot
			where slot.specialistAccountId = :specialistAccountId
			and slot.status = com.mentalbridge.consultation.availability.AvailabilitySlotStatus.ACTIVE
			and slot.startAt < :endAt and slot.endAt > :startAt
			""")
	boolean existsActiveOverlap(@Param("specialistAccountId") UUID specialistAccountId,
			@Param("startAt") Instant startAt, @Param("endAt") Instant endAt);

	@Query("""
			select slot from AvailabilitySlotEntity slot
			where slot.specialistAccountId = :specialistAccountId
			and slot.startAt >= :from and slot.startAt < :to
			and (:includeWithdrawn = true or slot.status = com.mentalbridge.consultation.availability.AvailabilitySlotStatus.ACTIVE)
			order by slot.startAt asc, slot.id asc
			""")
	List<AvailabilitySlotEntity> listOwned(@Param("specialistAccountId") UUID specialistAccountId,
			@Param("from") Instant from, @Param("to") Instant to,
			@Param("includeWithdrawn") boolean includeWithdrawn, Pageable pageable);
}
