package com.fashionagent.agent.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.api.TurnResponse.Result;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;

/**
 * 얼굴 확인 (개별 기능, 약속 5-2절). 찍는 방법이 달라서 얼굴(화장 대칭)과 치아를 나눠 봅니다.
 * "얼굴 확인해 줘" → "얼굴을 볼까요, 이를 확인할까요?" → 고른 쪽 카메라 → 앱이 자동으로 보낸 한 장
 * → face_makeup_check 또는 teeth_check → 안내. 처음부터 "이 확인해 줘"처럼 말하면 묻지 않습니다.
 * 도구가 이미 한국어 문장(notes, note)을 주므로 GPT 없이 코드로 이어 붙여 바로 답합니다.
 */
@Component
public class FaceFlow {

    private static final Logger log = LoggerFactory.getLogger(FaceFlow.class);
    /** 이 값보다 확신이 낮으면 "낀 것 같다"고 단정하지 않습니다. */
    static final double TEETH_SURE = 0.6;
    /** 얼굴/이 중 고르라고 묻는 최대 횟수. 그래도 모르면 원래 대화로 돌아갑니다. */
    static final int MAX_CHOICE_ASKS = 2;

    static final String ASK_CHOICE = "얼굴 화장을 볼까요, 이를 확인할까요?";
    static final String START_MAKEUP = "얼굴 화장을 볼게요. 폰을 얼굴 앞에 들어 주세요. 잘 보이면 자동으로 찍을게요.";
    static final String START_TEETH = "이를 확인할게요. 폰을 입 가까이 대고 '이' 하고 입을 벌려 주세요. 잘 보이면 자동으로 찍을게요.";

    /** 처음 말에서 치아를 알아보는 말. "이쪽 옷"처럼 흔한 말과 겹치지 않게 좁게 둡니다. */
    private static final List<String> TEETH_WORDS = List.of("치아", "이빨", "이사이", "이확인", "이에낀", "이에뭐", "입안");
    /** "얼굴을 볼까요, 이를 확인할까요?"에 대한 답에서만 쓰는 짧은 말. */
    private static final List<String> TEETH_ANSWERS = List.of("이", "이요", "이를", "이로", "이부터", "이확인해줘");
    private static final List<String> MAKEUP_WORDS = List.of("화장", "얼굴", "눈썹", "입술", "메이크업");

    private final AgentTools tools;
    private final SessionStore sessions;

    public FaceFlow(AgentTools tools, SessionStore sessions) {
        this.tools = tools;
        this.sessions = sessions;
    }

    /** 공백을 뺀 말에 치아 이야기가 있는지. */
    static boolean isTeeth(String compact) {
        return TEETH_WORDS.stream().anyMatch(compact::contains);
    }

    static boolean isMakeup(String compact) {
        return MAKEUP_WORDS.stream().anyMatch(compact::contains);
    }

    public TurnResponse start(AgentSession session, String utterance) {
        String s = compact(utterance);
        if (isTeeth(s)) {
            return startTeeth(session);
        }
        if (s.contains("화장") || s.contains("눈썹") || s.contains("입술") || s.contains("메이크업")) {
            return startMakeup(session);
        }
        session.faceChoiceAsked = 1;
        return reply(session, ASK_CHOICE, Next.listen, null);
    }

    /** 고르라고 물은 뒤의 답. 얼굴/이 중 하나를 알아들으면 시작하고, 아니면 한 번 더 묻거나 null(원래 대화로). */
    public TurnResponse answerChoice(AgentSession session, String utterance) {
        String s = compact(utterance);
        if (isTeeth(s) || TEETH_ANSWERS.contains(s) || s.startsWith("이를") || s.startsWith("이요")) {
            return startTeeth(session);
        }
        if (isMakeup(s)) {
            return startMakeup(session);
        }
        if (session.faceChoiceAsked < MAX_CHOICE_ASKS && s.length() <= 6) {
            session.faceChoiceAsked++;
            return reply(session, "얼굴이라고 하시면 화장을, 이라고 하시면 치아를 볼게요. " + ASK_CHOICE, Next.listen, null);
        }
        session.faceChoiceAsked = 0;
        return null;
    }

