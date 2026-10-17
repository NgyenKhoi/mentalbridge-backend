package com.mentalbridge.consultation.specialist;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SpecialistProfileStatusHistoryRepository
		extends JpaRepository<SpecialistProfileStatusHistoryEntity, UUID> {
}
