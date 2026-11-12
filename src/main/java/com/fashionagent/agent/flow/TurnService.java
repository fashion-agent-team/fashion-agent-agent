package com.fashionagent.agent.flow;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.assistant.Assistant;
import com.fashionagent.agent.judge.Korean;
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
    private final ReminderFlow reminder;
    private final ClosetFlow closetFlow;
    private final Assistant assistant;
    private final AgentTools tools;
    private final SessionStore sessions;
    private final java.time.Clock clock;

    public TurnService(OutfitFlow outfit, FaceFlow face, ReminderFlow reminder, ClosetFlow closetFlow,
            Assistant assistant, AgentTools tools, SessionStore sessions, java.time.Clock clock) {
        this.closetFlow = closetFlow;
        this.outfit = outfit;
        this.face = face;
        this.reminder = reminder;
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
        // 이번 대화에서 이미 답했으면 그 답을 쓰고, 아니면 앱이 기억한 답(없으면 null = 아직 안 물음).
        if (req.allowGpt() != null || !session.gptDecided) {
            session.gptConsent = req.allowGpt();
        }

        if (req.hasImage()) {
            session.checkOffered = false;
            if ("face".equals(req.imageKind())) {
                return face.checkMakeup(session, req.image(), req.faceLandmarks(), req.faceFrames());
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

        // 일정을 넣은 뒤 "일정 전에 알림을 드릴까요?", "며칠 전에 미리 알려 드릴까요?"에 대한 답
        if (session.reminderStep != null) {
            TurnResponse answered = reminder.answer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "옷장에서 ○○를 지울까요?", "몇째 옷을 지울까요?"에 대한 답
        if (session.closetRemoveIds != null) {
            TurnResponse answered = closetFlow.answerRemove(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "애매한 부분을 더 정확한 AI에게 물어봐도 될까요?"에 대한 답
        if (session.gptAsked) {
            TurnResponse answered = outfit.answerGptAsk(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "어떻게 보여 주실래요?"에 대한 답
        if (session.modeAsked > 0) {
            TurnResponse chosen = outfit.answerMode(session, req.utterance());
            if (chosen != null) {
                return chosen;
            }
        }
        // 앱이 기억한 outfitMode는 쓰지 않습니다. 매번 고릅니다(2026-11-06 진행 님 결정).

        // 말로 추천한 뒤 "봐 드릴까요?"에 대한 답
        if (session.checkOffered) {
            TurnResponse answered = outfit.answerCheckOffer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "입은 모습도 확인해 보실래요?"에 대한 답
        if (session.wornOfferAsked) {
            TurnResponse answered = outfit.answerWornOffer(session, req.utterance());
            if (answered != null) {
                return answered;
            }
        }

        // "얼굴을 볼까요, 이를 확인할까요?"에 대한 답
        if (session.faceChoiceAsked > 0) {
            TurnResponse chosen = face.answerChoice(session, req.utterance());
            if (chosen != null) {
                return chosen;
            }
        }

        return switch (Intent.of(req.utterance(), inOutfit)) {
            case OUTFIT -> outfit.start(session, req.location(), req.utterance());
            case CHECK -> outfit.startCheck(session, req.location(), req.utterance());
            case MODE -> outfit.switchMode(session, OutfitFlow.switchFrom(req.utterance()));
            case FINISH -> outfit.finish(session);
            case WHERE -> outfit.where(session);
            case CLOSET -> closetFlow.list(session, req.utterance());
            case CLOSET_HISTORY -> closetFlow.history(session, req.utterance());
            case CLOSET_REMOVE -> closetFlow.startRemove(session, req.utterance());
            case WEATHER -> weather(session, req.location(), req.utterance());
            case SCHEDULE -> schedule(session, req.utterance());
            case FACE -> face.start(session, req.utterance());
            // "할 줄 아는 거 말해 봐": GPT 여부와 상관없이 매번 같은 안내
            case HELP -> say(session, SmallTalk.CAPABILITIES, Next.listen);
            // 그 밖(일정 등록·삭제, 다른 날 일정 등)은 GPT가 도구를 고릅니다(약속 5-2절).
            case OTHER -> {
                Assistant.Reply r = assistant.reply(session, req.utterance(), req.location(), inOutfit);
                // GPT가 말뜻을 보고 우리 기능을 고른 경우(키워드에 안 걸린 말). 그 흐름을 그대로 시작합니다.
                if (r.feature() != null) {
                    yield switch (r.feature()) {
                        case "outfit" -> outfit.start(session, req.location(), req.utterance());
                        case "face" -> face.start(session, "얼굴 확인");
                        case "teeth" -> face.start(session, "이 확인");
                        case "schedule_today" -> schedule(session, null);
                        default -> say(session, r.speech(), r.next());
                    };
                }
                // 방금 일정을 넣었으면 답 뒤에 "일정 전에 알림을 드릴까요?"를 붙이고 답을 기다립니다.
                if (ReminderFlow.PENDING.equals(session.reminderStep)) {
                    yield say(session, reminder.askIfAdded(session, r.speech()), Next.listen);
                }
                yield say(session, r.speech(), r.next());
            }
        };
    }

    /**
     * "오늘 날씨 어때?", "내일 날씨 어때?", "우산 챙겨야 해?" 판단 없이 날씨 값을 읽어 줍니다.
     * 다른 날을 물으면 weather_forecast로 그날 예보를 읽습니다. 2026-11-12 실제 서버: "내일 날씨"에 오늘 날씨로 답했습니다.
     */
    private TurnResponse weather(AgentSession session, Location location, String utterance) {
        Location at = location == null ? Location.SEOUL : location;
        var today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        var asked = SpokenDate.from(utterance, today);
        boolean otherDay = asked != null && !asked.equals(today);
        String label = SpokenDate.labelAsAsked(utterance, asked, today);
        try {
            JsonNode w = otherDay
                    ? tools.call(ToolNames.WEATHER_FORECAST, Map.of("lat", at.lat(), "lon", at.lon(), "date", asked.toString()))
                    : tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", at.lat(), "lon", at.lon()));
            String speech = weatherSpeech(w, otherDay ? label : null, utterance);
            sessions.save(session);
            return new TurnResponse(session.sessionId, speech, Next.listen, Result.of("weather"));
        } catch (ToolException e) {
            if ("out_of_range".equals(e.code())) {
                return say(session, label + " 날씨는 아직 알 수 없어요. 16일 안쪽 날씨만 알려 드릴 수 있어요.", Next.listen);
            }
            return say(session, "미안해요, 지금은 날씨를 못 가져왔어요. 조금 있다가 다시 물어봐 주실래요?", Next.listen);
        }
    }

    /** day가 null이면 지금 날씨, 아니면 "내일은 비, 최저 3도 최고 13도예요" 꼴. 우산을 물었으면 우산 답을 먼저. */
    static String weatherSpeech(JsonNode w, String day, String utterance) {
        String condition = w.path("condition").asString("");
        long min = Math.round(w.path("minTemperature").asDouble());
        long max = Math.round(w.path("maxTemperature").asDouble());
        int rain = w.path("precipitationProbability").asInt(0);
        boolean umbrella = w.path("needsUmbrella").asBoolean(false);
        String u = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        boolean askedRain = u.contains("우산") || u.contains("비") || u.contains("눈와") || u.contains("눈올")
                || u.contains("눈오") || u.contains("눈이");

        StringBuilder sb = new StringBuilder();
        String feel = feeling(u, w.path("apparentTemperature").asDouble(), day);
        if (feel != null) {
            sb.append(feel).append(" ");
        }
        if (askedRain) {
            sb.append(umbrella
                    ? "우산 챙기세요. 비나 눈 올 확률이 " + rain + "%예요. "
                    : "우산은 안 챙기셔도 돼요. 비 올 확률이 " + rain + "%예요. ");
        }
        if (day == null) {
            sb.append("지금 ").append(condition).append(", ")
                    .append(Math.round(w.path("temperature").asDouble())).append("도이고 체감 ")
                    .append(Math.round(w.path("apparentTemperature").asDouble())).append("도예요. ");
        } else {
            sb.append(Korean.josa(day, "은", "는")).append(" ").append(condition).append(", ");
        }
        sb.append("최저 ").append(min).append("도, 최고 ").append(max).append("도예요. ");
        if (!askedRain && umbrella) {
            sb.append("비 올 확률이 ").append(rain).append("%라 우산을 챙기세요. ");
        }
        sb.append(w.path("clothingAdvice").asString(""));
        return sb.toString().trim();
    }

    /**
     * "추워?", "더워?"에는 체감 온도로 먼저 한마디 답합니다. 2026-11-12 실제 서버: 날씨만 읽어 줘서 답이 안 됐습니다.
     * 기준은 mcp-weather의 옷 조언과 비슷하게 잡았습니다(체감 5도 이하 추움, 12도 이하 쌀쌀, 28도 이상 더움).
     */
    static String feeling(String u, double apparent, String day) {
        boolean cold = u.contains("추워") || u.contains("춥") || u.contains("추운") || u.contains("쌀쌀");
        boolean hot = u.contains("더워") || u.contains("덥") || u.contains("더운");
        if (cold == hot) {
            return null;
        }
        String when = day == null ? "지금" : Korean.josa(day, "은", "는");
        long t = Math.round(apparent);
        if (cold) {
            if (apparent <= 5) {
                return "네, " + when + " 추워요. 체감 " + t + "도예요.";
            }
            if (apparent <= 12) {
                return "조금 쌀쌀해요. 체감 " + t + "도예요.";
            }
            if (apparent < 20) {
                return "춥지는 않고 선선해요. 체감 " + t + "도예요.";
            }
            return "아니요, 춥지 않아요. 체감 " + t + "도예요.";
        }
        if (apparent >= 28) {
            return "네, " + when + " 더워요. 체감 " + t + "도예요.";
        }
        if (apparent >= 23) {
            return "조금 더워요. 체감 " + t + "도예요.";
        }
        return "아니요, 덥지 않아요. 체감 " + t + "도예요.";
    }

    /** "오늘 일정 뭐야?", "11월 20일 일정 뭐야?" */
    private TurnResponse schedule(AgentSession session, String utterance) {
        var today = java.time.LocalDate.now(clock.withZone(java.time.ZoneId.of("Asia/Seoul")));
        var asked = SpokenDate.from(utterance, today);
        var day = asked == null ? today : asked;
        String label = SpokenDate.label(day, today);
        try {
            JsonNode r = tools.callForUser(ToolNames.SCHEDULE_LIST, session.userId, Map.of("date", day.toString()));
            JsonNode events = r.path("events");
            if (events.isEmpty()) {
                return say(session, label + "은 일정이 없어요.", Next.listen);
            }
            StringBuilder sb = new StringBuilder(label + " 일정은 ");
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
            return say(session, "미안해요, 지금은 일정을 못 가져왔어요. 조금 있다가 다시 물어봐 주실래요?", Next.listen);
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
