package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class WeatherSpeechTest {

    private static JsonNode w(String condition) {
        return JsonMapper.builder().build().readTree("""
                {"condition":"%s","temperature":6,"apparentTemperature":3,"minTemperature":1,"maxTemperature":12,
                 "precipitationProbability":100,"needsOuter":true,"needsUmbrella":true,"clothingAdvice":""}"""
                .formatted(condition));
    }

    @Test
    void 지금_맑은데_오늘_비_예보면_오늘_중에라고_말한다() {
        assertThat(TurnService.weatherSpeech(w("맑음"), null, "오늘 날씨 어때?"))
                .contains("지금은 안 오지만 오늘 중에 비 올 확률이 100%라 우산을 챙기세요.");
        assertThat(TurnService.weatherSpeech(w("맑음"), null, "우산 챙겨야 해?"))
                .startsWith("우산 챙기세요. 지금은 안 오지만 오늘 중에 비나 눈 올 확률이 100%예요.");
    }

    @Test
    void 지금_비가_오거나_다른_날이면_그대로_말한다() {
        assertThat(TurnService.weatherSpeech(w("비"), null, "오늘 날씨 어때?")).doesNotContain("지금은 안 오지만");
        assertThat(TurnService.weatherSpeech(w("맑음"), "내일", "내일 날씨 어때?")).doesNotContain("지금은 안 오지만");
    }

    @Test
    void 목도리_장갑은_추위로_네_아니요를_먼저_말한다() {
        assertThat(TurnService.weatherSpeech(w("맑음"), null, "목도리 해야 돼?")).startsWith("네, 챙기세요. 최저 1도라 추워요.");
        assertThat(TurnService.weatherSpeech(w("맑음"), "내일", "내일 장갑 챙길까?")).startsWith("네, 챙기세요. 최저 1도라 추워요.");
    }

    @Test
    void 치마_청바지_입어도_되냐면_네와_추위를_말한다() {
        assertThat(TurnService.weatherSpeech(w("맑음"), null, "치마 입어도 돼?"))
                .startsWith("네, 입어도 돼요. 다만 최저 1도라 두꺼운 스타킹을 신고 겉옷을 챙기세요.");
        assertThat(TurnService.weatherSpeech(w("맑음"), null, "청바지 괜찮아?")).startsWith("네, 입어도 돼요. 다만 추우니 겉옷을 챙기세요.");
    }

    @Test
    void 거꾸로_물으면_네_아니요도_거꾸로() {
        assertThat(TurnService.weatherSpeech(w("맑음"), null, "목도리 필요 없지?")).startsWith("아니요, 챙기세요.");
    }

    @Test
    void 장화_우비는_비로_답한다() {
        assertThat(TurnService.weatherSpeech(w("비"), null, "장화 신어야 돼?")).startsWith("우산 챙기세요.");
    }
}
