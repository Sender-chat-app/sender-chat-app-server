package com.sender.backend.conversation;

import com.sender.backend.auth.AuthDtos.PublicUser;
import com.sender.backend.friendship.Friendship;
import com.sender.backend.friendship.FriendshipRepository;
import com.sender.backend.user.*;
import com.sender.backend.conversation.ConversationDtos.*;
import com.sender.backend.message.Message;
import com.sender.backend.message.MessageAttachment;
import com.sender.backend.message.MessageRepository;
import com.sender.backend.presence.PresenceService;
import com.sender.backend.realtime.RealtimePublisher;
import com.sender.backend.storage.ObjectPublicUrl;
import com.sender.backend.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.Locale;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ConversationService {
    private static final Set<String> ALLOWED_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg",
            "image/png", "png",
            "image/webp", "webp");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}");
    private static final Pattern GROUP_AVATAR_KEY = Pattern.compile(
            "^group-avatars/(\\d+)/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(jpg|jpeg|png|webp)$");

    private final ConversationRepository conversations;
    private final ConversationMemberRepository members;
    private final MessageRepository messages;
    private final FriendshipRepository friendships;
    private final UserRepository users;
    private final PresenceService presence;
    private final RealtimePublisher realtime;
    private final ObjectStorage storage;
    private final ObjectPublicUrl publicUrls;
    private final Duration presignTtl;
    private final long maxBytes;
    private final TransactionTemplate readOnlyTx;

    public ConversationService(ConversationRepository conversations, ConversationMemberRepository members,
                               MessageRepository messages, FriendshipRepository friendships, UserRepository users,
                               PresenceService presence, RealtimePublisher realtime,
                               ObjectStorage storage, ObjectPublicUrl publicUrls,
                               PlatformTransactionManager transactionManager,
                               @Value("${app.s3.presign-seconds:300}") long presignSeconds,
                               @Value("${app.avatar.max-bytes:2097152}") long maxBytes) {
        this.conversations = conversations;
        this.members = members;
        this.messages = messages;
        this.friendships = friendships;
        this.users = users;
        this.presence = presence;
        this.realtime = realtime;
        this.storage = storage;
        this.publicUrls = publicUrls;
        this.presignTtl = Duration.ofSeconds(presignSeconds);
        this.maxBytes = maxBytes;
        this.readOnlyTx = new TransactionTemplate(transactionManager);
        this.readOnlyTx.setReadOnly(true);
    }

    @Transactional
    public ConversationResponse direct(Integer userId, DirectRequest request) {
        if (userId.equals(request.otherUserId())) {
            throw error(HttpStatus.BAD_REQUEST, "You cannot create a conversation with yourself");
        }
        User other = user(request.otherUserId());
        requireFriends(userId, other.getId());
        String key = directKey(userId, other.getId());
        Conversation conversation = conversations.findByDirectKey(key).orElse(null);
        if (conversation == null) {
            Instant now = Instant.now();
            boolean inserted = conversations.insertDirectIfAbsent(key, now, now) == 1;
            conversation = conversations.findByDirectKey(key)
                    .orElseThrow(() -> error(HttpStatus.CONFLICT, "Could not create direct conversation"));
            if (inserted) {
                addMember(conversation, user(userId));
                addMember(conversation, other);
            }
        }
        return response(conversation);
    }

    @Transactional
    public ConversationResponse group(Integer userId, GroupRequest request) {
        Set<Integer> ids = new LinkedHashSet<>(request.memberIds());
        ids.remove(userId);
        if (ids.isEmpty()) throw error(HttpStatus.BAD_REQUEST, "A group needs at least one friend");
        User creator = user(userId);
        Conversation conversation = conversations.save(Conversation.group(request.name().trim()));
        addMember(conversation, creator);
        for (Integer memberId : ids) {
            User member = user(memberId);
            requireFriends(userId, memberId);
            addMember(conversation, member);
        }
        return response(conversation);
    }

    public Page<Summary> list(Integer userId, String cursor, int limit) {
        ListAssembly assembly = Objects.requireNonNull(readOnlyTx.execute(status -> assembleList(userId, cursor, limit)));
        Set<Integer> onlinePeers = presence.onlineAmong(assembly.peerIds());
        return assembly.toPage(onlinePeers);
    }

    @Transactional(readOnly = true)
    public ConversationResponse get(Integer userId, Integer conversationId) {
        return response(memberConversation(userId, conversationId));
    }

    @Transactional
    public ConversationResponse updateGroup(Integer userId, Integer conversationId, UpdateGroupRequest request) {
        boolean hasName = request.name() != null;
        boolean hasAvatar = request.avatarObjectKey() != null && !request.avatarObjectKey().isBlank();
        if (!hasName && !hasAvatar) {
            throw error(HttpStatus.BAD_REQUEST, "Provide a group name and/or avatar object key");
        }
        Conversation conversation = lockedMemberConversation(userId, conversationId);
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw error(HttpStatus.BAD_REQUEST, "Only group conversations can be updated");
        }
        if (hasName) {
            conversation.rename(normalizeGroupName(request.name()));
        }
        String previousKey = null;
        if (hasAvatar) {
            String key = request.avatarObjectKey().trim();
            assertGroupAvatarKey(conversationId, key);
            ObjectStorage.StoredObject object = storage.head(key)
                    .orElseThrow(() -> error(HttpStatus.BAD_REQUEST, "Avatar object was not found"));
            String type = object.contentType() == null ? "" : object.contentType().split(";")[0].trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_TYPES.contains(type)) {
                throw error(HttpStatus.BAD_REQUEST, "Uploaded avatar has an unsupported type");
            }
            previousKey = publicUrls.objectKeyFrom(conversation.getAvatarUrl());
            conversation.setAvatarUrl(publicUrls.of(key));
        }
        conversation.touch(Instant.now());
        ConversationResponse body = response(conversation);
        publishActive(conversation.getId(), body);
        if (previousKey != null && !previousKey.equals(request.avatarObjectKey().trim())) {
            deleteAfterCommit(previousKey);
        }
        return body;
    }

    public AvatarUploadResponse createGroupAvatarUpload(Integer userId, Integer conversationId, AvatarUploadRequest request) {
        Conversation conversation = memberConversation(userId, conversationId);
        if (conversation.getType() != Conversation.Type.GROUP) {
            throw error(HttpStatus.BAD_REQUEST, "Only group conversations have avatars");
        }
        String contentType = request.contentType() == null ? "" : request.contentType().trim().toLowerCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(contentType)) {
            throw error(HttpStatus.BAD_REQUEST, "Avatar must be a JPEG, PNG, or WebP image");
        }
        long length = request.contentLength() == null ? 0 : request.contentLength();
        if (length < 1) {
            throw error(HttpStatus.BAD_REQUEST, "Avatar file size is required");
        }
        length = Math.clamp(length, 1, maxBytes);
        if (length > maxBytes) {
            throw error(HttpStatus.PAYLOAD_TOO_LARGE, "Avatar must be 2MB or smaller");
        }
        String key = "group-avatars/" + conversationId + "/" + UUID.randomUUID() + "." + EXTENSIONS.get(contentType);
        ObjectStorage.PresignedUpload upload = storage.presignPut(key, contentType, length, presignTtl);
        return new AvatarUploadResponse(upload.putUrl().toString(), key, publicUrls.of(key), upload.expiresAt());
    }

    @Transactional
    public ConversationResponse addMember(Integer userId, Integer conversationId, MemberRequest request) {
        Conversation conversation = lockedMemberConversation(userId, conversationId);
        if (conversation.getType() != Conversation.Type.GROUP) throw error(HttpStatus.BAD_REQUEST, "Direct conversations have no group members");
        if (userId.equals(request.userId())) throw error(HttpStatus.BAD_REQUEST, "You are already a member");
        requireFriends(userId, request.userId());
        if (members.existsByConversationIdAndUserId(conversationId, request.userId())) {
            throw error(HttpStatus.CONFLICT, "User is already a member");
        }
        addMember(conversation, user(request.userId()));
        conversation.touch(Instant.now());
        ConversationResponse body = response(conversation);
        publishActive(conversationId, body);
        return body;
    }

    @Transactional
    public Optional<ConversationResponse> removeMember(Integer userId, Integer conversationId, Integer targetUserId) {
        Conversation conversation = lockedMemberConversation(userId, conversationId);
        if (conversation.getType() != Conversation.Type.GROUP) throw error(HttpStatus.BAD_REQUEST, "Direct conversations have no group members");
        if (!members.existsByConversationIdAndUserId(conversationId, targetUserId)) {
            throw error(HttpStatus.NOT_FOUND, "Conversation member not found");
        }
        long count = members.countByConversationId(conversationId);
        if (count <= 1) {
            dissolve(conversation);
            return Optional.empty();
        }
        members.deleteById(new ConversationMember.Id(conversationId, targetUserId));
        conversation.touch(Instant.now());
        ConversationResponse body = response(conversation);
        publishRemoved(conversationId, targetUserId);
        publishActive(conversationId, body);
        return userId.equals(targetUserId) ? Optional.empty() : Optional.of(body);
    }

    private void dissolve(Conversation conversation) {
        Integer conversationId = conversation.getId();
        List<ConversationMember> current = members.findByConversationId(conversationId);
        List<Integer> recipientIds = current.stream().map(member -> member.getUser().getId()).toList();
        String avatarKey = publicUrls.objectKeyFrom(conversation.getAvatarUrl());
        List<String> attachmentKeys = messages.findByConversationId(conversationId).stream()
                .map(Message::getAttachments)
                .flatMap(List::stream)
                .map(MessageAttachment::objectKey)
                .toList();
        members.deleteAll(current);
        conversations.delete(conversation);
        ConversationUpdatedPayload payload = new ConversationUpdatedPayload(
                conversationId, ConversationUpdatedPayload.Membership.DISSOLVED, null);
        recipientIds.forEach(id -> realtime.publishAfterCommit(id, "CONVERSATION_UPDATED", payload));
        if (avatarKey != null) {
            deleteAfterCommit(avatarKey);
        }
        attachmentKeys.forEach(this::deleteAfterCommit);
    }

    private void publishActive(Integer conversationId, ConversationResponse body) {
        ConversationUpdatedPayload payload = new ConversationUpdatedPayload(
                conversationId, ConversationUpdatedPayload.Membership.ACTIVE, body);
        body.members().forEach(member ->
                realtime.publishAfterCommit(member.userId(), "CONVERSATION_UPDATED", payload));
    }

    private void publishRemoved(Integer conversationId, Integer userId) {
        realtime.publishAfterCommit(userId, "CONVERSATION_UPDATED",
                new ConversationUpdatedPayload(conversationId, ConversationUpdatedPayload.Membership.REMOVED, null));
    }

    private ListAssembly assembleList(Integer userId, String cursor, int limit) {
        int size = Math.clamp(limit, 1, 50);
        List<Conversation> values;
        if (cursor == null || cursor.isBlank()) {
            values = conversations.findForUser(userId, PageRequest.of(0, size + 1));
        } else {
            Cursor decoded = decode(cursor);
            values = conversations.findForUserBefore(userId, decoded.updatedAt(), decoded.id(), PageRequest.of(0, size + 1));
        }
        boolean more = values.size() > size;
        if (more) {
            values = List.copyOf(values.subList(0, size));
        }
        if (values.isEmpty()) {
            return new ListAssembly(List.of(), null, false, Set.of());
        }

        List<Integer> conversationIds = values.stream().map(Conversation::getId).toList();
        Map<Integer, List<ConversationMember>> membersByConversation = members.findByConversationIdIn(conversationIds)
                .stream()
                .collect(Collectors.groupingBy(member -> member.getId().getConversationId()));
        Map<Integer, Message> latestByConversation = latestMessagesByConversation(conversationIds);
        Set<Integer> friendIds = new HashSet<>(friendships.findFriendIds(userId, Friendship.Status.ACCEPTED));

        Set<Integer> peerIds = new HashSet<>();
        List<PendingSummary> pending = new ArrayList<>(values.size());
        for (Conversation conversation : values) {
            List<ConversationMember> conversationMembers =
                    membersByConversation.getOrDefault(conversation.getId(), List.of());
            PublicUser other = null;
            Integer friendPeerId = null;
            if (conversation.getType() == Conversation.Type.DIRECT) {
                other = conversationMembers.stream()
                        .map(ConversationMember::getUser)
                        .filter(user -> !user.getId().equals(userId))
                        .findFirst()
                        .map(PublicUser::from)
                        .orElse(null);
                if (other != null && friendIds.contains(other.id())) {
                    friendPeerId = other.id();
                    peerIds.add(other.id());
                }
            }
            String avatarUrl = conversation.getType() == Conversation.Type.GROUP ? conversation.getAvatarUrl() : null;
            Message latest = latestByConversation.get(conversation.getId());
            ConversationMember viewer = conversationMembers.stream()
                    .filter(member -> member.getUser().getId().equals(userId))
                    .findFirst()
                    .orElse(null);
            pending.add(new PendingSummary(
                    conversation.getId(),
                    conversation.getType(),
                    conversation.getName(),
                    avatarUrl,
                    other,
                    latest == null ? null : LatestMessage.from(latest),
                    hasUnread(latest, viewer),
                    conversation.getUpdatedAt(),
                    friendPeerId));
        }
        String next = more ? encode(values.getLast()) : null;
        return new ListAssembly(pending, next, more, peerIds);
    }

    private Map<Integer, Message> latestMessagesByConversation(Collection<Integer> conversationIds) {
        List<Long> latestIds = messages.findLatestMessageIds(conversationIds);
        if (latestIds.isEmpty()) {
            return Map.of();
        }
        return messages.findAllWithSenderByIdIn(latestIds).stream()
                .collect(Collectors.toMap(message -> message.getConversation().getId(), Function.identity()));
    }

    /**
     * True when the latest message is strictly after the viewer's last-read cursor.
     * Own latest messages do not count as unread.
     */
    private static boolean hasUnread(Message latest, ConversationMember viewer) {
        if (latest == null || viewer == null) {
            return false;
        }
        if (latest.getSender().getId().equals(viewer.getUser().getId())) {
            return false;
        }
        Long lastReadId = viewer.getLastReadMessageId();
        Instant lastReadAt = viewer.getLastReadAt();
        if (lastReadId == null || lastReadAt == null) {
            return true;
        }
        return latest.getCreatedAt().isAfter(lastReadAt)
                || (latest.getCreatedAt().equals(lastReadAt) && latest.getId() > lastReadId);
    }

    private ConversationResponse response(Conversation conversation) {
        return ConversationResponse.from(conversation, members.findByConversationId(conversation.getId()));
    }

    private Conversation memberConversation(Integer userId, Integer conversationId) {
        if (!members.existsByConversationIdAndUserId(conversationId, userId)) {
            throw error(HttpStatus.FORBIDDEN, "You are not a member of this conversation");
        }
        return conversations.findById(conversationId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    private Conversation lockedMemberConversation(Integer userId, Integer conversationId) {
        if (!members.existsByConversationIdAndUserId(conversationId, userId)) {
            if (!conversations.existsById(conversationId)) {
                throw error(HttpStatus.NOT_FOUND, "Conversation not found");
            }
            throw error(HttpStatus.FORBIDDEN, "You are not a member of this conversation");
        }
        return conversations.findByIdForUpdate(conversationId)
                .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "Conversation not found"));
    }

    private void addMember(Conversation conversation, User user) {
        members.save(new ConversationMember(conversation, user));
    }

    private User user(Integer id) {
        return users.findById(id).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "User not found"));
    }

    private void requireFriends(Integer first, Integer second) {
        if (!areFriends(first, second)) {
            throw error(HttpStatus.FORBIDDEN, "Only accepted friends can be in conversations");
        }
    }

    private boolean areFriends(Integer first, Integer second) {
        return friendships.findBetween(first, second).stream()
                .anyMatch(friendship -> friendship.getStatus() == Friendship.Status.ACCEPTED);
    }

    private String normalizeGroupName(String raw) {
        String name = raw.trim();
        if (name.isEmpty() || name.length() > 100 || CONTROL.matcher(name).find()) {
            throw error(HttpStatus.BAD_REQUEST, "Group name must be 1 to 100 characters without control characters");
        }
        return name;
    }

    private void assertGroupAvatarKey(Integer conversationId, String key) {
        var matcher = GROUP_AVATAR_KEY.matcher(key);
        if (!matcher.matches() || !String.valueOf(conversationId).equals(matcher.group(1))) {
            throw error(HttpStatus.BAD_REQUEST, "Avatar object key is invalid");
        }
    }

    private void deleteAfterCommit(String objectKey) {
        Runnable delete = () -> {
            try {
                storage.delete(objectKey);
            } catch (RuntimeException ignored) {
                // Best-effort cleanup; the new avatar URL is already durable.
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete.run();
                }
            });
        } else {
            delete.run();
        }
    }

    private String directKey(Integer first, Integer second) {
        return first < second ? first + ":" + second : second + ":" + first;
    }

    private String encode(Conversation conversation) {
        String value = conversation.getUpdatedAt() + "|" + conversation.getId();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private Cursor decode(String cursor) {
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", 2);
            return new Cursor(Instant.parse(parts[0]), Integer.parseInt(parts[1]));
        } catch (RuntimeException exception) {
            throw error(HttpStatus.BAD_REQUEST, "Invalid conversation cursor");
        }
    }

    private ResponseStatusException error(HttpStatus status, String message) {
        return new ResponseStatusException(status, message);
    }

    private record Cursor(Instant updatedAt, Integer id) {}

    private record PendingSummary(
            Integer id,
            Conversation.Type type,
            String name,
            String avatarUrl,
            PublicUser otherUser,
            LatestMessage latestMessage,
            boolean hasUnread,
            Instant updatedAt,
            Integer friendPeerId) {}

    private record ListAssembly(
            List<PendingSummary> items,
            String nextCursor,
            boolean hasMore,
            Set<Integer> peerIds) {
        Page<Summary> toPage(Set<Integer> onlinePeers) {
            List<Summary> summaries = items.stream()
                    .map(item -> new Summary(
                            item.id(),
                            item.type(),
                            item.name(),
                            item.avatarUrl(),
                            item.otherUser(),
                            item.latestMessage(),
                            item.hasUnread(),
                            item.updatedAt(),
                            item.friendPeerId() != null && onlinePeers.contains(item.friendPeerId())))
                    .toList();
            return new Page<>(summaries, nextCursor, hasMore);
        }
    }
}
