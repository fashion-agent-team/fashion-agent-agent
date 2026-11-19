package com.fashionagent.agent.judge;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

/**
 * GPT가 말한 "체감 N도"를 날씨 결과의 체감 온도(apparentTemperature)로 맞춥니다.
 * 2026-11-19 시뮬: 실제 체감 -2도인데 바지 확인에서 "오늘 체감 9도"라고 읽음.
 * GPT가 최고 기온(maxTemperature)을 체감으로 말하거나 프롬프트 예시(8.8 → 9도)의 숫자를 따라 한 것으로 보입니다.
 * 숫자는 코드가 정하고, GPT 문장에서는 숫자만 바꿉니다.
 */
final class FeelsLikeGuard {

    /** "체감 9도", "체감 온도 9도", "체감온도는 -2도", "체감 영하 2도", "체감 8.8도". */
    private static final Pattern FEELS = Pattern.compile(
            "(체감\\s*(?:온도)?\\s*(?:는|가|이|은)?\\s*)(?:영하\\s*)?[-−]?\\s*\\d+(?:\\.\\d+)?(\\s*도)");

    /** 날씨 결과의 체감 온도를 반올림한 정수. 없으면 null. */
    static Integer feelsLike(JsonNode weather) {
        if (weather == null || weather.isMissingNode() || weather.isNull()) {
            return null;
        }
        JsonNode t = weather.path("apparentTemperature");
        return t.isNumber() ? (int) Math.round(t.asDouble()) : null;
    }

    static String fix(String speech, JsonNode weather) {
        Integer t = feelsLike(weather);
        if (speech == null || t == null) {
            return speech;
        }
        Matcher m = FEELS.matcher(speech);
        return m.replaceAll(r -> Matcher.quoteReplacement(r.group(1) + t + r.group(2)));
    }

    private FeelsLikeGuard() {
    }
}
