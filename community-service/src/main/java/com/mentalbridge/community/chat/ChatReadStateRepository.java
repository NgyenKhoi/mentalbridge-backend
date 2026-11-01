package com.mentalbridge.community.chat;

import org.springframework.data.jpa.repository.JpaRepository;

interface ChatReadStateRepository extends JpaRepository<ChatReadStateEntity, ChatReadStateEntity.ReadId> {}
