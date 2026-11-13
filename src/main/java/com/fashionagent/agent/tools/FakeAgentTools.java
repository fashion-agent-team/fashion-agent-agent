package com.fashionagent.agent.tools;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * MCP 서버 없이 agent를 개발·시연할 때 쓰는 가짜 도구. 모양은 mcp-tools.md 예시와 같습니다.
 * 옷 사진은 내용과 상관없이 "왼쪽 흰 셔츠(얼룩) + 오른쪽 체크 셔츠"를 돌려줍니다.
 * 사진 base64가 "DARK"로 시작하면 어두운 사진, "EMPTY"면 옷 없음으로 봅니다(테스트용).
 */
public class FakeAgentTools implements AgentTools {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final JsonMapper json;

    public FakeAgentTools(JsonMapper json) {
        this.json = json;
    }

    @Override
    public JsonNode call(String tool, Map<String, Object> args) {
        return json.valueToTree(switch (tool) {
            case ToolNames.WEATHER_TODAY -> weather();
            case ToolNames.WEATHER_FORECAST -> forecast(String.valueOf(args.get("date")));
            case ToolNames.SCHEDULE_LIST -> schedule(String.valueOf(args.get("date")));
            case ToolNames.CLOTHES_INSPECT -> "worn".equals(args.get("mode"))
                    ? inspectWorn(String.valueOf(args.get("imageBase64")), String.valueOf(args.get("view")),
                            Boolean.TRUE.equals(args.get("allowGpt")))
                    : inspect(String.valueOf(args.get("imageBase64")), Boolean.TRUE.equals(args.get("allowGpt")));
            case ToolNames.CLOSET_SEARCH -> Map.of("candidates", List.of(), "styleNotes", List.of(), "empty", true);
            case ToolNames.CLOSET_SAVE -> closetSave(args);
            case ToolNames.CLOSET_WEAR -> Map.of("recorded", ((List<?>) args.get("garmentIds")).size());
            case ToolNames.CLOSET_MERGE -> Map.of("merged", args.get("garmentId"), "into", args.get("intoGarmentId"));
            case ToolNames.CLOSET_LIST -> Map.of("garments", List.of(), "total", 0);
            case ToolNames.CLOSET_OUTFITS -> Map.of("outfits", List.of());
            case ToolNames.CLOSET_REMOVE -> Map.of("removed", ((List<?>) args.get("garmentIds")).size());
            case ToolNames.SCHEDULE_ADD -> Map.of("eventId", "e_added", "occasion", "casual",
                    "spokenSummary", "일정 '" + args.get("title") + "'을 등록했어요.");
            case ToolNames.SCHEDULE_DELETE -> Map.of("deleted", true);
            case ToolNames.SCHEDULE_SET_REMINDER -> reminder(args);
            case ToolNames.FACE_MAKEUP_CHECK -> makeup(String.valueOf(args.get("imageBase64")));
            case ToolNames.TEETH_CHECK -> teeth(String.valueOf(args.get("imageBase64")));
            default -> throw new ToolException(tool, "not_implemented", "가짜 도구에 없는 도구예요.");
        });
    }

    private static Map<String, Object> reminder(Map<String, Object> args) {
        Map<String, Object> out = new java.util.HashMap<>();
        boolean remind = !Boolean.FALSE.equals(args.get("remind"));
        out.put("eventId", args.get("eventId"));
        out.put("remind", remind);
        out.put("remindDaysBefore", remind ? args.getOrDefault("daysBefore", 1) : null);
        return out;
    }

    private static Map<String, Object> weather() {
        return Map.of(
                "condition", "맑음",
                "temperature", 11.0,
                "apparentTemperature", 9.5,
                "minTemperature", 4.0,
                "maxTemperature", 15.0,
                "dailyRange", 11.0,
                "precipitationProbability", 10,
                "needsOuter", true,
                "needsUmbrella", false,
                "clothingAdvice", "자켓이나 가디건을 걸치세요.");
    }

