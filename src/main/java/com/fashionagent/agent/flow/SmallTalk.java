package com.fashionagent.agent.flow;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 기능 밖의 말에도 대화하듯 답하기. 2026-11-06 진행 님 폰 확인: "할 줄 아는 거 말해 봐"나 기능 밖 질문에 답을 못 함.
 * GPT가 켜져 있으면 기능 밖 말은 GPT가 자연스럽게 답하고({@code LlmAssistant}), 여기 규칙은 GPT 없이 돌 때와
 * "뭐 할 수 있어?"처럼 매번 같게 답해야 하는 말에 씁니다.
 */
public final class SmallTalk {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /** "뭐 할 수 있어?"의 답. 한 번에 듣기 좋게 짧게. */
    public static final String CAPABILITIES = "제가 할 수 있는 일은 다섯 가지예요. "
            + "오늘 날씨와 일정에 맞춰 옷을 추천하고, 꺼낸 옷을 카메라로 보여 주시면 얼룩이나 찢어진 곳이 없는지 봐 드려요. "
            + "오늘 날씨를 알려 드리고, 일정을 넣거나 읽어 드려요. "
            + "얼굴이나 이에 뭐가 묻었는지 봐 드리고, 옷 사진을 가족에게 보내 물어봐 드릴 수도 있어요. "
            + "예를 들어 \"오늘 뭐 입지?\"라고 말해 보세요.";

    /** 모르는 말. 못 한다고 솔직히 말하고 할 수 있는 일을 짧게 알려 줍니다. */
    public static final String UNKNOWN = "그건 아직 제가 할 수 없는 일이에요. "
            + "옷 추천, 날씨, 일정, 얼굴 확인은 도와드릴 수 있어요. 뭘 해 드릴까요?";

    private static final List<String> HELLO = List.of("안녕", "반가", "하이", "좋은아침");
    private static final List<String> THANKS = List.of("고마", "감사", "땡큐", "고맙");
    private static final List<String> WHO = List.of("누구야", "누구세요", "누구니", "너는뭐", "넌뭐", "이름이뭐", "이름뭐");
    private static final List<String> HOW_ARE_YOU = List.of("잘지냈", "기분어때", "뭐해", "심심");
    private static final List<String> TIME = List.of("몇시", "지금시간", "시간알려");
    private static final List<String> DATE = List.of("며칠", "몇일", "날짜", "무슨요일", "몇월");
    /** 2026-11-06 진행 님 예: 이름 부르고 "바보~"면 "바보 아닙니다" 느낌으로. */
    private static final List<String> TEASE = List.of("바보", "멍청", "멍텅", "똥개", "못생");
    private static final List<String> BYE = List.of("잘자", "잘있어", "안녕히", "다음에봐", "나갈게");

    private SmallTalk() {
    }

    /** 규칙으로 답할 수 있는 말이면 답, 아니면 null. */
    public static String reply(String utterance, Clock clock) {
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (s.isEmpty()) {
            return null;
        }
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(SEOUL));
        if (containsAny(s, TIME)) {
            int h = now.getHour();
            String half = h < 12 ? "오전" : "오후";
            int h12 = h % 12 == 0 ? 12 : h % 12;
            return "지금은 " + half + " " + h12 + "시 " + now.getMinute() + "분이에요.";
        }
        if (containsAny(s, DATE)) {
            return "오늘은 " + now.getMonthValue() + "월 " + now.getDayOfMonth() + "일 "
                    + now.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN) + "이에요.";
        }
        if (containsAny(s, TEASE)) {
            return "바보 아니에요. 그래도 더 잘 도와드리도록 노력할게요.";
        }
        if (containsAny(s, THANKS)) {
            return "천만에요. 또 필요하면 언제든 불러 주세요.";
        }
        if (containsAny(s, BYE)) {
            return "네, 다음에 또 불러 주세요.";
        }
        if (containsAny(s, WHO)) {
            return "저는 옷 입는 걸 도와드리는 에이전트예요. 오늘 뭐 입을지 같이 골라 드릴게요.";
        }
        if (containsAny(s, HOW_ARE_YOU)) {
            return "저는 언제나 잘 있어요. 오늘은 어떤 걸 도와드릴까요?";
        }
        if (containsAny(s, HELLO)) {
            return "안녕하세요. 오늘은 어떤 걸 도와드릴까요?";
        }
        return null;
    }

    private static boolean containsAny(String s, List<String> words) {
        return words.stream().anyMatch(s::contains);
    }
}
