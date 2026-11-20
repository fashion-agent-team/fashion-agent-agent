package com.fashionagent.agent.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 대화 상태 보관(단위 테스트). 앱 레포 specs/005-database/spec.md US3 "대화 중 상태는 잠깐만 들고 있는다"(30분)를 확인합니다.
 * 30분은 AgentProperties.sessionTtl 기본값(application.yml agent.session-ttl)과 같습니다.
 * US3-2(사진 10분 뒤 가족에게 묻기)는 가족 확인(helper)을 최종에서 뺐기 때문에 확인할 기능이 없습니다.
 */
class InMemorySessionStoreTest {

    static final Duration TTL = Duration.ofMinutes(30);
    final Instant start = Instant.parse("2026-11-20T01:00:00Z");

    /** 정해 둔 시각을 돌려주는 시계. at(분)으로 옮깁니다. */
    static final class StepClock extends Clock {
        Instant now;

        StepClock(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static AgentSession firstStep(String userId) {
        AgentSession s = new AgentSession();
        s.userId = userId;
        s.sessionId = "s-1";
        s.spokenOccasion = "면접";
        return s;
    }

    @Test
    @DisplayName("005 US3-1 Given 1단계를 마친 세션 When 10분 뒤 옷을 보여 주면 Then 1단계 내용(자리 등)이 그대로 남아 있다")
    void 십분_뒤에도_1단계를_기억한다() {
        // given
        StepClock clock = new StepClock(start);
        InMemorySessionStore store = new InMemorySessionStore(TTL, clock);
        store.save(firstStep("u-1"));

        // when
        clock.now = start.plus(Duration.ofMinutes(10));

        // then
        assertThat(store.find("u-1", "s-1")).get().extracting(s -> s.spokenOccasion).isEqualTo("면접");
    }

    @Test
    @DisplayName("005 US3 Given 1단계를 마친 세션 When 31분 동안 말이 없으면 Then 잊고 새 세션으로 시작한다")
    void 삼십분_넘게_말이_없으면_잊는다() {
        // given
        StepClock clock = new StepClock(start);
        InMemorySessionStore store = new InMemorySessionStore(TTL, clock);
        store.save(firstStep("u-1"));

        // when
        clock.now = start.plus(Duration.ofMinutes(31));

        // then
        assertThat(store.find("u-1", "s-1")).isEmpty();
    }

    @Test
    @DisplayName("005 US3 Given 25분 뒤에 한 번 더 말함 When 다시 25분이 지나면 Then 마지막 말부터 30분이라 아직 기억한다")
    void 말할_때마다_30분이_다시_시작된다() {
        // given
        StepClock clock = new StepClock(start);
        InMemorySessionStore store = new InMemorySessionStore(TTL, clock);
        store.save(firstStep("u-1"));
        clock.now = start.plus(Duration.ofMinutes(25));
        store.save(store.find("u-1", "s-1").orElseThrow());

        // when
        clock.now = start.plus(Duration.ofMinutes(50));

        // then
        assertThat(store.find("u-1", "s-1")).isPresent();
    }

    @Test
    @DisplayName("005 US2-3 Given 사용자 A의 세션 When 사용자 B가 같은 sessionId로 찾으면 Then 보이지 않는다")
    void 다른_사람_세션은_보이지_않는다() {
        // given
        InMemorySessionStore store = new InMemorySessionStore(TTL, new StepClock(start));
        store.save(firstStep("u-a"));

        // when / then
        assertThat(store.find("u-b", "s-1")).isEmpty();
    }
}
