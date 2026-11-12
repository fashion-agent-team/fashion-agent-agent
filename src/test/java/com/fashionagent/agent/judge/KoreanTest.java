package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KoreanTest {

    @Test
    void 조사() {
        assertThat(Korean.josa("셔츠", "이", "가")).isEqualTo("셔츠가");
        assertThat(Korean.josa("패딩", "을", "를")).isEqualTo("패딩을");
        // 2026-11-12 시뮬: ㄹ 받침 뒤 '으로' → '로'
        assertThat(Korean.josa("갈색 신발", "으로", "로")).isEqualTo("갈색 신발로");
        assertThat(Korean.josa("검정 바지", "으로", "로")).isEqualTo("검정 바지로");
        assertThat(Korean.josa("흰색 원피스", "으로", "로")).isEqualTo("흰색 원피스로");
        assertThat(Korean.josa("어두운 파랑 코트, 검정 셔츠, 청색 청바지", "으로", "로")).endsWith("청바지로");
        assertThat(Korean.josa("검정 패딩", "으로", "로")).isEqualTo("검정 패딩으로");
        assertThat(Korean.josa("오늘", "은", "는")).isEqualTo("오늘은");
        assertThat(Korean.josa("신발", "은", "는")).isEqualTo("신발은");
    }
}
