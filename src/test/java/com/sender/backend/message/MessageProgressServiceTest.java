package com.sender.backend.message;

import com.sender.backend.conversation.Conversation;
import com.sender.backend.conversation.ConversationMember;
import com.sender.backend.conversation.ConversationMemberRepository;
import com.sender.backend.conversation.ConversationRepository;
import com.sender.backend.message.MessageProgressDtos.ProgressRequest;
import com.sender.backend.message.MessageProgressDtos.ProgressResponse;
import com.sender.backend.realtime.RealtimePublisher;
import com.sender.backend.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageProgressServiceTest {
	@Mock ConversationRepository conversations;
	@Mock ConversationMemberRepository members;
	@Mock MessageRepository messages;
	@Mock RealtimePublisher realtime;

	private MessageProgressService progress;

	@BeforeEach
	void setUp() {
		progress = new MessageProgressService(conversations, members, messages, realtime);
	}

	@Test
	void acceptReadReportsHasUnreadFromLatestVersusReadCursor() throws Exception {
		Conversation conversation = Conversation.direct("1:2");
		setConversationId(conversation, 10);
		User alice = user(1, "alice");
		User bob = user(2, "bob");
		ConversationMember membership = new ConversationMember(conversation, alice);
		Instant readAt = membership.getJoinedAt().plusSeconds(10);
		setReadCursor(membership, 5L, readAt);
		Message readThrough = new Message(conversation, bob, "older", UUID.randomUUID(), List.of());
		setMessageId(readThrough, 5L);
		setMessageCreatedAt(readThrough, readAt);
		Message latest = new Message(conversation, bob, "newer", UUID.randomUUID(), List.of());
		setMessageId(latest, 9L);
		setMessageCreatedAt(latest, readAt.plusSeconds(60));

		when(conversations.existsById(10)).thenReturn(true);
		when(members.findMembership(10, 1)).thenReturn(Optional.of(membership));
		when(messages.findById(5L)).thenReturn(Optional.of(readThrough));
		when(messages.findTopByConversationIdOrderByCreatedAtDescIdDesc(10)).thenReturn(Optional.of(latest));

		ProgressResponse response = progress.acceptRead(1, 10, new ProgressRequest(5L));

		assertTrue(response.hasUnread());
	}

	@Test
	void acceptReadClearsHasUnreadWhenLatestMatchesReadCursor() throws Exception {
		Conversation conversation = Conversation.direct("1:2");
		setConversationId(conversation, 10);
		User alice = user(1, "alice");
		User bob = user(2, "bob");
		ConversationMember membership = new ConversationMember(conversation, alice);
		Message latest = new Message(conversation, bob, "latest", UUID.randomUUID(), List.of());
		setMessageId(latest, 9L);
		setMessageCreatedAt(latest, membership.getJoinedAt().plusSeconds(30));

		when(conversations.existsById(10)).thenReturn(true);
		when(members.findMembership(10, 1)).thenReturn(Optional.of(membership));
		when(messages.findById(9L)).thenReturn(Optional.of(latest));
		when(messages.findTopByConversationIdOrderByCreatedAtDescIdDesc(10)).thenReturn(Optional.of(latest));
		when(messages.findSenderIdsThrough(10, 9L)).thenReturn(List.of(2));

		ProgressResponse response = progress.acceptRead(1, 10, new ProgressRequest(9L));

		assertFalse(response.hasUnread());
	}

	private static User user(int id, String username) throws Exception {
		User user = new User(username, username + "@example.com", "hash");
		Field field = User.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(user, id);
		return user;
	}

	private static void setConversationId(Conversation conversation, int id) throws Exception {
		Field field = Conversation.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(conversation, id);
	}

	private static void setMessageId(Message message, long id) throws Exception {
		Field field = Message.class.getDeclaredField("id");
		field.setAccessible(true);
		field.set(message, id);
	}

	private static void setMessageCreatedAt(Message message, Instant createdAt) throws Exception {
		Field field = Message.class.getDeclaredField("createdAt");
		field.setAccessible(true);
		field.set(message, createdAt);
	}

	private static void setReadCursor(ConversationMember member, Long messageId, Instant at) throws Exception {
		Field idField = ConversationMember.class.getDeclaredField("lastReadMessageId");
		idField.setAccessible(true);
		idField.set(member, messageId);
		Field atField = ConversationMember.class.getDeclaredField("lastReadAt");
		atField.setAccessible(true);
		atField.set(member, at);
	}
}
