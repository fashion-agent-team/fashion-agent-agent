package com.fashionagent.agent.session;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fashionagent.agent.config.AgentProperties;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class SessionConfig {

    @Bean
    public Clock clock() {
        return Clock.systemDefaultZone();
    }

    @Bean
    public SessionStore sessionStore(AgentProperties props, ObjectProvider<StringRedisTemplate> redis,
            JsonMapper json, Clock clock) {
        if ("redis".equals(props.session())) {
            return new RedisSessionStore(redis.getObject(), json, props.sessionTtl());
        }
        return new InMemorySessionStore(props.sessionTtl(), clock);
    }
}
