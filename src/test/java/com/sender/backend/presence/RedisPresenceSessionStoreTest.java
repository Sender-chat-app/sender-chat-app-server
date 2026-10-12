package com.sender.backend.presence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisPresenceSessionStoreTest {
    private static final String ONLINE_USERS_KEY = "presence:online-users";

    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> values;
    @Mock SetOperations<String, String> sets;

    private RedisPresenceSessionStore store;

    @BeforeEach
    void setUp() {
        store = new RedisPresenceSessionStore(redis, Duration.ofSeconds(90));
    }

    @Test
    void onlineAmongUsesSingleSmismemberRoundTrip() {
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.isMember(ONLINE_USERS_KEY, "10", "11", "12"))
                .thenReturn(Map.of("10", true, "11", false, "12", true));

        assertEquals(Set.of(10, 12), store.onlineAmong(List.of(10, 11, 12)));

        verify(sets).isMember(ONLINE_USERS_KEY, "10", "11", "12");
    }

    @Test
    void onlineAmongReturnsEmptyForNoPeers() {
        assertTrue(store.onlineAmong(List.of()).isEmpty());
    }

    @Test
    void addSessionSkipsOnlineRegistryWhenAlreadyListed() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.opsForSet()).thenReturn(sets);
        when(sets.isMember(ONLINE_USERS_KEY, "10")).thenReturn(true);

        assertEquals(false, store.addSession(10, "s1"));

        verify(sets).add("presence:user:10:sessions", "s1");
        verify(sets).isMember(ONLINE_USERS_KEY, "10");
    }
}
