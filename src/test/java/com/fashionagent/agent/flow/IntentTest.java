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
    void 무슨_색이냐는_색_확인() {
        assertThat(Intent.of("이거 무슨 색이야?", false)).isEqualTo(Intent.COLOR);
        assertThat(Intent.of("이 옷 색깔 뭐야", false)).isEqualTo(Intent.COLOR);
        assertThat(Intent.of("어떤 색이야?", true)).isEqualTo(Intent.COLOR);
        // 무엇을 입을지는 추천, 옷장 이야기는 옷장
        assertThat(Intent.of("내일 무슨 색 입을까?", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("옷장에 무슨 색 옷 있어?", false)).isEqualTo(Intent.CLOSET);
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
        assertThat(Intent.of("내려놓고", true)).isEqualTo(Intent.MODE);   // 2026-11-23: 짧게 방식만
        assertThat(Intent.of("입고 볼게", false)).isNotEqualTo(Intent.MODE);
        assertThat(Intent.of("입고 볼게", false)).isEqualTo(Intent.CHECK);   // 2026-11-23: 처음부터 방식만 말해도 옷 보기
        assertThat(Intent.of("흰 셔츠 입고 갈게", false)).isNotEqualTo(Intent.CHECK);
        assertThat(Intent.of("내려놓고 볼게, 내일 뭐 입지?", true)).isEqualTo(Intent.OUTFIT);   // 2026-11-20: 새 추천
    }

    @Test
    void 옷_검사는_바로_확인으로_간다_리허설() {
        assertThat(Intent.of("옷 검사해 줘", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("이 옷 어때", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("이 옷 어때?", true)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("얼굴 어때", false)).isNotEqualTo(Intent.CHECK);
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
        // 2026-11-23 점검: "입은 옷"이 옷 검사로 가서 카메라가 켜졌음
        assertThat(Intent.of("어제 입은 옷 뭐야?", false)).isEqualTo(Intent.CLOSET_HISTORY);
        assertThat(Intent.of("그저께 입은 거 알려 줘", false)).isEqualTo(Intent.CLOSET_HISTORY);
        assertThat(Intent.of("지난주에 입은 옷 다시 입을까?", false)).isNotEqualTo(Intent.CLOSET_HISTORY);
        assertThat(Intent.of("옷장에서 원피스 지워줘", false)).isEqualTo(Intent.CLOSET_REMOVE);
        assertThat(Intent.of("옷장에서 버린 바지 빼 줘", false)).isEqualTo(Intent.CLOSET_REMOVE);
        assertThat(Intent.of("옷장 비워 줘", false)).isEqualTo(Intent.CLOSET_REMOVE);
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
        // 2026-11-12 진행 님 폰: "없애"는 일정 지우기인데 오늘 일정 읽기로 갔습니다.
        assertThat(Intent.of("일정 없애 줘", true)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("내일 일정 없애줘", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("일정 지우고 싶어", false)).isEqualTo(Intent.OTHER);
    }

    @Test
    void 자주_하는_말이_키워드에_걸린다_2026_11_23_시뮬() {
        assertThat(Intent.of("얼룩 있는지 봐줘", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("셔츠에 뭐 묻었는지 봐줘", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("입술에 뭐 묻었어?", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("이에 뭐 묻었어?", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("립스틱 번졌어?", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("눈썹 짝짝이야?", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("파란색이야?", false)).isEqualTo(Intent.COLOR);
        assertThat(Intent.of("오늘 약속 있어?", false)).isEqualTo(Intent.SCHEDULE);
        assertThat(Intent.of("약속 잡아 줘", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("패딩 입어야 돼?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 뭐 입어야 해?", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("지금 몇 도야?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("오늘 반팔 입어도 돼?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("반팔 뭐 입지?", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("목도리 해야 돼?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("장갑 챙길까?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("장갑은 안 껴도 돼?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("바지 몇 벌 있어?", false)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("자켓 있어?", false)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("니트 몇 벌이야?", false)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("내일 몇 시에 나가야 돼?", false)).isEqualTo(Intent.SCHEDULE);
        assertThat(Intent.of("12월 25일에 뭐 있어?", false)).isEqualTo(Intent.SCHEDULE);
        assertThat(Intent.of("치마 입어도 돼?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("청바지 괜찮아?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("니트 입어도 될까?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("치마 입어도 돼?", true)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("장화 신어야 돼?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("목도리도?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 비 온대?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("옷 좀 골라줘", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("따뜻하게 입고 싶어", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("오늘 영하야?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("코트 빼줘", false)).isEqualTo(Intent.CLOSET_REMOVE);
        assertThat(Intent.of("립 색깔 괜찮아?", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("패딩만 보여줘", false)).isEqualTo(Intent.CLOSET);
        assertThat(Intent.of("바람막이 챙길까?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("겉옷 챙길까?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("제일 많이 입은 옷 뭐야?", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("양말 무슨 색 신을까?", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("신발은 뭐 신지?", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("운동화 신을까?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("토요일에 눈 온다던데", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("운동화 신어도 돼?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("오늘 겉옷 필요 없지?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("반팔 입을까?", true)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("토요일 비", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("오늘이랑 내일 날씨 비교해줘", false)).isEqualTo(Intent.OTHER);
        assertThat(Intent.of("청바지에 니트 어때?", true)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 눈?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("눈", false)).isNotEqualTo(Intent.WEATHER);
        assertThat(Intent.of("추천 다시 해줘", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("이 닦았는데 봐줘", false)).isEqualTo(Intent.FACE);
        assertThat(Intent.of("치마 입을까?", true)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 무슨 바지 입을까?", false)).isEqualTo(Intent.OUTFIT);
        assertThat(Intent.of("이거 어때?", false)).isEqualTo(Intent.CHECK);
        assertThat(Intent.of("우비 필요해?", false)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("검정 정장 괜찮아?", true)).isEqualTo(Intent.WEATHER);
        assertThat(Intent.of("내일 뭐 있어?", false)).isEqualTo(Intent.SCHEDULE);
        assertThat(Intent.of("내일 겉옷 있어야 돼?", false)).isNotEqualTo(Intent.CLOSET);
        assertThat(Intent.of("바지에 얼룩 있어?", false)).isEqualTo(Intent.CHECK);
    }
}
