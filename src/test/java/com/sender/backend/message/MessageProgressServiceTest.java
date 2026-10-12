package com.sender.backend.message;

import com.sender.backend.conversation.Conversation;
import com.sender.backend.conversation.ConversationMember;
import com.sender.backend.conversation.ConversationMemberRepository;
import com.sender.backend.conversation.ConversationRepository;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
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
	void unreadCountsBatchesMembershipsAndOverlaysPendingRead() throws Exception {
		Conversation first = Conversation.direct("1:2");
		setConversationId(first, 10);
		Conversation second = Conversation.direct("1:3");
		setConversationId(second, 11);
		User alice = user(1, "alice");
		ConversationMember membership10 = new ConversationMember(first, alice);
		ConversationMember membership11 = new ConversationMember(second, alice);
		setReadCursor(membership10, 5L, Instant.parse("2026-10-08T00:00:00Z"));

		when(members.findMemberships(List.of(10, 11), 1)).thenReturn(List.of(membership10, membership11));
		when(messages.countUnreadAfter(eq(10), eq(1), any(), eq(5L))).thenReturn(2L);
		when(messages.countUnread(11, 1)).thenReturn(4L);

		Map<Integer, Long> counts = progress.unreadCounts(1, List.of(10, 11));

		assertEquals(Map.of(10, 2L, 11, 4L), counts);
		verify(members).findMemberships(List.of(10, 11), 1);
	}

	@Test
	void unreadCountsReturnsZerosForEmptyInput() {
		assertEquals(Map.of(), progress.unreadCounts(1, List.of()));
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

	private static void setReadCursor(ConversationMember member, Long messageId, Instant at) throws Exception {
		Field idField = ConversationMember.class.getDeclaredField("lastReadMessageId");
		idField.setAccessible(true);
		idField.set(member, messageId);
		Field atField = ConversationMember.class.getDeclaredField("lastReadAt");
		atField.setAccessible(true);
		atField.set(member, at);
	}
}
