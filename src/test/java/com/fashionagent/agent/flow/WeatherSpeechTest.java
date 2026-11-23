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
}
