package com.sender.backend.message;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.UUID;

public final class MessageProgressDtos {
    private MessageProgressDtos() {}

    public record ProgressRequest(@NotNull Long messageId) {}

    public record ProgressResponse(
            Integer conversationId,
            Long deliveredThroughMessageId,
            Instant deliveredAt,
            Long readThroughMessageId,
            Instant readAt,
            boolean hasUnread) {}

    public record ProgressEvent(
            Integer conversationId,
            Integer viewerId,
            String kind,
            Long throughMessageId,
            Instant occurredAt,
            UUID eventId) {}
}
