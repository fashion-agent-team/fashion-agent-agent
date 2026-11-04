package com.fashionagent.agent.flow;

import java.util.List;

/**
 * 말로 들어온 요청 고르기. 옷 추천 흐름은 Gemini가 아니라 키워드로 정해서 발표 때 매번 같게 동작합니다(약속 5절).
 * 개별 기능(일정 등록, 가족에게 묻기 등)은 나중에 Gemini 도구 선택으로 넘깁니다.
 */
public enum Intent {
    OUTFIT, FINISH, WHERE, WEATHER, SCHEDULE, FACE, UNKNOWN;

    private static final List<String> FINISH_WORDS = List.of("이걸로", "이거로", "이걸입", "이거입", "됐어", "다됐", "그만",
            "끝낼", "마칠");
    private static final List<String> OUTFIT_WORDS = List.of("뭐입", "뭘입", "옷추천", "추천해", "코디", "입을까", "입지",
            "옷골라");

    /** inOutfitSession: 옷 추천 대화 중인지 (마무리·"어디야?"는 그때만). */
    public static Intent of(String utterance, boolean inOutfitSession) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        if (inOutfitSession && containsAny(s, FINISH_WORDS)) {
            return FINISH;
        }
        if (inOutfitSession && s.contains("어디")) {
            return WHERE;
        }
        if (containsAny(s, OUTFIT_WORDS)) {
            return OUTFIT;
        }
        if (s.contains("얼굴")) {
            return FACE;
        }
        if (s.contains("날씨")) {
            return WEATHER;
        }
        if (s.contains("일정") || s.contains("스케줄")) {
            return SCHEDULE;
        }
        return UNKNOWN;
    }

    private static boolean containsAny(String s, List<String> words) {
        return words.stream().anyMatch(s::contains);
    }
}