    /** 날짜 하루 예보. 16일 넘게 남은 날은 실제 서버처럼 out_of_range. */
    private static Map<String, Object> forecast(String date) {
        LocalDate day = LocalDate.parse(date);
        if (day.isAfter(LocalDate.now(SEOUL).plusDays(15))) {
            throw new ToolException(ToolNames.WEATHER_FORECAST, "out_of_range", "그날 날씨는 아직 알 수 없어요.");
        }
        return map(
                "date", date,
                "condition", "비",
                "temperature", 8.0,
                "apparentTemperature", 6.0,
                "minTemperature", 3.0,
                "maxTemperature", 13.0,
                "dailyRange", 10.0,
                "precipitationProbability", 70,
                "needsOuter", true,
                "needsUmbrella", true,
                "clothingAdvice", "코트와 히트텍처럼 따뜻한 옷이 좋아요.");
    }

    /** 오늘(과 지난 날)은 면접, 앞으로의 날은 그날 오후 2시 결혼식(가짜). */
    private static Map<String, Object> schedule(String date) {
        String today = LocalDate.now(SEOUL).toString();
        if (date != null && date.matches("\\d{4}-\\d{2}-\\d{2}") && date.compareTo(today) > 0) {
            return Map.of("events", List.of(Map.of(
                    "eventId", "e_fake_" + date,
                    "title", "친구 결혼식",
                    "start", date + "T14:00:00+09:00",
                    "place", "더채플앳청담",
                    "placeCategory", "웨딩홀",
                    "occasion", "wedding")));
        }
        return Map.of("events", List.of(Map.of(
                "eventId", "e_fake",
                "title", "면접",
                "start", today + "T10:00:00+09:00",
                "place", "강남 사무실",
                "placeCategory", "회사",
                "occasion", "formal")));
    }

    /** 입은 사람 한 명. 앞에서는 셔츠와 슬랙스, 뒤에서는 셔츠 등에 얼룩. where는 입은 사람 몸 기준입니다. */
    private static Map<String, Object> inspectWorn(String image, String view, boolean allowGpt) {
        if (image.startsWith("FAR")) {
            return Map.of("garments", List.of(), "framing", "발이 안 보여요. 한 걸음 더 물러나 주세요.",
                    "lighting", "ok", "source", "model", "modelVersion", "fake");
        }
        var shirt = map("id", "g1", "category", "top", "name", "셔츠", "colorName", "흰색",
                "colorFamily", "white", "tone", "light", "formality", "smart", "position", "center",
                "defects", "back".equals(view)
                        ? List.of(Map.of("kind", "stain", "where", "등 가운데", "confidence", 0.8))
                        : List.of(),
                "confidence", 0.93);
        var slacks = map("id", "g2", "category", "bottom", "name", "슬랙스", "colorName", "남색",
                "colorFamily", "navy", "tone", "dark", "formality", "formal", "position", "center",
                "defects", List.of(), "confidence", 0.9);
        var out = map("garments", "front".equals(view) ? List.of(shirt, slacks) : List.of(shirt),
                "framing", "", "lighting", "ok", "source", "model", "modelVersion", "fake");
        if (image.startsWith("UNSURE") && "front".equals(view)) {
            // 앞모습에서만 애매한 얼룩 후보. 뒤까지 다 본 뒤에 GPT 확인을 묻는지 봅니다.
            out.put("pendingHelper", map("garmentId", "g2", "candidates",
                    List.of(Map.of("kind", "stain", "where", "무릎", "confidence", 0.4)), "note", "확실하지 않아요"));
        }
        if (allowGpt) {
            // 실제 서버처럼 동의했을 때만 옷 실수를 봄. WEAR 사진이면 셔츠 택이 앞·뒤 모두에서 보이고 앞에서는 단추도 어긋남.
            out.put("wearIssues", !image.startsWith("WEAR") ? List.of() : "front".equals(view)
                    ? List.of(Map.of("kind", "buttons_misaligned", "garmentId", "g1", "category", "top", "confidence", 0.85,
                            "checkedBy", "gpt"), Map.of("kind", "tag_out", "garmentId", "g1", "category", "top",
                                    "confidence", 0.8, "checkedBy", "gpt"))
                    : List.of(Map.of("kind", "tag_out", "garmentId", "g1", "category", "top", "confidence", 0.9,
                            "checkedBy", "gpt")));
        }
        return out;
    }

