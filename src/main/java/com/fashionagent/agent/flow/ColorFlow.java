package com.fashionagent.agent.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.judge.Korean;
import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 빠른 색 확인: "이거 무슨 색이야?" → 카메라 → 비춘 옷의 색·무늬·종류만 바로 읽어 줍니다(2026-11-13 진행 님 요청).
 * 날씨·일정 추천이나 옷장 기록 없이, 옷 상태 서버(clothes_inspect)의 색 판단만 씁니다. GPT를 거치지 않아 빠릅니다.
 * 카메라는 켜 둔 채로 다음 옷을 비추면 또 읽고, "그만"·"됐어"면 끝냅니다.
 */
@Component
public class ColorFlow {

    private static final Logger log = LoggerFactory.getLogger(ColorFlow.class);

    static final String START = "네, 색 봐 드릴게요. 옷을 폰으로 비춰 주세요.";
    static final String MORE = "다른 옷도 비추시면 또 알려 드릴게요. 그만하시려면 \"됐어\"라고 해 주세요.";
    static final String DONE = "알겠어요. 색 확인은 여기까지 할게요.";

    /** 색 확인을 묻는 말. "이거 무슨 색이야?", "이 옷 색깔 뭐야?", "어떤 색이야?", "색 알려 줘". */
    private static final List<String> WORDS = List.of("무슨색", "뭔색", "어떤색", "무슨색깔", "색깔뭐", "색이뭐", "색뭐",
            "색알려", "색깔알려", "색확인", "색좀봐", "색봐", "무슨무늬", "어떤무늬",
            // 2026-11-23 시뮬: "파란색이야?"처럼 색을 확인하는 말
            "색이야", "색맞아", "색깔이야", "색인가",
            // 2026-11-23 시뮬: "옷 색깔 봐줘"가 GPT로 갔음
            "색깔봐", "색깔좀봐", "색깔확인");
    private static final List<String> FINISH = List.of("됐어", "그만", "끝", "다봤", "고마워", "이제됐");

    private final AgentTools tools;
    private final SessionStore sessions;
    private final JsonMapper json;

    public ColorFlow(AgentTools tools, SessionStore sessions, JsonMapper json) {
        this.tools = tools;
        this.sessions = sessions;
        this.json = json;
    }

    /** 공백을 뺀 말이 색 확인을 묻는지. */
    static boolean isColorQuestion(String compact) {
        return WORDS.stream().anyMatch(compact::contains);
    }

    public TurnResponse start(AgentSession session) {
        session.colorCheck = true;
        session.colorSeen = new ArrayList<>();
        return reply(session, START, Next.camera_outfit);
    }

    /** 색 확인 중에 한 말. 끝내는 말이면 끝내고, 다른 말이면 색 확인을 접고 null(원래 대화로). */
    public TurnResponse answer(AgentSession session, String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (isColorQuestion(s)) {
            return reply(session, "네, 옷을 비춰 주시면 색을 알려 드릴게요.", Next.camera_outfit);
        }
        // 색을 보는 중 "이건?", "이것도": 다음 옷을 비추겠다는 말(2026-11-23 시뮬에서 색 확인을 접었음).
        // "이 신발은?", "이 바지도?"(2026-11-23 시뮬: 색 확인을 접었음).
        if (s.matches("(그럼|그러면)?(이건|이거는|이것도|이것은|이거도|이건요|저건|저거는)(뭐야|요|어때)?")
                || (s.matches("(그럼|그러면)?(이|저|요)[가-힣]{1,4}(은|는|도)(요|뭐야|어때)?") && ClosetView.category(s) != null)) {
            return reply(session, "네, 비춰 주시면 바로 알려 드릴게요.", Next.camera_outfit);
        }
        // 색을 보는 중 "짧게 말해 줘", "자세히 말해 줘": 색 설명 길이만 바꾸고 카메라는 켜 둡니다.
        if (s.length() <= 12 && (s.contains("짧게") || s.contains("간단히") || s.contains("자세히"))) {
            boolean detail = s.contains("자세히");
            session.colorDetail = detail;
            sessions.save(session);
            return new TurnResponse(session.sessionId,
                    detail ? com.fashionagent.agent.judge.ColorFeel.SAY_DETAILED
                            : com.fashionagent.agent.judge.ColorFeel.SAY_SHORT,
                    Next.camera_outfit, null, null, detail);
        }
        // "이 두 개 어울려?": 2026-11-23 시뮬에서 색 확인을 접고 "할 수 없는 일이에요"로 답했음.
        if (s.contains("어울") && s.length() <= 15) {
            return harmony(session);
        }
        session.colorCheck = false;
        if (FINISH.stream().anyMatch(s::contains)) {
            return reply(session, DONE, Next.done);
        }
        return null;
    }

