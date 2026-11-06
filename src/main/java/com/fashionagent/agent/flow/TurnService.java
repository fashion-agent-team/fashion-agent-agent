package com.fashionagent.agent.flow;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.assistant.Assistant;
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
    private final FaceFlow face;
    private final Assistant assistant;
    private final AgentTools tools;
    private final SessionStore sessions;
    private final java.time.Clock clock;

    public TurnService(OutfitFlow outfit, FaceFlow face, Assistant assistant, AgentTools tools,
            SessionStore sessions, java.time.Clock clock) {
        this.outfit = outfit;
        this.face = face;
        this.assistant = assistant;
        this.tools = tools;
        this.sessions = sessions;
        this.clock = clock;
    }

    public TurnResponse handle(String userId, TurnRequest req) {
        AgentSession session = req.sessionId() == null ? null
                : sessions.find(userId, req.sessionId()).orElse(null);
        boolean inOutfit = session != null && (session.advice != null || !session.seen.isEmpty());
        if (session == null) {
            session = new AgentSession("s_" + UUID.randomUUID(), userId);
        }

        if (req.hasImage()) {
            // 가족에게 묻기에 쓰려고 직전 사진 한 장만 10분 들고 있습니다.
            session.keepPhoto(req.image(), clock);
            if ("face".equals(req.imageKind())) {
                return face.checkMakeup(session, req.image(), req.faceLandmarks());
            }
            if ("teeth".equals(req.imageKind())) {
                return face.checkTeeth(session, req.image(), req.mouthBox());
            }
            if ("worn".equals(req.imageKind())) {
                session.outfitMode = OutfitFlow.WORN;
                return outfit.checkWorn(session, req.image());
            }
            return outfit.check(session, req.image());
        }

        // "어떻게 보여 주실래요?"에 대한 답
        if (session.modeAsked > 0) {
            TurnResponse chosen = outfit.answerMode(session, req.utterance());
            if (chosen != null) {
                return chosen;
            }
        }
        if (session.outfitMode == null) {
            session.outfitMode = OutfitFlow.parseMode(req.outfitMode());
        }

        // "얼굴을 볼까요, 이를 확인할까요?"에 대한 답
        if (session.faceChoiceAsked > 0) {
            TurnResponse chosen = face.answerChoice(session, req.utterance());
            if (chosen != null) {
                return chosen;
            }
        }

        return switch (Intent.of(req.utterance(), inOutfit)) {
            case OUTFIT -> outfit.start(session, req.location(), req.outfitMode());
            case MODE -> outfit.switchMode(session, OutfitFlow.switchFrom(req.utterance()));
            case FINISH -> outfit.finish(session);
            case WHERE -> outfit.where(session);
            case WEATHER -> weather(session, req.location());
            case SCHEDULE -> schedule(session);
            case FACE -> face.start(session, req.utterance());
            // "할 줄 아는 거 말해 봐": GPT 여부와 상관없이 매번 같은 안내
            case HELP -> say(session, SmallTalk.CAPABILITIES, inOutfit ? Next.camera_outfit : Next.listen);
            // 그 밖(일정 등록·삭제, 다른 날 일정, 가족에게 묻기 등)은 GPT가 도구를 고릅니다(약속 5-2절).
            case OTHER -> {
                Assistant.Reply r = assistant.reply(session, req.utterance(), req.location(), inOutfit);
                // GPT가 말뜻을 보고 우리 기능을 고른 경우(키워드에 안 걸린 말). 그 흐름을 그대로 시작합니다.
                if (r.feature() != null) {
                    yield switch (r.feature()) {
                        case "outfit" -> outfit.start(session, req.location(), req.outfitMode());
                        case "face" -> face.start(session, "얼굴 확인");
                        case "teeth" -> face.start(session, "이 확인");
                        case "schedule_today" -> schedule(session);
                        default -> say(session, r.speech(), r.next());
                    };
                }
                yield say(session, r.speech(), r.next());
            }
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
                String time = spokenTime(start);
                sb.append(i == 0 ? "" : ", ").append(time).append(" ").append(e.path("title").asString(""));
            }
            sb.append("이에요.");
            sessions.save(session);
            return new TurnResponse(session.sessionId, sb.toString(), Next.listen, Result.of("schedule"));
        } catch (ToolException e) {
            return say(session, "지금은 일정을 불러오지 못했어요.", Next.listen);
        }
    }

    /** "2026-11-04T14:00:00+09:00" → "오후 2시", 분이 있으면 "오후 2시 30분". */
    static String spokenTime(String iso) {
        try {
            var t = java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.of("Asia/Seoul"));
            int h = t.getHour();
            String ampm = h < 12 ? "오전" : "오후";
            int h12 = h % 12 == 0 ? 12 : h % 12;
            return ampm + " " + h12 + "시" + (t.getMinute() == 0 ? "" : " " + t.getMinute() + "분");
        } catch (RuntimeException e) {
            return "";
        }
    }

    private TurnResponse say(AgentSession session, String speech, Next next) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, next, null);
    }
}
