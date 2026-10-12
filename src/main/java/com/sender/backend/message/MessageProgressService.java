package com.sender.backend.message;

import com.sender.backend.conversation.*;
import com.sender.backend.message.MessageProgressDtos.*;
import com.sender.backend.realtime.RealtimePublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class MessageProgressService {
    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final RealtimePublisher realtime;
    private final Map<ProgressKey, PendingProgress> pending = new ConcurrentHashMap<>();

    public MessageProgressService(ConversationRepository conversations,
                                  ConversationMemberRepository members,
                                  MessageRepository messages,
                                  RealtimePublisher realtime) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.realtime = realtime;
    }

    @Transactional(readOnly = true)
    public ProgressResponse acceptDelivered(Integer userId, Integer conversationId, ProgressRequest request) {
        return accept(userId, conversationId, request.messageId(), false);
    }

    @Transactional(readOnly = true)
    public ProgressResponse acceptRead(Integer userId, Integer conversationId, ProgressRequest request) {
        return accept(userId, conversationId, request.messageId(), true);
    }

    private ProgressResponse accept(Integer userId, Integer conversationId, Long messageId, boolean read) {
        ConversationMember member = memberForRead(userId, conversationId);
        Message message = messageForConversation(conversationId, messageId, member);
        ProgressKey key = new ProgressKey(userId, conversationId);
        PendingProgress value = pending.computeIfAbsent(key, ignored -> new PendingProgress());
        synchronized (value) {
            if (read) {
                boolean changed = isAfter(message, max(cursor(member.getLastReadMessageId(), member.getLastReadAt()), value.read));
                if (changed) value.read = new Cursor(message.getId(), message.getCreatedAt());
                if (isAfter(message, max(cursor(member.getLastDeliveredMessageId(), member.getLastDeliveredAt()), value.delivered))) {
                    value.delivered = new Cursor(message.getId(), message.getCreatedAt());
                }
                if (changed) publishProgress(conversationId, userId, message.getId(), "READ");
            } else if (isAfter(message, max(cursor(member.getLastDeliveredMessageId(), member.getLastDeliveredAt()), value.delivered))) {
                value.delivered = new Cursor(message.getId(), message.getCreatedAt());
                publishProgress(conversationId, userId, message.getId(), "DELIVERED");
            }
            return response(conversationId, userId, member, value);
        }
    }

    @Scheduled(fixedDelayString = "${app.message-progress.flush-ms:2000}")
    @Transactional
    public void flushPending() {
        pending.forEach(this::flush);
    }

    @Transactional
    public void flushPendingNow() {
        pending.forEach(this::flush);
    }

    private void flush(ProgressKey key, PendingProgress value) {
        ConversationMember member = memberForUpdate(key.userId(), key.conversationId());
        synchronized (value) {
            if (value.delivered != null) {
                member.advanceDelivered(value.delivered.id(), value.delivered.createdAt());
                value.delivered = null;
            }
            if (value.read != null) {
                member.advanceRead(value.read.id(), value.read.createdAt());
                value.read = null;
            }
        }
    }

    private ProgressResponse response(Integer conversationId, Integer userId,
                                      ConversationMember member, PendingProgress value) {
        Cursor delivered = max(cursor(member.getLastDeliveredMessageId(), member.getLastDeliveredAt()), value.delivered);
        Cursor read = max(cursor(member.getLastReadMessageId(), member.getLastReadAt()), value.read);
        Message latest = messages.findTopByConversationIdOrderByCreatedAtDescIdDesc(conversationId).orElse(null);
        boolean hasUnread = hasUnread(latest, userId, read);
        return new ProgressResponse(conversationId,
                delivered == null ? null : delivered.id(), delivered == null ? null : delivered.createdAt(),
                read == null ? null : read.id(), read == null ? null : read.createdAt(), hasUnread);
    }

    private static boolean hasUnread(Message latest, Integer userId, Cursor read) {
        if (latest == null || latest.getSender().getId().equals(userId)) {
            return false;
        }
        return read == null
                || latest.getCreatedAt().isAfter(read.createdAt())
                || (latest.getCreatedAt().equals(read.createdAt()) && latest.getId() > read.id());
    }

    private Message messageForConversation(Integer conversationId, Long messageId, ConversationMember member) {
        Message message = messages.findById(messageId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Message not found"));
        if (!message.getConversation().getId().equals(conversationId)) {
            throw error(HttpStatus.BAD_REQUEST, "Message does not belong to this conversation");
        }
        if (message.getCreatedAt().isBefore(member.getJoinedAt())) {
            throw error(HttpStatus.BAD_REQUEST, "Message predates conversation membership");
        }
        return message;
    }

    private void publishProgress(Integer conversationId, Integer viewerId, Long messageId, String kind) {
        ProgressEvent event = new ProgressEvent(conversationId, viewerId, kind, messageId, Instant.now(), UUID.randomUUID());
        messages.findSenderIdsThrough(conversationId, messageId).stream()
                .filter(senderId -> !senderId.equals(viewerId))
                .forEach(senderId -> realtime.sendToUser(senderId, "MESSAGE_" + kind, event));
    }

    private ConversationMember memberForUpdate(Integer userId, Integer conversationId) {
        return members.findMembershipForUpdate(conversationId, userId)
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "Authenticated user is not a member of this conversation"));
    }

    private ConversationMember memberForRead(Integer userId, Integer conversationId) {
        if (!conversations.existsById(conversationId)) throw error(HttpStatus.NOT_FOUND, "Conversation not found");
        return members.findMembership(conversationId, userId)
                .orElseThrow(() -> error(HttpStatus.FORBIDDEN, "Authenticated user is not a member of this conversation"));
    }

    private Cursor cursor(Long id, Instant at) {
        return id == null || at == null ? null : new Cursor(id, at);
    }

    private Cursor max(Cursor first, Cursor second) {
        return second != null && (first == null || isAfter(second, first)) ? second : first;
    }

    private boolean isAfter(Message message, Cursor cursor) {
        return cursor == null || isAfter(new Cursor(message.getId(), message.getCreatedAt()), cursor);
    }

    private boolean isAfter(Cursor first, Cursor second) {
        return first.createdAt().isAfter(second.createdAt())
                || (first.createdAt().equals(second.createdAt()) && first.id() > second.id());
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private record ProgressKey(Integer userId, Integer conversationId) {}
    private record Cursor(Long id, Instant createdAt) {}
    private static final class PendingProgress {
        private Cursor delivered;
        private Cursor read;
    }
}
