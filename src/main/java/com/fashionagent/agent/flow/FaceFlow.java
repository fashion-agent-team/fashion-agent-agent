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
 * 얼굴 확인 (개별 기능, 약속 5-2절). "얼굴 확인해 줘" → 카메라 켜기, 앱이 자동으로 보낸 한 장 → face_check → 안내.
 * face_check가 이미 한국어 문장(notes, note)을 주므로 Gemini 없이 코드로 이어 붙여 바로 답합니다.
 */
@Component
public class FaceFlow {

    private static final Logger log = LoggerFactory.getLogger(FaceFlow.class);
    /** 이 값보다 확신이 낮으면 "낀 것 같다"고 단정하지 않습니다. */
    static final double TEETH_SURE = 0.6;

    private final AgentTools tools;
    private final SessionStore sessions;

    public FaceFlow(AgentTools tools, SessionStore sessions) {
        this.tools = tools;
        this.sessions = sessions;
    }

    public TurnResponse start(AgentSession session) {
        sessions.save(session);
        return new TurnResponse(session.sessionId,
                "얼굴 확인을 시작할게요. 폰을 얼굴 앞에 들어 주세요. 잘 보이면 자동으로 찍을게요.", Next.camera_face, null);
    }

    public TurnResponse check(AgentSession session, String imageBase64, Map<String, Object> landmarks) {
        Map<String, Object> args = new HashMap<>();
        args.put("imageBase64", imageBase64);
        if (landmarks != null && !landmarks.isEmpty()) {
            args.put("landmarks", landmarks);
        }
        JsonNode r;
        try {
            r = tools.call(ToolNames.FACE_CHECK, args);
        } catch (ToolException e) {
            log.warn("face_check 실패: {} {}", e.code(), e.getMessage());
            return reply(session, "지금은 얼굴을 확인하지 못했어요. 잠시 뒤에 다시 해 주세요.", Next.listen, null);
        }
        String framing = r.path("framing").asString("");
        if (!framing.isBlank()) {
            return reply(session, framing, Next.camera_face, null);
        }
        if ("dark".equals(r.path("lighting").asString(""))) {
            return reply(session, "조금 어두워요. 밝은 곳에서 다시 얼굴을 비춰 주세요.", Next.camera_face, null);
        }
        return reply(session, speech(r), Next.listen, Result.of("face_check"));
    }

    static String speech(JsonNode r) {
        List<String> parts = new ArrayList<>();
        JsonNode symmetry = r.path("symmetry");
        if (symmetry.path("ok").asBoolean(true)) {
            parts.add("화장은 양쪽이 고르게 잘 됐어요.");
        } else {
            symmetry.path("notes").valueStream().map(n -> n.asString("")).filter(s -> !s.isBlank())
                    .forEach(parts::add);
        }
        JsonNode teeth = r.path("teeth");
        if (teeth.path("foreignObject").asBoolean(false)) {
            String note = teeth.path("note").asString("이에 뭔가 낀 것 같아요.");
            if (teeth.path("confidence").asDouble(1) >= TEETH_SURE) {
                parts.add(note + " 혀로 한 번 확인해 보세요.");
            } else {
                parts.add("이에 뭔가 있는지는 확실하지 않아요. 한 번 확인해 보세요.");
            }
        } else {
            parts.add("이에 낀 것은 없어 보여요.");
        }
        return String.join(" ", parts);
    }

    private TurnResponse reply(AgentSession session, String speech, Next next, Result result) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, next, result);
    }
}
