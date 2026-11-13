package com.fashionagent.agent.tools;

import java.util.Map;

/** 도구 이름과 그 도구가 있는 MCP 서버 (약속 6절). 이름은 &lt;서버&gt;_&lt;동작&gt;. */
public final class ToolNames {

    public static final String WEATHER_TODAY = "weather_today";
    /** 날짜 하루의 예보(오늘부터 16일 안쪽). 다른 날 옷 추천에 씁니다. */
    public static final String WEATHER_FORECAST = "weather_forecast";
    public static final String SCHEDULE_LIST = "schedule_list";
    public static final String SCHEDULE_ADD = "schedule_add";
    public static final String SCHEDULE_DELETE = "schedule_delete";
    public static final String SCHEDULE_SET_REMINDER = "schedule_set_reminder";
    public static final String CLOTHES_INSPECT = "clothes_inspect";
    /** 같이 입을 옷의 색 조합 판단과 어울림 점수(0~100, 색상환 규칙). 옷 상태 서버에 있습니다. */
    public static final String OUTFIT_COLOR_CHECK = "outfit_color_check";
    public static final String FACE_MAKEUP_CHECK = "face_makeup_check";
    public static final String TEETH_CHECK = "teeth_check";
    public static final String CLOSET_SEARCH = "closet_search";
    public static final String CLOSET_SAVE = "closet_save";
    public static final String CLOSET_WEAR = "closet_wear";
    public static final String CLOSET_MERGE = "closet_merge";
    public static final String CLOSET_LIST = "closet_list";
    public static final String CLOSET_OUTFITS = "closet_outfits";
    public static final String CLOSET_REMOVE = "closet_remove";

    /** 도구 이름의 앞부분이 서버 이름입니다. clothes_inspect → clothes-state 처럼 다른 것만 적습니다. */
    private static final Map<String, String> SERVER_OF_PREFIX = Map.of(
            "clothes", "clothes-state",
            "outfit", "clothes-state",
            "face", "face-check",
            "teeth", "face-check");

    public static String serverOf(String tool) {
        String prefix = tool.substring(0, tool.indexOf('_'));
        return SERVER_OF_PREFIX.getOrDefault(prefix, prefix);
    }

    private ToolNames() {
    }
}
