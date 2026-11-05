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
            case ToolNames.SCHEDULE_LIST -> schedule();
            case ToolNames.CLOTHES_INSPECT -> inspect(String.valueOf(args.get("imageBase64")));
            case ToolNames.CLOSET_SEARCH -> Map.of("candidates", List.of(), "styleNotes", List.of(), "empty", true);
            case ToolNames.CLOSET_SAVE -> closetSave(args);
            case ToolNames.CLOSET_WEAR -> Map.of("recorded", ((List<?>) args.get("garmentIds")).size());
            case ToolNames.SCHEDULE_ADD -> Map.of("eventId", "e_added", "occasion", "casual",
                    "spokenSummary", "일정 '" + args.get("title") + "'을 등록했어요.");
            case ToolNames.SCHEDULE_DELETE -> Map.of("deleted", true);
            case ToolNames.FACE_MAKEUP_CHECK -> makeup(String.valueOf(args.get("imageBase64")));
            case ToolNames.TEETH_CHECK -> teeth(String.valueOf(args.get("imageBase64")));
            case ToolNames.HELPER_CONTACTS -> Map.of("contacts",
                    List.of(Map.of("contactId", "h_1", "name", "엄마", "relation", "가족")));
            case ToolNames.HELPER_ASK -> Map.of("requestId", "r_fake", "expiresAt", "2026-11-05T09:00:00+09:00",
                    "sentTo", "엄마");
            case ToolNames.HELPER_ANSWER -> Map.of("status", "answered", "answer", "괜찮아, 잘 어울려.",
                    "answeredBy", "엄마", "answeredAt", "2026-11-04T09:10:00+09:00");
            default -> throw new ToolException(tool, "not_implemented", "가짜 도구에 없는 도구예요.");
        });
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

    private static Map<String, Object> schedule() {
        String today = LocalDate.now(SEOUL).toString();
        return Map.of("events", List.of(Map.of(
                "eventId", "e_fake",
                "title", "면접",
                "start", today + "T10:00:00+09:00",
                "place", "강남 사무실",
                "placeCategory", "회사",
                "occasion", "formal")));
    }

    private static Map<String, Object> inspect(String image) {
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
            return map("framing", "입이 다 보이지 않아요. 입을 조금 더 벌려 주세요.", "lighting", "ok", "source", "model");
        }
        return map("lighting", "ok",
                "teeth", map("foreignObject", true, "confidence", 0.82, "note", "앞니 사이에 뭔가 낀 것 같아요."),
                "source", "model");
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
