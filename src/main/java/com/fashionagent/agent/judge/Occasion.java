package com.fashionagent.agent.judge;

import tools.jackson.databind.JsonNode;

/** 일정의 자리 종류 (mcp-tools.md mcp-schedule, 앱 lib/styling/schedule_rules.dart와 같음). 앞쪽일수록 엄격합니다. */
public enum Occasion {
    mourning, wedding, formal, business, active, casual;

    /** 오늘 일정 중 가장 엄격한 자리. 일정이 없으면 casual. */
    public static Occasion strictest(JsonNode events) {
        Occasion result = casual;
        if (events == null) {
            return result;
        }
        for (JsonNode event : events) {
            try {
                Occasion o = valueOf(event.path("occasion").asString("casual"));
                if (o.ordinal() < result.ordinal()) {
                    result = o;
                }
            } catch (IllegalArgumentException ignored) {
                // 모르는 값은 casual로 봄
            }
        }
        return result;
    }

    /** strictest와 같은 자리의 일정 제목 (예: "면접"). 없으면 null. */
    public static String titleOf(JsonNode events, Occasion occasion) {
        if (events == null) {
            return null;
        }
        for (JsonNode event : events) {
            if (occasion.name().equals(event.path("occasion").asString(""))) {
                return event.path("title").asString(null);
            }
        }
        return null;
    }
}
