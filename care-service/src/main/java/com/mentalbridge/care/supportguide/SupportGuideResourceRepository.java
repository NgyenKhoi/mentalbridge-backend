package com.mentalbridge.care.supportguide;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SupportGuideResourceRepository extends JpaRepository<SupportGuideResourceEntity, UUID> {
	List<SupportGuideResourceEntity> findBySupportGuideIdOrderByOrdinal(UUID supportGuideId);
}
