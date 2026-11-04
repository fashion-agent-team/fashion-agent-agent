package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SpokenTimeTest {

    @Test
    void 일정_시각을_말로_읽는다() {
        assertThat(TurnService.spokenTime("2026-11-04T14:00:00+09:00")).isEqualTo("오후 2시");
        assertThat(TurnService.spokenTime("2026-11-04T09:30:00+09:00")).isEqualTo("오전 9시 30분");
        assertThat(TurnService.spokenTime("2026-11-04T00:00:00+09:00")).isEqualTo("오전 12시");
        assertThat(TurnService.spokenTime("이상한 값")).isEmpty();
    }
}
