package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FeelsLikeGuardTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 2026-11-19 시뮬과 비슷한 날: 지금 체감 -2.3도, 최고 9도. */
    private static final JsonNode COLD = JSON.readTree("""
            {"condition":"맑음","temperature":1.5,"apparentTemperature":-2.3,
             "minTemperature":-1.0,"maxTemperature":9.0,"dailyRange":10.0}
            """);

    @Test
    @DisplayName("GPT가 최고 기온을 체감으로 말하면 실제 체감으로 바꾼다 (2026-11-19: 체감 -2도인데 \"체감 9도\")")
    void wrongNumber() {
        assertThat(FeelsLikeGuard.fix("검정 바지는 좋아요. 오늘 체감 9도라 겉옷도 챙기세요.", COLD))
                .isEqualTo("검정 바지는 좋아요. 오늘 체감 -2도라 겉옷도 챙기세요.");
    }

    @Test
    @DisplayName("여러 말투(체감 온도는, 체감온도, 영하, 소수점)도 맞춘다")
    void forms() {
        assertThat(FeelsLikeGuard.fix("체감 온도는 9도예요.", COLD)).isEqualTo("체감 온도는 -2도예요.");
        assertThat(FeelsLikeGuard.fix("체감온도가 8.8도라", COLD)).isEqualTo("체감온도가 -2도라");
        assertThat(FeelsLikeGuard.fix("체감 영하 5도라 추워요", COLD)).isEqualTo("체감 -2도라 추워요");
        assertThat(FeelsLikeGuard.fix("체감 -7도, 체감 3도", COLD)).isEqualTo("체감 -2도, 체감 -2도");
    }

    @Test
    @DisplayName("맞는 숫자, 체감이 아닌 기온, 날씨 없음은 그대로 둔다")
    void untouched() {
        assertThat(FeelsLikeGuard.fix("오늘 체감 -2도예요.", COLD)).isEqualTo("오늘 체감 -2도예요.");
        assertThat(FeelsLikeGuard.fix("최고 9도, 최저 -1도예요.", COLD)).isEqualTo("최고 9도, 최저 -1도예요.");
        assertThat(FeelsLikeGuard.fix("오늘 체감 9도예요.", null)).isEqualTo("오늘 체감 9도예요.");
        assertThat(FeelsLikeGuard.fix("오늘 체감 9도예요.", JSON.readTree("{}"))).isEqualTo("오늘 체감 9도예요.");
        assertThat(FeelsLikeGuard.fix(null, COLD)).isNull();
    }

    @Test
    @DisplayName("GPT 입력에 반올림한 체감 온도(feelsLike)를 따로 준다")
    void input() {
        var input = new java.util.LinkedHashMap<String, Object>();
        LlmOutfitJudge.putFeelsLike(input, COLD);
        assertThat(input.get("feelsLike")).isEqualTo(-2);
        var none = new java.util.LinkedHashMap<String, Object>();
        LlmOutfitJudge.putFeelsLike(none, null);
        assertThat(none).doesNotContainKey("feelsLike");
    }
}
