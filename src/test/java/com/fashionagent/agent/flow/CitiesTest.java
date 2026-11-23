package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CitiesTest {

    @Test
    void 말한_도시를_찾는다() {
        assertThat(Cities.in("부산 날씨 어때?").name()).isEqualTo("부산");
        assertThat(Cities.in("제주도 비 와?").name()).isEqualTo("제주");
        assertThat(Cities.in("서귀포 내일 날씨").name()).isEqualTo("서귀포");
        assertThat(Cities.in("오늘 날씨 어때?")).isNull();
        assertThat(Cities.in(null)).isNull();
    }
}
