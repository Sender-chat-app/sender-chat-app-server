package com.sender.backend.conversation;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.util.*;

public interface ConversationMemberRepository extends JpaRepository<ConversationMember, ConversationMember.Id> {
    boolean existsByConversationIdAndUserId(Integer conversationId, Integer userId);
    long countByConversationId(Integer conversationId);
    List<ConversationMember> findByConversationId(Integer conversationId);

    @Query("""
        select m from ConversationMember m
        join fetch m.user
        where m.conversation.id in :conversationIds
        """)
    List<ConversationMember> findByConversationIdIn(@Param("conversationIds") Collection<Integer> conversationIds);

    @Query("""
        select m from ConversationMember m
        where m.conversation.id in :conversationIds and m.user.id = :userId
        """)
    List<ConversationMember> findMemberships(@Param("conversationIds") Collection<Integer> conversationIds,
                                              @Param("userId") Integer userId);

    @Query("""
        select m from ConversationMember m
        where m.conversation.id = :conversationId and m.user.id = :userId
        """)
    Optional<ConversationMember> findMembership(@Param("conversationId") Integer conversationId,
                                                 @Param("userId") Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
        select m from ConversationMember m
        where m.conversation.id = :conversationId and m.user.id = :userId
        """)
    Optional<ConversationMember> findMembershipForUpdate(@Param("conversationId") Integer conversationId,
                                                         @Param("userId") Integer userId);
}
