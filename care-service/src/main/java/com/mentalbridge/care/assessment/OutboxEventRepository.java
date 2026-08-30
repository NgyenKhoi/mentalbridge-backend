package com.mentalbridge.care.assessment;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface OutboxEventRepository extends JpaRepository<OutboxEventEntity, UUID> {
}
