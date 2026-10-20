package com.mentalbridge.community.chat;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

interface ChatMembershipRepository extends JpaRepository<ChatMembershipEntity, UUID> {
    List<ChatMembershipEntity> findByConversationIdAndDepartedAtIsNullOrderById(UUID conversation);
    Optional<ChatMembershipEntity> findByConversationIdAndProfileIdAndDepartedAtIsNull(UUID conversation, UUID profile);
    List<ChatMembershipEntity> findByProfileIdAndDepartedAtIsNull(UUID profile);
}
