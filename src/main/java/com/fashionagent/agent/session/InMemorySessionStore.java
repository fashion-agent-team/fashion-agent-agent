package com.fashionagent.agent.session;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 서버 한 대로 개발할 때 쓰는 저장소. 재시작하면 사라집니다. */
public class InMemorySessionStore implements SessionStore {

    private record Entry(AgentSession session, Instant expiresAt) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public InMemorySessionStore(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public Optional<AgentSession> find(String userId, String sessionId) {
        Instant now = clock.instant();
        entries.values().removeIf(e -> e.expiresAt().isBefore(now));
        return Optional.ofNullable(entries.get(SessionStore.key(userId, sessionId))).map(Entry::session);
    }

    @Override
    public void save(AgentSession session) {
        entries.put(SessionStore.key(session.userId, session.sessionId),
                new Entry(session, clock.instant().plus(ttl)));
    }
}
