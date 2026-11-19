package com.fashionagent.agent.flow;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fashionagent.agent.judge.Occasion;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * 말 속의 자리("결혼식에 뭐 입지?", "내일 면접인데")를 자리 종류로 바꿉니다.
 * 일정에 없어도 말한 자리에 맞춰 추천하려고 씁니다(2026-11-15 실서버 시뮬: 결혼식을 물었는데 "일정은 없어요"라고만 함).
 * 키워드는 mcp-schedule OccasionClassifier 제목 키워드와 같습니다.
 */
public final class SpokenOccasion {

    /** 말한 자리. title은 말에 나온 낱말("결혼식"). */
    public record Spoken(Occasion occasion, String title) {
    }

    private static final Map<Occasion, List<String>> WORDS = new LinkedHashMap<>();

    static {
        WORDS.put(Occasion.mourning, List.of("장례식", "장례", "조문", "빈소", "추도식"));
        WORDS.put(Occasion.wedding, List.of("결혼식", "웨딩", "예식", "하객"));
        WORDS.put(Occasion.formal, List.of("면접", "발표", "상견례", "시상식", "졸업식", "입학식", "세미나"));
        WORDS.put(Occasion.business, List.of("회의", "미팅", "출근", "거래처", "수업", "강의"));
        WORDS.put(Occasion.active, List.of("운동", "헬스", "등산", "러닝", "조깅", "요가", "필라테스", "축구", "농구"));
    }

    private SpokenOccasion() {
    }

    /** 가장 엄격한 자리 하나. 없으면 null. */
    public static Spoken from(String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        for (Map.Entry<Occasion, List<String>> e : WORDS.entrySet()) {
            for (String w : e.getValue()) {
                if (s.contains(w)) {
                    return new Spoken(e.getKey(), w);
                }
            }
        }
        return null;
    }

    /**
     * 일정에 말한 자리를 더한 목록. 일정에 같거나 더 엄격한 자리가 이미 있으면 그대로 돌려줍니다.
     * 더한 항목은 spoken=true라서 "일정은 ~" 문장에는 넣지 않습니다.
     */
    public static JsonNode merge(JsonNode events, String occasion, String title) {
        if (occasion == null || title == null) {
            return events;
        }
        Occasion spoken;
        try {
            spoken = Occasion.valueOf(occasion);
        } catch (IllegalArgumentException e) {
            return events;
        }
        if (events != null && Occasion.strictest(events).ordinal() <= spoken.ordinal()
                && Occasion.strictest(events) != Occasion.casual) {
            return events;
        }
        ArrayNode out = JsonNodeFactory.instance.arrayNode();
        if (events != null && events.isArray()) {
            events.forEach(out::add);
        }
        ObjectNode e = out.addObject();
        e.put("title", title);
        e.put("occasion", spoken.name());
        e.put("spoken", true);
        return out;
    }
}
