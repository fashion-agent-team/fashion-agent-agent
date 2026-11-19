package com.fashionagent.agent.session;

import java.time.Duration;
import java.util.Optional;

import org.springframework.data.redis.core.StringRedisTemplate;

import tools.jackson.databind.json.JsonMapper;

/** 배포용 저장소. JSON으로 넣고 30분 뒤 지워집니다. */
public class RedisSessionStore implements SessionStore {

    private final StringRedisTemplate redis;
    private final JsonMapper json;
    private final Duration ttl;

    public RedisSessionStore(StringRedisTemplate redis, JsonMapper json, Duration ttl) {
        this.redis = redis;
        this.json = json;
        this.ttl = ttl;
    }

    @Override
    public Optional<AgentSession> find(String userId, String sessionId) {
        String value = redis.opsForValue().get(SessionStore.key(userId, sessionId));
        return Optional.ofNullable(value).map(v -> json.readValue(v, AgentSession.class));
    }

    @Override
    public void save(AgentSession session) {
        redis.opsForValue().set(SessionStore.key(session.userId, session.sessionId),
                json.writeValueAsString(session), ttl);
    }
}
