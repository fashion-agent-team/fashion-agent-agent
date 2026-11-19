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

    @org.junit.jupiter.api.Test
    void 읽을_문장의_종류_코드는_한국어로() {
        org.assertj.core.api.Assertions.assertThat(Korean.spoken("상의는 아직 보이지 않아서 top도 같이 보면 더 좋아요."))
                .isEqualTo("상의는 아직 보이지 않아서 상의도 같이 보면 더 좋아요.");
        org.assertj.core.api.Assertions.assertThat(Korean.spoken("Bottom과 outer를 보여 주세요")).isEqualTo("하의와 겉옷을 보여 주세요");
        org.assertj.core.api.Assertions.assertThat(Korean.spoken("stopwatch tops")).isEqualTo("stopwatch tops");
    }
}
