package com.fashionagent.agent.judge;

import java.util.Map;

/** 읽어 줄 문장을 만들 때 쓰는 작은 도우미 (조사, 위치·종류 이름). */
public final class Korean {

    private static final Map<String, String> POSITION = Map.of("left", "왼쪽", "right", "오른쪽", "center", "가운데");
    private static final Map<String, String> CATEGORY = Map.of(
            "top", "상의", "bottom", "하의", "outer", "겉옷", "dress", "원피스", "shoes", "신발", "other", "소품");

    /** 받침이 있으면 withBatchim, 없으면 without. 예: josa("셔츠", "이", "가") → "셔츠가". */
    public static String josa(String word, String withBatchim, String without) {
        if (word == null || word.isEmpty()) {
            return without;
        }
        char last = word.charAt(word.length() - 1);
        boolean batchim = last >= 0xAC00 && last <= 0xD7A3 && (last - 0xAC00) % 28 != 0;
        return word + (batchim ? withBatchim : without);
    }

    public static String position(String position) {
        return POSITION.getOrDefault(position, "");
    }

    public static String category(String category) {
        return CATEGORY.getOrDefault(category, "옷");
    }

    private Korean() {
    }
}
