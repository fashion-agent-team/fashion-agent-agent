package com.fashionagent.agent.flow;

import java.util.List;

/**
 * 말로 들어온 요청 고르기. 옷 추천 흐름은 GPT가 아니라 키워드로 정해서 발표 때 매번 같게 동작합니다(약속 5절).
 * 날씨·오늘 일정 읽기·얼굴 확인도 키워드로 바로 처리합니다(빠르고 GPT 없이도 됨).
 * 나머지({@link #OTHER}: 일정 등록·삭제, 다른 날 일정, 가족에게 묻기 등)는 GPT가 도구를 고릅니다.
 */
public enum Intent {
    OUTFIT, CHECK, CLOSET, FINISH, WHERE, MODE, WEATHER, SCHEDULE, FACE, HELP, OTHER;

    private static final List<String> FINISH_WORDS = List.of("이걸로", "이거로", "이걸입", "이거입", "됐어", "다됐", "그만",
            "끝낼", "마칠");
    private static final List<String> OUTFIT_WORDS = List.of("뭐입", "뭘입", "옷추천", "추천해", "코디", "입을까", "입지",
            "옷골라");
    /**
     * "옷 검사해 줘"처럼 꺼낸 옷을 바로 봐 달라는 말. 추천을 길게 말하지 않고 바로 카메라로 갑니다.
     * 2026-11-09 리허설: "옷 검사"가 키워드에 없어 GPT로 갔고, 얼굴 확인으로 넘어갔습니다.
     */
    private static final List<String> CHECK_WORDS = List.of("옷검사", "옷확인", "옷체크", "옷상태", "옷봐", "옷좀봐",
            "입은옷", "입은거", "입은모습", "옷차림", "검사해", "검사좀");
    /** 가족·친구에게 묻기. 옷 이야기가 섞여도("이 옷 괜찮은지 엄마한테 물어봐") GPT로 보냅니다. */
    private static final List<String> HELPER_WORDS = List.of("물어", "보내", "답왔", "답장");
    /** 일정 등록·삭제는 GPT로 보냅니다. */
    private static final List<String> SCHEDULE_CHANGE_WORDS = List.of("넣어", "등록", "추가", "잡아", "지워", "삭제",
            "취소", "빼");
    /**
     * 여러 날 일정("이번 주 일정")은 GPT가 date·endDate로 한 번에 봅니다. 하루("내일", "다음 주 토요일", "11월 20일")는
     * 바로 읽습니다(2026-11-09 측정: GPT를 거치면 2~4.6초, 바로 읽으면 0.1초 안팎).
     */
    private static final List<String> MULTI_DAY_WORDS = List.of("이번주", "다음주", "담주", "주말", "이번달", "다음달");

    /** "할 줄 아는 거 말해 봐", "기능 설명해 줘" 같은 말. 2026-11-06 진행 님 폰 확인에서 못 알아들었던 말. */
    private static final List<String> HELP_WORDS = List.of("기능", "할줄", "할수있", "뭐해줄", "뭘해줄", "뭐도와", "뭘도와",
            "무엇을도와", "뭐할수", "뭘할수", "무엇을할", "능력", "너소개", "자기소개", "사용법", "어떻게써");

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
        // 옷 추천 중 "입고 볼게", "내려놓고 볼게"
        if (inOutfitSession && OutfitFlow.switchFrom(s) != null) {
            return MODE;
        }
        // "옷장 확인해 줘", "옷장에 뭐 있어?": 옷장 목록. "옷장에서 옷 추천해 줘"는 추천으로.
        // 2026-11-12 박창근 님 폰: 키워드가 없어 GPT로 갔고 옷 추천으로 넘어갔습니다.
        if (s.contains("옷장") && !containsAny(s, OUTFIT_WORDS) && !containsAny(s, SCHEDULE_CHANGE_WORDS)) {
            return CLOSET;
        }
        if (containsAny(s, CHECK_WORDS) && !s.contains("얼굴") && !FaceFlow.isTeeth(s)) {
            return CHECK;
        }
        if (containsAny(s, OUTFIT_WORDS)) {
            return OUTFIT;
        }
        if (s.contains("얼굴") || s.contains("화장") || FaceFlow.isTeeth(s)) {
            return FACE;
        }
        if (s.contains("날씨")) {
            return WEATHER;
        }
        if ((s.contains("일정") || s.contains("스케줄")) && !containsAny(s, SCHEDULE_CHANGE_WORDS)
                && !(containsAny(s, MULTI_DAY_WORDS) && !s.contains("요일"))) {
            return SCHEDULE;
        }
        if (containsAny(s, HELP_WORDS)) {
            return HELP;
        }
        return OTHER;
    }

    private static boolean containsAny(String s, List<String> words) {
        return words.stream().anyMatch(s::contains);
    }
}
