package com.fashionagent.agent.flow;

import java.util.List;

/**
 * 말로 들어온 요청 고르기. 옷 추천 흐름은 GPT가 아니라 키워드로 정해서 발표 때 매번 같게 동작합니다(약속 5절).
 * 날씨·오늘 일정 읽기·얼굴 확인도 키워드로 바로 처리합니다(빠르고 GPT 없이도 됨).
 * 나머지({@link #OTHER}: 일정 등록·삭제, 다른 날 일정, 가족에게 묻기 등)는 GPT가 도구를 고릅니다.
 */
public enum Intent {
    OUTFIT, FINISH, WHERE, WEATHER, SCHEDULE, FACE, OTHER;

    private static final List<String> FINISH_WORDS = List.of("이걸로", "이거로", "이걸입", "이거입", "됐어", "다됐", "그만",
            "끝낼", "마칠");
    private static final List<String> OUTFIT_WORDS = List.of("뭐입", "뭘입", "옷추천", "추천해", "코디", "입을까", "입지",
            "옷골라");
    /** 가족·친구에게 묻기. 옷 이야기가 섞여도("이 옷 괜찮은지 엄마한테 물어봐") GPT로 보냅니다. */
    private static final List<String> HELPER_WORDS = List.of("물어", "보내", "답왔", "답장");
    /** "오늘 일정 뭐야?"가 아닌 일정 말(등록·삭제·다른 날)은 GPT로 보냅니다. */
    private static final List<String> SCHEDULE_CHANGE_WORDS = List.of("넣어", "등록", "추가", "잡아", "지워", "삭제",
            "취소", "빼", "내일", "모레", "다음", "요일", "이번주");

    /** inOutfitSession: 옷 추천 대화 중인지 (마무리·"어디야?"는 그때만). */
    public static Intent of(String utterance, boolean inOutfitSession) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        if (containsAny(s, HELPER_WORDS)) {
            return OTHER;
        }
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
        if ((s.contains("일정") || s.contains("스케줄")) && !containsAny(s, SCHEDULE_CHANGE_WORDS)) {
            return SCHEDULE;
        }
        return OTHER;
    }

    private static boolean containsAny(String s, List<String> words) {
        return words.stream().anyMatch(s::contains);
    }
}