    /** 비춘 사진 한 장. 옷마다 "흰색 셔츠예요"처럼 읽고 카메라는 켜 둡니다. */
    public TurnResponse check(AgentSession session, String imageBase64) {
        JsonNode inspect;
        try {
            inspect = tools.call(ToolNames.CLOTHES_INSPECT,
                    Map.of("imageBase64", imageBase64, "allowGpt", Boolean.TRUE.equals(session.gptConsent)));
        } catch (ToolException e) {
            log.warn("clothes_inspect(색 확인) 실패: {}", e.getMessage());
            return reply(session, "미안해요, 지금은 색을 잘 못 봤어요. 한 번 더 비춰 주실래요?", Next.camera_outfit);
        }
        if ("dark".equals(inspect.path("lighting").asString("ok"))) {
            return reply(session, "조금 어두워요. 불을 켜거나 밝은 곳에서 다시 비춰 주세요. 어두우면 색이 다르게 보여요.",
                    Next.camera_outfit);
        }
        List<Garment> garments = new ArrayList<>();
        for (JsonNode g : inspect.path("garments")) {
            garments.add(json.treeToValue(g, Garment.class));
        }
        if (garments.isEmpty()) {
            return reply(session, "옷이 잘 안 보여요. 옷 위로 폰을 들어 주실래요?", Next.camera_outfit);
        }
        for (Garment g : garments) {
            session.colorSeen.add(g.withId("c" + (session.colorSeen.size() + 1) + "-" + g.id()));
        }
        while (session.colorSeen.size() > 4) {
            session.colorSeen.remove(0);
        }
        return reply(session, speech(garments) + " " + MORE, Next.camera_outfit);
    }

    /** 색 확인에서 마지막으로 본 두 벌의 색 어울림(옷 상태 서버의 색상환 계산). */
    private TurnResponse harmony(AgentSession session) {
        List<Garment> seen = session.colorSeen;
        if (seen.size() < 2) {
            return reply(session, "옷을 두 벌 이상 비춰 주시면 서로 어울리는지 알려 드릴게요.", Next.camera_outfit);
        }
        List<Garment> two = seen.subList(seen.size() - 2, seen.size());
        List<Map<String, Object>> garments = new ArrayList<>();
        for (Garment g : two) {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("id", g.id());
            m.put("category", g.category() == null ? "other" : g.category());
            m.put("colorFamily", g.colorFamily() == null ? "unknown" : g.colorFamily());
            if (g.colorName() != null && !g.colorName().isBlank()) {
                m.put("colorName", g.colorName());
            }
            if (g.tone() != null && !g.tone().isBlank()) {
                m.put("tone", g.tone());
            }
            garments.add(m);
        }
        String names = Korean.josa(two.get(0).label(), "과", "와") + " " + two.get(1).label();
        try {
            JsonNode h = tools.call(ToolNames.OUTFIT_COLOR_CHECK, Map.of("garments", garments, "occasion", "casual",
                    "lighting", "ok"));
            if (!h.path("score").isNumber()) {
                return reply(session, "미안해요, " + names + "의 어울림은 지금 잘 모르겠어요.", Next.camera_outfit);
            }
            String reason = h.path("scoreReason").asString("");
            return reply(session, Korean.josa(names, "은", "는") + " 어울림 " + h.path("score").asInt() + "점이에요."
                    + (reason.isBlank() ? "" : " " + reason), Next.camera_outfit);
        } catch (ToolException e) {
            log.warn("outfit_color_check(색 확인) 실패: {}", e.getMessage());
            return reply(session, "미안해요, 지금은 어울림을 못 봤어요. 조금 있다가 다시 물어봐 주실래요?", Next.camera_outfit);
        }
    }

    /** "흰색 셔츠예요." 여러 벌이면 "왼쪽은 흰색 셔츠, 오른쪽은 검정 바지예요." */
    static String speech(List<Garment> garments) {
        List<String> parts = new ArrayList<>();
        boolean many = garments.size() > 1;
        for (Garment g : garments) {
            String where = many ? Korean.position(g.position()) : "";
            String label = describe(g);
            parts.add(where.isEmpty() ? label : Korean.josa(where, "은", "는") + " " + label);
        }
        String said = ieyo(String.join(", ", parts)) + ".";
        String guess = garments.size() == 1 ? patternGuess(garments.getFirst()) : "";
        boolean damaged = garments.stream().anyMatch(g -> !g.defects().isEmpty());
        return said + guess + (damaged ? " 그리고 얼룩이나 찢어진 곳이 있어 보여요. 옷을 봐 달라고 하시면 자세히 알려 드릴게요." : "");
    }

    /** 색을 모르면 "색은 잘 모르겠지만 셔츠" 대신 종류만 말하고 뒤에 덧붙입니다. */
    private static String describe(Garment g) {
        if (g.colorName() == null || g.colorName().isBlank()) {
            return g.label() + "인데 색은 잘 모르겠어요";
        }
        return g.label();
    }

    /** 무늬를 확실히 모를 때 1등 무늬를 약하게 말합니다. */
    private static String patternGuess(Garment g) {
        if (g.pattern() != null || g.patternGuess() == null) {
            return "";
        }
        String name = Garment.patternName(g.patternGuess());
        return name.isEmpty() ? "" : " 무늬는 확실하지 않은데 " + name + " 같아요.";
    }

    /** 끝 말에 "예요/이에요"를 붙입니다. 이미 "~어요"로 끝나면 그대로. */
    static String ieyo(String text) {
        if (text.endsWith("요")) {
            return text;
        }
        return Korean.josa(text, "이에요", "예요");
    }

    private TurnResponse reply(AgentSession session, String speech, Next next) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, next, null);
    }
}
