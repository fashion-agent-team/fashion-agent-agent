package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/** GPT 없이 규칙으로 답하는 짧은 말(인사·시간·날짜·놀림 등). 시간은 서울 기준이라 UTC 시계를 줘도 서울 시각으로 말해야 한다. */
class SmallTalkTest {

    /** 2026-11-04(수) 오후 4시 5분(서울) = 07:05 UTC. */
    private final Clock afternoon = Clock.fixed(Instant.parse("2026-11-04T07:05:00Z"), ZoneId.of("UTC"));

    @Test
    @DisplayName("Given UTC 시계 When \"지금 몇 시야?\" Then 서울 시각을 오전/오후 12시간제로 말한다")
    void timeInSeoul() {
        assertThat(SmallTalk.reply("지금 몇 시야?", afternoon)).isEqualTo("지금은 오후 4시 5분이에요.");
    }

    @Test
    @DisplayName("Given 서울 자정 0시 30분 When 시간을 물으면 Then 0시가 아니라 오전 12시로 말한다")
    void midnightIsTwelve() {
        Clock midnight = Clock.fixed(Instant.parse("2026-11-04T15:30:00Z"), ZoneId.of("UTC"));

        assertThat(SmallTalk.reply("몇시", midnight)).isEqualTo("지금은 오전 12시 30분이에요.");
    }

    @Test
    @DisplayName("Given 서울 정오 When 시간을 물으면 Then 오후 12시")
    void noonIsPm() {
        Clock noon = Clock.fixed(Instant.parse("2026-11-04T03:00:00Z"), ZoneId.of("UTC"));

        assertThat(SmallTalk.reply("시간 알려 줘", noon)).isEqualTo("지금은 오후 12시 0분이에요.");
    }

    @Test
    @DisplayName("Given UTC로는 전날 When \"오늘 며칠이야?\" Then 서울 날짜와 요일로 말한다")
    void dateInSeoul() {
        // 2026-11-03 23:00 UTC = 2026-11-04 08:00 서울(수요일)
        Clock lateUtc = Clock.fixed(Instant.parse("2026-11-03T23:00:00Z"), ZoneId.of("UTC"));

        assertThat(SmallTalk.reply("오늘 며칠이야?", lateUtc)).isEqualTo("오늘은 11월 4일 수요일이에요.");
    }

    @ParameterizedTest(name = "\"{0}\" → {1}")
    @CsvSource({
            "안녕, 안녕하세요",
            "좋은 아침!, 안녕하세요",
            "고마워~, 별말씀을요",
            "감사합니다, 별말씀을요",
            "너 누구야?, 옷 입는 걸",
            "이름이 뭐야, 옷 입는 걸",
            "잘 지냈어?, 잘 지내요",
            "심심해, 옷 골라 볼까요",
            "바보야~, 서운해요",
            "잘 자, 푹 쉬세요",
    })
    @DisplayName("규칙에 있는 말은 정해진 답")
    void knownPhrases(String said, String expectedPart) {
        assertThat(SmallTalk.reply(said, afternoon)).contains(expectedPart);
    }

    @Test
    @DisplayName("Given 놀림과 인사가 섞임 When \"안녕 바보\" Then 놀림 답이 먼저(인사보다 위)")
    void teaseBeatsHello() {
        assertThat(SmallTalk.reply("안녕 바보", afternoon)).contains("서운해요");
    }

    @Test
    @DisplayName("Given \"안녕히 계세요\" When 답하면 Then 인사가 아니라 작별 답")
    void byeBeatsHello() {
        assertThat(SmallTalk.reply("안녕히 계세요", afternoon)).contains("푹 쉬세요");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", "?!~", "내일 뭐 입지", "우산 챙겨야 해?"})
    @DisplayName("규칙에 없는 말이나 빈 말은 null(다른 흐름이나 GPT가 답함)")
    void unknownIsNull(String said) {
        assertThat(SmallTalk.reply(said, afternoon)).isNull();
    }

    @Test
    @DisplayName("할 수 있는 일 안내에는 기능 네 가지와 예시 말이 들어 있다")
    void capabilities() {
        assertThat(SmallTalk.CAPABILITIES).contains("네 가지", "날씨", "일정", "화장", "\"내일 뭐 입지?\"");
        assertThat(SmallTalk.UNKNOWN).contains("할 수 없는", "뭐부터");
    }
}
