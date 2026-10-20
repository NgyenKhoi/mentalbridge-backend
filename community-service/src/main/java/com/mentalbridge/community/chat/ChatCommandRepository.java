package com.mentalbridge.community.chat;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChatCommandRepository extends JpaRepository<ChatCommandEntity, UUID> {
    Optional<ChatCommandEntity> findByActorProfileIdAndOperationAndIdempotencyKey(UUID actor, String operation, UUID key);
}
