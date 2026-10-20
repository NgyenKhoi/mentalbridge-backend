package com.mentalbridge.community.chat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.repository.query.Param;

interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, UUID> {
    Optional<ChatMessageEntity> findByConversationIdAndSenderProfileIdAndClientMessageId(UUID conversation, UUID sender, UUID clientId);
    long countBySenderProfileIdAndCreatedAtAfter(UUID sender, Instant after);
    @Query(value = """
            select * from community_chat_message where conversation_id = :conversation
            and server_sequence > :after and server_sequence <= :through
            and content_ciphertext is not null and expires_at > :now
            order by server_sequence limit :batch
            """, nativeQuery = true)
    List<ChatMessageEntity> history(@Param("conversation") UUID conversation, @Param("after") long after,
            @Param("through") long through, @Param("now") Instant now, @Param("batch") int batch);
    @Query("""
            select count(message) from ChatMessageEntity message where message.conversationId = :conversation
            and message.senderProfileId <> :viewer and message.serverSequence > :read
            and message.contentCiphertext is not null and message.expiresAt > :now
            """)
    long unread(@Param("conversation") UUID conversation, @Param("viewer") UUID viewer,
            @Param("read") long read, @Param("now") Instant now);
    @Query(value = """
            select * from community_chat_message where conversation_id = :conversation
            and server_sequence <= :read and content_ciphertext is not null and expires_at > :now
            order by server_sequence desc limit 1
            """, nativeQuery = true)
    Optional<ChatMessageEntity> lastRead(@Param("conversation") UUID conversation, @Param("read") long read, @Param("now") Instant now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update community_chat_message set content_ciphertext = null, purged_at = :now
            where id in (select id from community_chat_message where content_ciphertext is not null and expires_at <= :now
            order by expires_at, id limit :batch for update skip locked)
            """, nativeQuery = true)
    int purgeExpired(@Param("now") Instant now, @Param("batch") int batch);
}
