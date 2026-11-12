package com.fashionagent.agent.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fashionagent.agent.judge.Korean;

import tools.jackson.databind.JsonNode;

/**
 * "옷장 확인해 줘", "바지 뭐 있어?": closet_list 결과를 말로 읽어 줍니다.
 * 2026-11-12 박창근 님 폰: "옷장 확인해줘"가 키워드에 없어 GPT로 갔고, 옷 추천으로 넘어갔습니다.
 */
final class ClosetView {

    /** 종류마다 이름은 이만큼만 읽고 나머지는 "외 n벌". 귀로 듣기에 길지 않게. */
    static final int NAMES_PER_CATEGORY = 4;

    private static final Map<String, String> KOREAN = new LinkedHashMap<>();
    static {
        KOREAN.put("outer", "겉옷");
        KOREAN.put("top", "상의");
        KOREAN.put("dress", "원피스");
        KOREAN.put("bottom", "하의");
        KOREAN.put("shoes", "신발");
        KOREAN.put("other", "그 밖의 옷");
    }

    /** 말에 종류가 있으면 closet_list의 category. 없으면 null(전부). */
    private static final Map<String, List<String>> CATEGORY_WORDS = new LinkedHashMap<>();
    static {
        CATEGORY_WORDS.put("outer", List.of("겉옷", "외투", "아우터", "자켓", "재킷", "코트", "패딩", "점퍼"));
        CATEGORY_WORDS.put("dress", List.of("원피스"));
        CATEGORY_WORDS.put("bottom", List.of("바지", "하의", "치마", "스커트", "청바지"));
        CATEGORY_WORDS.put("shoes", List.of("신발", "운동화", "구두"));
        CATEGORY_WORDS.put("top", List.of("상의", "윗옷", "셔츠", "티셔츠", "니트", "블라우스", "맨투맨"));
    }

    private ClosetView() {
    }

    static String category(String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        for (var e : CATEGORY_WORDS.entrySet()) {
            if (e.getValue().stream().anyMatch(s::contains)) {
                return e.getKey();
            }
        }
        return null;
    }

    /** closet_list 결과 → 읽어 줄 말. */
    static String speech(JsonNode list, String category) {
        int total = list.path("total").asInt(0);
        String kind = category == null ? null : KOREAN.get(category);
        if (total == 0) {
            return (kind == null ? "옷장이 아직 비어 있어요. " : "옷장에 " + Korean.josa(kind, "은", "는") + " 아직 없어요. ")
                    + "옷 추천을 받고 \"이걸로 할게\"라고 하시면 고른 옷이 옷장에 저장돼요.";
        }
        Map<String, List<String>> byKind = new LinkedHashMap<>();
        for (JsonNode g : list.path("garments")) {
            byKind.computeIfAbsent(g.path("category").asString("other"), k -> new ArrayList<>()).add(name(g));
        }
        StringBuilder sb = new StringBuilder(kind == null ? "옷장에 옷이 " + total + "벌 있어요."
                : "옷장에 " + Korean.josa(kind, "이", "가") + " " + total + "벌 있어요.");
        for (var e : byKind.entrySet()) {
            List<String> names = e.getValue();
            List<String> said = names.subList(0, Math.min(NAMES_PER_CATEGORY, names.size()));
            sb.append(' ');
            if (kind == null) {
                sb.append(KOREAN.getOrDefault(e.getKey(), "그 밖의 옷")).append(' ').append(names.size()).append("벌: ");
            }
            sb.append(String.join(", ", said));
            if (names.size() > said.size()) {
                sb.append(" 외 ").append(names.size() - said.size()).append("벌");
            }
            sb.append('.');
        }
        int listed = byKind.values().stream().mapToInt(List::size).sum();
        if (total > listed) {
            sb.append(" 나머지 ").append(total - listed).append("벌은 \"바지 뭐 있어?\"처럼 종류를 말씀해 주시면 읽어 드릴게요.");
        }
        return sb.toString();
    }

    /** "검정 패딩", 색 이름이 없으면 이름만. */
    static String label(JsonNode g) {
        return name(g);
    }

    private static String name(JsonNode g) {
        String color = g.path("colorName").asString("");
        String name = g.path("name").asString("옷");
        return color.isBlank() || name.startsWith(color) ? name : color + " " + name;
    }
}
