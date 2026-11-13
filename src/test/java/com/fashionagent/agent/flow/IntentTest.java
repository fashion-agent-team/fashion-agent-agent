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
        assertThat(Intent.of("다 보여 줬어", true)).isEqualTo(Intent.SHOWN);
        assertThat(Intent.of("이게 다야", true)).isEqualTo(Intent.SHOWN);
        assertThat(Intent.of("다 보여 줬어", false)).isNotEqualTo(Intent.SHOWN);
        assertThat(Intent.of("이걸로 할게", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("얼룩 어디야?", true)).isEqualTo(Intent.WHERE);
        assertThat(Intent.of("입고 볼게", true)).isEqualTo(Intent.MODE);
        assertThat(Intent.of("내려놓고 보여줄게", true)).isEqualTo(Intent.MODE);
        assertThat(Intent.of("흰 셔츠 입고 갈게", true)).isNotEqualTo(Intent.MODE);
        assertThat(Intent.of("입고 볼게", false)).isNotEqualTo(Intent.MODE);
    }

    @Test
    void 옷_검사는_바로_확인으로_간다_리허설() {
        assertThat(Intent.of("옷 검사해 줘", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("옷검사 해 줘", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("내가 입은 옷 확인해 줘", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("얼굴 검사해 줘", false)).isEqualTo(Intent.FACE);
    }

    @Test
    void 옷장_보기_2026_11_12_폰() {
        assertThat(Intent.of("옷장 확인해줘", false)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("내 옷장에 뭐 있어?", false)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("옷장에 있는 옷 봐줘", false)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("옷장 바지 뭐 있어", true)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("옷장에서 옷 추천해 줘", false)).isEqualTo(Intent.OUTFIT);
        // 2026-11-12 시뮬 2회차
        assertThat(Intent.of("어제 뭐 입었지?", false)).isEqualTo(Intent.CLOSET_HISTORY);
        assertThat(Intent.of("지난주에 무슨 옷 입었어", false)).isEqualTo(Intent.CLOSET_HISTORY);
        assertThat(Intent.of("옷장에서 원피스 지워줘", false)).isEqualTo(Intent.CLOSET_REMOVE);
        assertThat(Intent.of("옷장에서 버린 바지 빼 줘", false)).isEqualTo(Intent.CLOSET_REMOVE);
        assertThat(Intent.of("내일 뭐 입지?", false)).isEqualTo(Intent.OUTFIT);
    }

    @Test
    void 할_줄_아는_거() {
        assertThat(Intent.of("할 줄 아는 거 말해 봐", false)).isEqualTo(Intent.HELP);
        assertThat(Intent.of("기능 설명해 줘", false)).isEqualTo(Intent.HELP);
        assertThat(Intent.of("뭐 할 수 있어?", false)).isEqualTo(Intent.HELP);
        assertThat(Intent.of("뭘 도와줄 수 있어", false)).isEqualTo(Intent.HELP);
        assertThat(Intent.of("날씨 기능 있어?", false)).isEqualTo(Intent.WEATHER);
    }

    @Test
    void 날씨라는_말이_없어도_날씨로_바로_답한다() {
        // 2026-11-12 실제 서버: "우산 챙겨야 해?"가 GPT로 가서 8초 걸렸음
        assertThat(Intent.of("우산 챙겨야 해?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("오늘 추워?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 비 와?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("지금 기온 몇 도야", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 눈 와?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("겉옷 챙겨야 할까", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 날씨 어때", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("토요일 날씨 알려 줘", false)).isEqualTo(Intent.WEATHER);
    }

    @Test
    void 날씨_단어가_있어도_다른_기능이_먼저() {
        assertThat(Intent.of("추운 날 뭐 입을까", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("눈 화장 봐 줘", false)).isEqualTo(Intent.FACE);
        // 여러 날은 GPT가 날짜별로 봄
        assertThat(Intent.of("이번 주 날씨 어때", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("준비가 다 끝났어", false)).isNotEqualTo(Intent.WEATHER);
    }

    @Test
    void 개별_기능() {
        assertThat(Intent.of("오늘 날씨 어때", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("오늘 일정 뭐야", false)).isEqualTo(Intent.SCHEDULE);
        // 하루 일정은 GPT를 거치지 않고 바로 읽습니다(2026-11-09 측정: GPT 2~4.6초).
        assertThat(Intent.of("내일 일정 뭐야", false)).isEqualTo(Intent.SCHEDULE);
        assertThat(Intent.of("다음 주 토요일 일정 알려 줘", false)).isEqualTo(Intent.SCHEDULE);
        assertThat(Intent.of("얼굴 확인해 줘", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("이 확인해 줘", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("치아에 뭐 꼈어?", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("화장 봐 줘", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("이쪽 옷 괜찮아?", false)).isNotEqualTo(Intent.FACE);
    }

    @Test
    void 일정_등록_삭제_다른_날은_GPT로() {
        assertThat(Intent.of("토요일 두 시에 결혼식 넣어 줘", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("이번 주 일정 뭐야", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("면접 일정 지워 줘", false)).isEqualTo(Intent.OTHER);
    }
}