    private static Map<String, Object> inspect(String image, boolean allowGpt) {
        if (image.startsWith("UNSURE")) {
            // 애매한 얼룩: 실제 서버처럼 GPT 없이는 defects에 넣지 않고 pendingHelper로 돌려줍니다.
            // GPT 확인을 허락하면 GPT가 얼룩이라고 확인한 것으로 돌려줍니다.
            List<Object> defects = allowGpt
                    ? List.of(Map.<String, Object>of("kind", "stain", "where", "앞", "confidence", 0.7, "checkedBy", "gpt"))
                    : List.of();
            var out = map("garments", List.of(map("id", "g1", "category", "top", "name", "셔츠", "colorName", "흰색",
                    "colorFamily", "white", "tone", "light", "formality", "smart", "position", "center",
                    "defects", defects, "confidence", 0.95)),
                    "lighting", "ok", "source", "model", "modelVersion", "fake");
            if (!allowGpt) {
                out.put("pendingHelper", map("garmentId", "g1", "candidates",
                        List.of(Map.of("kind", "stain", "where", "앞", "confidence", 0.35)), "note", "확실하지 않아요"));
            }
            return out;
        }
        if (image.startsWith("PATTERN?")) {
            // 무늬를 모름(pattern 없음, 호피 같아 보임). 얼룩은 없음.
            return Map.of("garments", List.of(map("id", "g1", "category", "top", "name", "셔츠", "colorName", "회색",
                    "colorFamily", "gray", "tone", "mid", "formality", "casual", "position", "center",
                    "patternGuess", "animal", "defects", List.of(), "confidence", 0.9)),
                    "lighting", "ok", "source", "model", "modelVersion", "fake");
        }
        if (image.startsWith("DARK")) {
            return Map.of("garments", List.of(), "lighting", "dark", "source", "model", "modelVersion", "fake");
        }
        if (image.startsWith("EMPTY")) {
            return Map.of("garments", List.of(), "framing", "옷이 안 보여요. 옷 위로 폰을 들어 주세요.",
                    "lighting", "ok", "source", "model", "modelVersion", "fake");
        }
        var white = map("id", "g1", "category", "top", "name", "셔츠", "colorName", "흰색",
                "colorFamily", "white", "tone", "light", "formality", "smart", "position", "left",
                "defects", List.of(Map.of("kind", "stain", "where", "오른쪽 소매 끝", "confidence", 0.85)),
                "confidence", 0.95);
        var check = map("id", "g2", "category", "top", "name", "체크 셔츠", "colorName", "빨간색",
                "colorFamily", "red", "tone", "mid", "pattern", "체크", "formality", "casual",
                "position", "right", "defects", List.of(), "confidence", 0.93);
        return Map.of("garments", List.of(white, check), "lighting", "ok", "source", "model",
                "modelVersion", "fake");
    }

    /** 얼굴(화장): "CUT"으로 시작하면 구도 안내, 그 밖에는 눈썹 비대칭. */
    private static Map<String, Object> makeup(String image) {
        if (image.startsWith("NOFACE")) {
            throw new ToolException(ToolNames.FACE_MAKEUP_CHECK, "no_landmarks", "얼굴을 찾지 못했어요.");
        }
        if (image.startsWith("CUT")) {
            return map("framing", "얼굴이 화면 위쪽에 잘렸어요. 폰을 조금 내려 주세요.", "lighting", "ok", "source", "model");
        }
        return map("lighting", "ok",
                "symmetry", map("ok", false, "notes", List.of("왼쪽 눈썹이 오른쪽보다 조금 짧게 그려졌어요.")),
                "source", "model");
    }

    /** 치아: "CUT"으로 시작하면 구도 안내, 그 밖에는 앞니에 낀 것. */
    private static Map<String, Object> teeth(String image) {
        if (image.startsWith("CUT")) {
            return map("framing", "입이 다 보이지 않아요. 입을 조금 더 벌려 주세요.", "lighting", "ok");
        }
        if (image.startsWith("UNSURE")) {
            return map("framing", null, "lighting", "ok",
                    "teeth", map("foreignObject", null, "confidence", 0.5, "note", "뭔가 낀 것 같기도 한데, 잘 보이지 않아서 확실하지 않아요."));
        }
        return map("framing", null, "lighting", "ok",
                "teeth", map("foreignObject", true, "confidence", 0.82, "note", "앞니 사이에 뭔가 낀 것 같아요."));
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static Map<String, Object> closetSave(Map<String, Object> args) {
        List<Map<String, Object>> saved = new ArrayList<>();
        for (Object g : (List<?>) args.get("garments")) {
            String id = String.valueOf(((Map<?, ?>) g).get("id"));
            saved.add(Map.of("inputId", id, "garmentId", "c_" + id, "isNew", true));
        }
        return Map.of("saved", saved);
    }
}
