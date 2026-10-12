package com.sender.backend.conversation;

import com.sender.backend.auth.AuthDtos.PublicUser;
import com.sender.backend.message.Message;
import com.sender.backend.user.User;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

public final class ConversationDtos {
    private ConversationDtos() {}

    public record DirectRequest(@NotNull Integer otherUserId) {}

    public record GroupRequest(
            @NotBlank @Size(max = 100) String name,
            @NotEmpty @Size(max = 50) Set<@NotNull Integer> memberIds) {}

    public record MemberRequest(@NotNull Integer userId) {}

    public record UpdateGroupRequest(
            @Size(max = 100) String name,
            @Size(max = 512) String avatarObjectKey) {}

    public record AvatarUploadRequest(
            @Schema(description = "Image MIME type", example = "image/png")
            @NotBlank String contentType,
            @Schema(description = "Exact byte length of the file to upload", example = "1200")
            @NotNull @Min(1) Long contentLength) {}

    public record AvatarUploadResponse(
            String putUrl,
            String objectKey,
            String publicUrl,
            Instant expiresAt) {}

    public record MemberResponse(Integer userId, String username, String displayName, String avatarUrl, Instant joinedAt) {
        static MemberResponse from(ConversationMember member) {
            User user = member.getUser();
            return new MemberResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getAvatarUrl(),
                    member.getJoinedAt());
        }
    }

    public record ConversationResponse(Integer id, Conversation.Type type, String name, String avatarUrl,
                                       Instant createdAt, Instant updatedAt, List<MemberResponse> members) {
        static ConversationResponse from(Conversation conversation, List<ConversationMember> members) {
            return new ConversationResponse(conversation.getId(), conversation.getType(), conversation.getName(),
                    conversation.getAvatarUrl(), conversation.getCreatedAt(), conversation.getUpdatedAt(),
                    members.stream().map(MemberResponse::from).toList());
        }
    }

    public record ConversationUpdatedPayload(
            Integer conversationId,
            Membership membership,
            ConversationResponse conversation) {
        public enum Membership { ACTIVE, REMOVED, DISSOLVED }
    }

    public record LatestMessage(Long id, String body, PublicUser sender, Instant createdAt, UUID clientMessageId) {
        static LatestMessage from(Message message) {
            return new LatestMessage(message.getId(), message.getBody(), PublicUser.from(message.getSender()),
                    message.getCreatedAt(), message.getClientMessageId());
        }
    }

    public record Summary(Integer id, Conversation.Type type, String name, String avatarUrl, PublicUser otherUser,
                          LatestMessage latestMessage, boolean hasUnread, Instant updatedAt, boolean online) {}

    public record Page<T>(List<T> items, String nextCursor, boolean hasMore) {}
}
