package com.fashionagent.agent.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import tools.jackson.databind.json.JsonMapper;

/** agent.session=redis일 때 쓰는 저장소. Redis는 Mock으로 바꾸고 키·만료 시간·JSON 왕복만 봅니다. */
class RedisSessionStoreTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private ValueOperations<String, String> values;
    private RedisSessionStore store;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        given(redis.opsForValue()).willReturn(values);
        store = new RedisSessionStore(redis, json, Duration.ofMinutes(30));
    }

    @Test
    @DisplayName("save Given 대화 When 저장하면 Then 사용자·대화 id 키에 JSON으로 30분 만료를 걸어 넣는다")
    void saveUsesKeyAndTtl() {
        // given
        AgentSession session = new AgentSession("s1", "u1");

        // when
        store.save(session);

        // then
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("agent:session:u1:s1"), value.capture(), eq(Duration.ofMinutes(30)));
        assertThat(value.getValue()).contains("\"sessionId\":\"s1\"").contains("\"userId\":\"u1\"");
    }

    @Test
    @DisplayName("find Given 저장된 JSON When 찾으면 Then 같은 대화로 돌려준다")
    void findReadsBack() {
        // given
        given(values.get("agent:session:u1:s1")).willReturn(json.writeValueAsString(new AgentSession("s1", "u1")));

        // when
        var found = store.find("u1", "s1");

        // then
        assertThat(found).hasValueSatisfying(s -> {
            assertThat(s.sessionId).isEqualTo("s1");
            assertThat(s.userId).isEqualTo("u1");
        });
    }

    @Test
    @DisplayName("find Given 없거나 만료된 대화 When 찾으면 Then 빈 값")
    void findMissing() {
        given(values.get(anyString())).willReturn(null);

        assertThat(store.find("u1", "nope")).isEmpty();
    }
}
