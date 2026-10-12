package com.sender.backend.presence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RedisPresenceSessionStore implements PresenceSessionStore {
    private static final Logger log = LoggerFactory.getLogger(RedisPresenceSessionStore.class);
    private static final String ONLINE_USERS_KEY = "presence:online-users";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public RedisPresenceSessionStore(StringRedisTemplate redis, Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    @Override
    public boolean addSession(Integer userId, String sessionId) {
        try {
            boolean wasOnline = isListedOnline(userId);
            redis.opsForValue().set(sessionKey(sessionId), userId.toString(), ttl);
            redis.opsForSet().add(userSessionsKey(userId), sessionId);
            redis.expire(userSessionsKey(userId), ttl.multipliedBy(2));
            if (!wasOnline) {
                redis.opsForSet().add(ONLINE_USERS_KEY, userId.toString());
                return true;
            }
            return false;
        } catch (RuntimeException exception) {
            log.warn("Presence registry unavailable on addSession", exception);
            return false;
        }
    }

    @Override
    public boolean refreshSession(Integer userId, String sessionId) {
        try {
            String owner = redis.opsForValue().get(sessionKey(sessionId));
            if (owner != null && owner.equals(userId.toString())) {
                redis.expire(sessionKey(sessionId), ttl);
                redis.opsForSet().add(userSessionsKey(userId), sessionId);
                redis.expire(userSessionsKey(userId), ttl.multipliedBy(2));
                return false;
            }
            return addSession(userId, sessionId);
        } catch (RuntimeException exception) {
            log.warn("Presence registry unavailable on refreshSession", exception);
            return false;
        }
    }

    @Override
    public Optional<Integer> removeSession(String sessionId) {
        try {
            String owner = redis.opsForValue().getAndDelete(sessionKey(sessionId));
            if (owner == null) {
                return Optional.empty();
            }
            Integer userId = Integer.valueOf(owner);
            redis.opsForSet().remove(userSessionsKey(userId), sessionId);
            pruneDeadSessions(userId);
            if (!hasLiveSession(userId)) {
                redis.delete(userSessionsKey(userId));
                redis.opsForSet().remove(ONLINE_USERS_KEY, userId.toString());
                return Optional.of(userId);
            }
            return Optional.empty();
        } catch (RuntimeException exception) {
            log.warn("Presence registry unavailable on removeSession", exception);
            return Optional.empty();
        }
    }

    @Override
    public boolean isOnline(Integer userId) {
        try {
            return isListedOnline(userId);
        } catch (RuntimeException exception) {
            log.warn("Presence registry unavailable on isOnline", exception);
            return false;
        }
    }

    /**
     * Batch membership against {@code presence:online-users} (one Redis round-trip).
     * Stale entries are cleared by disconnect handling and the periodic sweep — not on the read path.
     */
    @Override
    public Set<Integer> onlineAmong(Collection<Integer> userIds) {
        if (userIds == null || userIds.isEmpty()) {
            return Set.of();
        }
        try {
            Set<Integer> distinct = userIds instanceof Set<Integer> set
                    ? set
                    : new LinkedHashSet<>(userIds);
            if (distinct.isEmpty()) {
                return Set.of();
            }
            Object[] members = distinct.stream().map(Object::toString).toArray();
            Map<Object, Boolean> flags = redis.opsForSet().isMember(ONLINE_USERS_KEY, members);
            if (flags == null || flags.isEmpty()) {
                return Set.of();
            }
            Set<Integer> online = new HashSet<>();
            for (Integer userId : distinct) {
                if (Boolean.TRUE.equals(flags.get(userId.toString()))) {
                    online.add(userId);
                }
            }
            return online;
        } catch (RuntimeException exception) {
            log.warn("Presence registry unavailable on onlineAmong", exception);
            return Set.of();
        }
    }

    @Override
    public Set<Integer> sweepExpired() {
        try {
            Set<String> tracked = redis.opsForSet().members(ONLINE_USERS_KEY);
            if (tracked == null || tracked.isEmpty()) {
                return Set.of();
            }
            Set<Integer> becameOffline = new HashSet<>();
            for (String raw : tracked) {
                Integer userId = Integer.valueOf(raw);
                pruneDeadSessions(userId);
                if (!hasLiveSession(userId)) {
                    redis.opsForSet().remove(ONLINE_USERS_KEY, raw);
                    redis.delete(userSessionsKey(userId));
                    becameOffline.add(userId);
                }
            }
            return becameOffline;
        } catch (RuntimeException exception) {
            log.warn("Presence registry unavailable on sweepExpired", exception);
            return Set.of();
        }
    }

    private boolean isListedOnline(Integer userId) {
        return Boolean.TRUE.equals(redis.opsForSet().isMember(ONLINE_USERS_KEY, userId.toString()));
    }

    private boolean hasLiveSession(Integer userId) {
        Long size = redis.opsForSet().size(userSessionsKey(userId));
        return size != null && size > 0;
    }

    private void pruneDeadSessions(Integer userId) {
        Set<String> sessionIds = redis.opsForSet().members(userSessionsKey(userId));
        if (sessionIds == null || sessionIds.isEmpty()) {
            return;
        }
        for (String sessionId : sessionIds) {
            Boolean exists = redis.hasKey(sessionKey(sessionId));
            if (exists == null || !exists) {
                redis.opsForSet().remove(userSessionsKey(userId), sessionId);
            }
        }
    }

    private static String sessionKey(String sessionId) {
        return "presence:session:" + sessionId;
    }

    private static String userSessionsKey(Integer userId) {
        return "presence:user:" + userId + ":sessions";
    }
}
