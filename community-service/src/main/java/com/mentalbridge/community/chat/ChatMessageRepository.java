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
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select message from ChatMessageEntity message where message.id = :id")
    Optional<ChatMessageEntity> lock(@Param("id") UUID id);
    @Query("select distinct m.senderProfileId from ChatMessageEntity m where m.conversationId = :group and m.senderMembershipId is not null and ((m.state = 'ACTIVE' and m.contentCiphertext is not null and m.expiresAt > :now) or m.state = 'RETRACTED')")
    List<UUID> retainedGroupSenders(@Param("group") UUID group, @Param("now") Instant now);
    @Query(value = """
            select max(m.created_at) from community_chat_message m join community_chat_membership s on s.conversation_id = m.conversation_id
            and s.id = m.sender_membership_id and s.profile_id = m.sender_profile_id and m.server_sequence > s.joined_after_sequence
            where m.conversation_id = :group and m.sender_profile_id in (:visible) and m.server_sequence > :floor
            and ((m.state = 'ACTIVE' and m.content_ciphertext is not null and m.expires_at > :now) or m.state = 'RETRACTED')
            """, nativeQuery = true)
    Optional<Instant> groupActivity(@Param("group") UUID group, @Param("visible") List<UUID> visible, @Param("floor") long floor, @Param("now") Instant now);
    @Query("select max(m.createdAt) from ChatMessageEntity m where m.conversationId = :id and m.serverSequence > :floor and ((m.state = 'ACTIVE' and m.contentCiphertext is not null and m.expiresAt > :now) or m.state = 'RETRACTED')")
    Optional<Instant> directActivity(@Param("id") UUID id, @Param("floor") long floor, @Param("now") Instant now);
    @Query(value = """
            select m.* from community_chat_message m join community_chat_membership s on s.conversation_id = m.conversation_id
            and s.id = m.sender_membership_id and s.profile_id = m.sender_profile_id and m.server_sequence > s.joined_after_sequence
            where m.conversation_id = :group and m.sender_profile_id in (:visible) and m.server_sequence > :after and m.server_sequence <= :through
            and ((m.state = 'ACTIVE' and m.content_ciphertext is not null and m.expires_at > :now) or m.state = 'RETRACTED')
            order by m.server_sequence limit :batch
            """, nativeQuery = true)
    List<ChatMessageEntity> groupHistory(@Param("group") UUID group, @Param("visible") List<UUID> visible,
            @Param("after") long after, @Param("through") long through, @Param("now") Instant now, @Param("batch") int batch);
    @Query(value = """
            select count(*) from community_chat_message m join community_chat_membership s on s.conversation_id = m.conversation_id
            and s.id = m.sender_membership_id and s.profile_id = m.sender_profile_id and m.server_sequence > s.joined_after_sequence
            where m.conversation_id = :group and m.sender_profile_id in (:visible) and m.sender_profile_id <> :viewer
            and m.server_sequence > :after and m.content_ciphertext is not null and m.expires_at > :now
            """, nativeQuery = true)
    long groupUnread(@Param("group") UUID group, @Param("viewer") UUID viewer, @Param("visible") List<UUID> visible, @Param("after") long after, @Param("now") Instant now);
    @Query(value = """
            select m.* from community_chat_message m join community_chat_membership s on s.conversation_id = m.conversation_id
            and s.id = m.sender_membership_id and s.profile_id = m.sender_profile_id and m.server_sequence > s.joined_after_sequence
            where m.conversation_id = :group and m.sender_profile_id in (:visible) and m.server_sequence > :floor and m.server_sequence <= :read
            and ((m.state = 'ACTIVE' and m.content_ciphertext is not null and m.expires_at > :now) or m.state = 'RETRACTED')
            order by m.server_sequence desc limit 1
            """, nativeQuery = true)
    Optional<ChatMessageEntity> groupLastRead(@Param("group") UUID group, @Param("visible") List<UUID> visible,
            @Param("floor") long floor, @Param("read") long read, @Param("now") Instant now);
    Optional<ChatMessageEntity> findByConversationIdAndSenderProfileIdAndClientMessageId(UUID conversation, UUID sender, UUID clientId);
    long countBySenderProfileIdAndCreatedAtAfter(UUID sender, Instant after);
    @Query(value = """
            select * from community_chat_message where conversation_id = :conversation
            and server_sequence > :after and server_sequence <= :through
            and ((state = 'ACTIVE' and content_ciphertext is not null and expires_at > :now) or state = 'RETRACTED')
            order by server_sequence limit :batch
            """, nativeQuery = true)
    List<ChatMessageEntity> history(@Param("conversation") UUID conversation, @Param("after") long after,
            @Param("through") long through, @Param("now") Instant now, @Param("batch") int batch);
    @Query("""
            select count(message) from ChatMessageEntity message where message.conversationId = :conversation
            and message.senderProfileId <> :viewer and message.serverSequence > :read
            and message.state = 'ACTIVE' and message.contentCiphertext is not null and message.expiresAt > :now
            """)
    long unread(@Param("conversation") UUID conversation, @Param("viewer") UUID viewer,
            @Param("read") long read, @Param("now") Instant now);
    @Query(value = """
            select * from community_chat_message where conversation_id = :conversation
            and server_sequence > :floor and server_sequence <= :read and ((state = 'ACTIVE' and content_ciphertext is not null and expires_at > :now) or state = 'RETRACTED')
            order by server_sequence desc limit 1
            """, nativeQuery = true)
    Optional<ChatMessageEntity> lastRead(@Param("conversation") UUID conversation, @Param("floor") long floor,
            @Param("read") long read, @Param("now") Instant now);
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update community_chat_message set content_ciphertext = null, purged_at = :now
            where id in (select id from community_chat_message where content_ciphertext is not null and expires_at <= :now
            order by expires_at, id limit :batch for update skip locked)
            """, nativeQuery = true)
    int purgeExpired(@Param("now") Instant now, @Param("batch") int batch);
}
