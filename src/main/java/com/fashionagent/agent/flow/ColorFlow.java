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
            "색알려", "색깔알려", "색확인", "색좀봐", "색봐", "무슨무늬", "어떤무늬");
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
        return reply(session, START, Next.camera_outfit);
    }

    /** 색 확인 중에 한 말. 끝내는 말이면 끝내고, 다른 말이면 색 확인을 접고 null(원래 대화로). */
    public TurnResponse answer(AgentSession session, String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (isColorQuestion(s)) {
            return reply(session, "네, 옷을 비춰 주시면 색을 알려 드릴게요.", Next.camera_outfit);
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
        return reply(session, speech(garments) + " " + MORE, Next.camera_outfit);
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
