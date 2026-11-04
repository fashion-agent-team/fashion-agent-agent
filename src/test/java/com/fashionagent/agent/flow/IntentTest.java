package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IntentTest {

    @Test
    void 옷_추천() {
        assertThat(Intent.of("오늘 뭐 입지?", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("옷 추천해 줘", false)).isEqualTo(Intent.OUTFIT);
    }

    @Test
    void 마무리와_어디야는_옷_추천_중일_때만() {
        assertThat(Intent.of("이걸로 할게", true)).isEqualTo(Intent.FINISH);
        assertThat(Intent.of("이걸로 할게", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("얼룩 어디야?", true)).isEqualTo(Intent.WHERE);
    }

    @Test
    void 개별_기능() {
        assertThat(Intent.of("오늘 날씨 어때", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("오늘 일정 뭐야", false)).isEqualTo(Intent.SCHEDULE);
        assertThat(Intent.of("얼굴 확인해 줘", false)).isEqualTo(Intent.FACE);
    }

    @Test
    void 일정_등록_삭제_다른_날과_가족에게_묻기는_Gemini로() {
        assertThat(Intent.of("토요일 두 시에 결혼식 넣어 줘", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("내일 일정 뭐야", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("면접 일정 지워 줘", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("이 옷 괜찮은지 엄마한테 물어봐 줘", true)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("엄마 답 왔어?", false)).isEqualTo(Intent.OTHER);
    }
}
