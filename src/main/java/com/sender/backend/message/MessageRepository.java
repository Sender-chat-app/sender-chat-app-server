package com.sender.backend.message;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.*;

public interface MessageRepository extends JpaRepository<Message, Long> {
    interface SearchRow {
        Long getId();
        Instant getCreatedAt();
        Double getRank();
        String getSnippet();
    }

    interface AttachmentListRow {
        UUID getId();
        Long getMessageId();
        String getOriginalFilename();
        String getContentType();
        Long getSizeBytes();
        Integer getSortOrder();
        Instant getCreatedAt();
    }

    Optional<Message> findByConversationIdAndSenderIdAndClientMessageId(Integer conversationId, Integer senderId, UUID clientMessageId);
    Optional<Message> findTopByConversationIdOrderByCreatedAtDescIdDesc(Integer conversationId);

    @Query(value = """
        select distinct on (conversation_id) id
        from messages
        where conversation_id in (:conversationIds)
        order by conversation_id, created_at desc, id desc
        """, nativeQuery = true)
    List<Long> findLatestMessageIds(@Param("conversationIds") Collection<Integer> conversationIds);

    @Query("""
        select m from Message m
        join fetch m.sender
        join fetch m.conversation
        where m.id in :ids
        """)
    List<Message> findAllWithSenderByIdIn(@Param("ids") Collection<Long> ids);

    @Query("""
        select m from Message m
        where m.conversation.id = :conversationId
          and (m.createdAt < :createdAt or (m.createdAt = :createdAt and m.id < :id))
        order by m.createdAt desc, m.id desc
        """)
    List<Message> findBefore(@Param("conversationId") Integer conversationId,
                             @Param("createdAt") Instant createdAt,
                             @Param("id") Long id,
                             Pageable pageable);

    List<Message> findByConversationIdOrderByCreatedAtDescIdDesc(Integer conversationId, Pageable pageable);

    List<Message> findByConversationId(Integer conversationId);

    @Query("""
        select distinct m.sender.id from Message m
        where m.conversation.id = :conversationId and m.id <= :messageId
        """)
    List<Integer> findSenderIdsThrough(@Param("conversationId") Integer conversationId,
                                       @Param("messageId") Long messageId);

    @Query(value = """
        select m.id as id, m.created_at as createdAt,
               ts_rank_cd(to_tsvector('simple', immutable_unaccent(m.body)),
                          plainto_tsquery('simple', immutable_unaccent(:query))) as rank,
               substring(m.body from 1 for 240) as snippet
        from messages m
        where m.conversation_id = :conversationId
          and to_tsvector('simple', immutable_unaccent(m.body))
              @@ plainto_tsquery('simple', immutable_unaccent(:query))
        order by rank desc, m.created_at desc, m.id desc
        """, nativeQuery = true)
    List<SearchRow> search(@Param("conversationId") Integer conversationId,
                           @Param("query") String query,
                           Pageable pageable);

    @Query(value = """
        select m.id as id, m.created_at as createdAt,
               ts_rank_cd(to_tsvector('simple', immutable_unaccent(m.body)),
                          plainto_tsquery('simple', immutable_unaccent(:query))) as rank,
               substring(m.body from 1 for 240) as snippet
        from messages m
        where m.conversation_id = :conversationId
          and to_tsvector('simple', immutable_unaccent(m.body))
              @@ plainto_tsquery('simple', immutable_unaccent(:query))
          and (
            ts_rank_cd(to_tsvector('simple', immutable_unaccent(m.body)),
                       plainto_tsquery('simple', immutable_unaccent(:query))) < :rank
            or (
              ts_rank_cd(to_tsvector('simple', immutable_unaccent(m.body)),
                         plainto_tsquery('simple', immutable_unaccent(:query))) = :rank
              and (m.created_at < :createdAt
                   or (m.created_at = :createdAt and m.id < :id))
            )
          )
        order by rank desc, m.created_at desc, m.id desc
        """, nativeQuery = true)
    List<SearchRow> searchBefore(@Param("conversationId") Integer conversationId,
                                 @Param("query") String query,
                                 @Param("rank") double rank,
                                 @Param("createdAt") Instant createdAt,
                                 @Param("id") Long id,
                                 Pageable pageable);

    @Query(value = """
        select cast(a->>'id' as uuid) as id,
               m.id as messageId,
               a->>'originalFilename' as originalFilename,
               a->>'contentType' as contentType,
               cast(a->>'sizeBytes' as bigint) as sizeBytes,
               cast(a->>'sortOrder' as int) as sortOrder,
               m.created_at as createdAt
        from messages m
        cross join lateral jsonb_array_elements(m.attachments) as a
        where m.conversation_id = :conversationId
          and jsonb_array_length(m.attachments) > 0
          and (
            :kind = 'all'
            or (:kind = 'media' and a->>'contentType' like 'image/%')
            or (:kind = 'files' and a->>'contentType' not like 'image/%')
          )
        order by m.created_at desc, m.id desc, cast(a->>'sortOrder' as int) asc
        """, nativeQuery = true)
    List<AttachmentListRow> listAttachments(@Param("conversationId") Integer conversationId,
                                            @Param("kind") String kind,
                                            Pageable pageable);

    @Query(value = """
        select cast(a->>'id' as uuid) as id,
               m.id as messageId,
               a->>'originalFilename' as originalFilename,
               a->>'contentType' as contentType,
               cast(a->>'sizeBytes' as bigint) as sizeBytes,
               cast(a->>'sortOrder' as int) as sortOrder,
               m.created_at as createdAt
        from messages m
        cross join lateral jsonb_array_elements(m.attachments) as a
        where m.conversation_id = :conversationId
          and jsonb_array_length(m.attachments) > 0
          and (
            :kind = 'all'
            or (:kind = 'media' and a->>'contentType' like 'image/%')
            or (:kind = 'files' and a->>'contentType' not like 'image/%')
          )
          and (
            m.created_at < :createdAt
            or (m.created_at = :createdAt and m.id < :messageId)
            or (m.created_at = :createdAt and m.id = :messageId
                and cast(a->>'sortOrder' as int) > :sortOrder)
          )
        order by m.created_at desc, m.id desc, cast(a->>'sortOrder' as int) asc
        """, nativeQuery = true)
    List<AttachmentListRow> listAttachmentsBefore(@Param("conversationId") Integer conversationId,
                                                  @Param("kind") String kind,
                                                  @Param("createdAt") Instant createdAt,
                                                  @Param("messageId") Long messageId,
                                                  @Param("sortOrder") int sortOrder,
                                                  Pageable pageable);
}
