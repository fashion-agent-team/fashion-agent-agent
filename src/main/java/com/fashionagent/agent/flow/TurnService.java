package com.fashionagent.agent.flow;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.api.TurnRequest;
import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.api.TurnResponse.Result;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;

/** 대화 한 번을 어느 흐름으로 보낼지 정합니다. */
@Service
public class TurnService {

    private final OutfitFlow outfit;
    private final AgentTools tools;
    private final SessionStore sessions;

    public TurnService(OutfitFlow outfit, AgentTools tools, SessionStore sessions) {
        this.outfit = outfit;
        this.tools = tools;
        this.sessions = sessions;
    }

    public TurnResponse handle(String userId, TurnRequest req) {
        AgentSession session = req.sessionId() == null ? null
                : sessions.find(userId, req.sessionId()).orElse(null);
        boolean inOutfit = session != null && (session.advice != null || !session.seen.isEmpty());
        if (session == null) {
            session = new AgentSession("s_" + UUID.randomUUID(), userId);
        }

        if (req.hasImage()) {
            if ("face".equals(req.imageKind())) {
                return say(session, "얼굴 확인은 아직 준비 중이에요.", Next.listen);
            }
            return outfit.check(session, req.image());
        }

        return switch (Intent.of(req.utterance(), inOutfit)) {
            case OUTFIT -> outfit.advise(session, req.location());
            case FINISH -> outfit.finish(session);
            case WHERE -> outfit.where(session);
            case WEATHER -> weather(session, req.location());
            case SCHEDULE -> schedule(session);
            case FACE -> say(session, "얼굴 확인은 아직 준비 중이에요.", Next.listen);
            // TODO: 개별 기능(일정 등록, 가족에게 묻기)은 Gemini 도구 선택으로 (README 할 일)
            case UNKNOWN -> say(session, "아직 그 말은 잘 모르겠어요. \"오늘 뭐 입지?\"라고 말해 보세요.",
                    inOutfit ? Next.camera_outfit : Next.listen);
        };
    }

    /** "오늘 날씨 어때?" 판단 없이 weather_today 값을 읽어 줍니다. */
    private TurnResponse weather(AgentSession session, Location location) {
        Location at = location == null ? Location.SEOUL : location;
        try {
            JsonNode w = tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", at.lat(), "lon", at.lon()));
            String speech = "지금 " + w.path("condition").asString("") + ", " + Math.round(w.path("temperature").asDouble())
                    + "도이고 체감 " + Math.round(w.path("apparentTemperature").asDouble()) + "도예요. 최저 "
                    + Math.round(w.path("minTemperature").asDouble()) + "도, 최고 "
                    + Math.round(w.path("maxTemperature").asDouble()) + "도예요. "
                    + w.path("clothingAdvice").asString("");
            sessions.save(session);
            return new TurnResponse(session.sessionId, speech.trim(), Next.listen, Result.of("weather"));
        } catch (ToolException e) {
            return say(session, "지금은 날씨를 불러오지 못했어요.", Next.listen);
        }
    }

    /** "오늘 일정 뭐야?" */
    private TurnResponse schedule(AgentSession session) {
        try {
            JsonNode r = tools.callForUser(ToolNames.SCHEDULE_LIST, session.userId,
                    Map.of("date", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).toString()));
            JsonNode events = r.path("events");
            if (events.isEmpty()) {
                return say(session, "오늘은 일정이 없어요.", Next.listen);
            }
            StringBuilder sb = new StringBuilder("오늘 일정은 ");
            for (int i = 0; i < events.size(); i++) {
                JsonNode e = events.get(i);
                String start = e.path("start").asString("");
                String time = start.length() >= 16 ? start.substring(11, 16) : "";
                sb.append(i == 0 ? "" : ", ").append(time).append(" ").append(e.path("title").asString(""));
            }
            sb.append("이에요.");
            sessions.save(session);
            return new TurnResponse(session.sessionId, sb.toString(), Next.listen, Result.of("schedule"));
        } catch (ToolException e) {
            return say(session, "지금은 일정을 불러오지 못했어요.", Next.listen);
        }
    }

    private TurnResponse say(AgentSession session, String speech, Next next) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, next, null);
    }
}