    private TurnResponse startMakeup(AgentSession session) {
        session.faceChoiceAsked = 0;
        return reply(session, START_MAKEUP, Next.camera_face, null);
    }

    private TurnResponse startTeeth(AgentSession session) {
        session.faceChoiceAsked = 0;
        return reply(session, START_TEETH, Next.camera_teeth, null);
    }

    public TurnResponse checkMakeup(AgentSession session, String imageBase64, Map<String, Object> landmarks) {
        JsonNode r = call(ToolNames.FACE_MAKEUP_CHECK, imageBase64, "landmarks", landmarks);
        if (r == null) {
            return reply(session, "지금은 얼굴을 확인하지 못했어요. 잠시 뒤에 다시 해 주세요.", Next.listen, null);
        }
        TurnResponse retake = retake(session, r, Next.camera_face, "얼굴을");
        return retake != null ? retake
                : reply(session, makeupSpeech(r), Next.listen, Result.of(ToolNames.FACE_MAKEUP_CHECK));
    }

    public TurnResponse checkTeeth(AgentSession session, String imageBase64, Map<String, Object> mouthBox) {
        JsonNode r = call(ToolNames.TEETH_CHECK, imageBase64, "mouthBox", mouthBox);
        if (r == null) {
            return reply(session, "지금은 이를 확인하지 못했어요. 잠시 뒤에 다시 해 주세요.", Next.listen, null);
        }
        TurnResponse retake = retake(session, r, Next.camera_teeth, "입을");
        return retake != null ? retake
                : reply(session, teethSpeech(r), Next.listen, Result.of(ToolNames.TEETH_CHECK));
    }

    private JsonNode call(String tool, String imageBase64, String extraKey, Map<String, Object> extra) {
        Map<String, Object> args = new HashMap<>();
        args.put("imageBase64", imageBase64);
        if (extra != null && !extra.isEmpty()) {
            args.put(extraKey, extra);
        }
        try {
            return tools.call(tool, args);
        } catch (ToolException e) {
            log.warn("{} 실패: {} {}", tool, e.code(), e.getMessage());
            return null;
        }
    }

    /** 구도가 안 맞거나 어두우면 그 안내만 하고 카메라를 켜 둡니다. 다시 찍을 일이 없으면 null. */
    private TurnResponse retake(AgentSession session, JsonNode r, Next camera, String what) {
        String framing = r.path("framing").asString("");
        if (!framing.isBlank()) {
            return reply(session, framing, camera, null);
        }
        if ("dark".equals(r.path("lighting").asString(""))) {
            return reply(session, "조금 어두워요. 밝은 곳에서 다시 " + what + " 비춰 주세요.", camera, null);
        }
        return null;
    }

    static String makeupSpeech(JsonNode r) {
        JsonNode symmetry = r.path("symmetry");
        if (symmetry.path("ok").asBoolean(true)) {
            return "화장은 양쪽이 고르게 잘 됐어요.";
        }
        List<String> parts = new ArrayList<>();
        symmetry.path("notes").valueStream().map(n -> n.asString("")).filter(s -> !s.isBlank()).forEach(parts::add);
        return parts.isEmpty() ? "화장이 양쪽이 조금 달라 보여요. 거울로 한 번 확인해 보세요." : String.join(" ", parts);
    }

    static String teethSpeech(JsonNode r) {
        JsonNode teeth = r.path("teeth");
        if (!teeth.path("foreignObject").asBoolean(false)) {
            return "이에 낀 것은 없어 보여요.";
        }
        String note = teeth.path("note").asString("이에 뭔가 낀 것 같아요.");
        if (teeth.path("confidence").asDouble(1) >= TEETH_SURE) {
            return note + " 혀로 한 번 확인해 보세요.";
        }
        return "이에 뭔가 있는지는 확실하지 않아요. 한 번 확인해 보세요.";
    }

    private static String compact(String utterance) {
        return utterance == null ? "" : utterance.replaceAll("[\\s.,?!~]+", "");
    }

    private TurnResponse reply(AgentSession session, String speech, Next next, Result result) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, next, result);
    }
}
