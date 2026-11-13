package com.fashionagent.agent.flow;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.api.TurnResponse.Result;
import com.fashionagent.agent.config.AgentProperties;
import com.fashionagent.agent.judge.Korean;
import com.fashionagent.agent.judge.OutfitJudge;
import com.fashionagent.agent.judge.SameGarmentJudge;
import com.fashionagent.agent.judge.OutfitJudge.AdviceInput;
import com.fashionagent.agent.judge.OutfitJudge.CheckInput;
import com.fashionagent.agent.judge.OutfitJudge.SeenBrief;
import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
import com.fashionagent.agent.judge.Occasion;
import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.model.OutfitCheck;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 옷 추천 2단계 (약속 5-1절). 도구를 부르는 순서는 여기 코드로 정해져 있고, GPT는 판단만 합니다.
 * <ol>
 * <li>{@link #advise}: weather_today + schedule_list (+ closet_search) → 추천 모드 → next=camera_outfit</li>
 * <li>{@link #check}: 자동 전송된 한 장 → clothes_inspect → 확인 모드 → next=camera_outfit</li>
 * <li>{@link #where}: "어디야?" → 직전 얼룩 위치</li>
 * <li>{@link #finish}: "이걸로 할게" → closet_save + closet_wear → next=done</li>
 * </ol>
 * 옷을 보여 주는 방식은 두 가지이고 사용자가 고릅니다(앱이 기억해서 outfitMode로 보냄).
 * <ul>
 * <li>laid: 옷을 내려놓고 위에서 비추기 → camera_outfit, 한 장마다 판단</li>
 * <li>worn: 입고 폰을 세워 둔 채 한 바퀴 돌기 → camera_worn, 앞·뒤 두 장을 받은 뒤 한 번 판단 ({@link #checkWorn})</li>
 * </ul>
 */
@Component
public class OutfitFlow {

    private static final Logger log = LoggerFactory.getLogger(OutfitFlow.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AgentTools tools;
    private final SameGarmentJudge sameGarment;
    private final OutfitJudge judge;
    private final SessionStore sessions;
    private final AgentProperties props;
    private final ExecutorService executor;
    private final JsonMapper json;
    private final Clock clock;

    public OutfitFlow(AgentTools tools, OutfitJudge judge, SessionStore sessions, AgentProperties props,
            ExecutorService agentExecutor, JsonMapper json, Clock clock, SameGarmentJudge sameGarment) {
        this.sameGarment = sameGarment;
        this.tools = tools;
        this.judge = judge;
        this.sessions = sessions;
        this.props = props;
        this.executor = agentExecutor;
        this.json = json;
        this.clock = clock;
    }

    // ── 보여 주는 방식 ────────────────────────────────────────────────

    public static final String LAID = "laid";
    public static final String WORN = "worn";
    // 2026-11-09 리허설: 카메라 안내가 길고 어렵다는 의견으로 짧게 줄였습니다.
    // 2026-11-09 발표 피드백: "내려놓고 찍으실래요, 입어 보고 돌아보는 거 찍으실래요?"
    static final String ASK_MODE = "옷을 내려놓고 찍으실래요, 입어 보고 한 바퀴 도는 모습을 찍으실래요?";
    static final String LAID_HINT = "옷을 바닥에 펼쳐 놓고 폰으로 비춰 주세요.";
    static final String WORN_HINT = "폰을 가슴 높이에 세워 두고, 두세 걸음 떨어져 서 주세요.";
    static final int MAX_MODE_ASKS = 2;

    private static final List<String> WORN_WORDS = List.of("입고", "입은채", "입어서", "입은거", "한바퀴", "돌게", "돌기",
            "돌면서", "입어보", "입어볼", "돌아보", "돌아볼", "도는", "입은모습");
    /** 옷 추천 중 방식을 바꾸는 말에만 있는 낱말. "흰 셔츠 입고 갈게"는 바꾸기가 아닙니다. */
    private static final List<String> SWITCH_WORDS = List.of("볼게", "봐줘", "봐주", "보여줄", "보여드", "방식", "바꿔",
            "확인해");
    private static final List<String> LAID_WORDS = List.of("내려놓", "펼쳐", "놓고", "바닥", "침대", "비추", "비춰");

    /** 말에서 고른 방식. 모르면 null. */
    public static String modeFrom(String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        if (WORN_WORDS.stream().anyMatch(s::contains)) {
            return WORN;
        }
        if (LAID_WORDS.stream().anyMatch(s::contains)) {
            return LAID;
        }
        return null;
    }

    /** 옷 추천 중 "입고 볼게"처럼 방식을 바꾸는 말이면 그 방식, 아니면 null. */
    public static String switchFrom(String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        return SWITCH_WORDS.stream().anyMatch(s::contains) ? modeFrom(s) : null;
    }

    /** 앱이 보낸 outfitMode. laid·worn이 아니면 null. */
    public static String parseMode(String raw) {
        return LAID.equals(raw) || WORN.equals(raw) ? raw : null;
    }

    private static Next cameraFor(String mode) {
        return WORN.equals(mode) ? Next.camera_worn : Next.camera_outfit;
    }

    /**
     * "오늘 뭐 입지?" 날씨·일정으로 먼저 말로 추천만 합니다. 바로 카메라를 켜지 않습니다.
     * 2026-11-06 진행 님 요청: 추천 → "옷 봐 드릴까요?" → 봐 달라고 하면 그때 보여 주는 방식(내려놓기/입고 한 바퀴)을 고르고 카메라.
     * 말에 방식이 있으면("입고 볼게, 뭐 입지?") 기억해 두었다가 방식은 묻지 않습니다. 앱이 기억한 방식은 쓰지 않고 매번 고릅니다.
     */
    public TurnResponse start(AgentSession session, Location location, String utterance) {
        LocalDate day = SpokenDate.from(utterance, today());
        session.targetDate = day == null || day.equals(today()) ? null : day.toString();
        session.outfitMode = modeFrom(utterance);
        session.modeAsked = 0;
        TurnResponse advice = advise(session, location);
        session.checkOffered = true;
        sessions.save(session);
        // 2026-11-09 진행 님: 추천은 "~을 추천드려요"까지만 말합니다. "옷을 봐 드릴까요?"는 묻지 않고,
        // 사용자가 이어서 "봐 줘", "입고 볼게"라고 하면 그때 방식을 묻거나 카메라를 켭니다(checkOffered).
        return new TurnResponse(session.sessionId, session.advice.speech(), Next.listen, advice.result());
    }

    /**
     * "옷 검사해 줘": 추천을 말로 길게 하지 않고 바로 보여 주는 방식을 묻거나 카메라를 켭니다(2026-11-09 리허설).
     * 날씨·일정은 옷 판단에 필요해서 불러 두기만 합니다.
     */
    public TurnResponse startCheck(AgentSession session, Location location, String utterance) {
        session.outfitMode = modeFrom(utterance);
        session.modeAsked = 0;
        session.checkOffered = false;
        advise(session, location);
        if (session.outfitMode == null) {
            session.modeAsked = 1;
            sessions.save(session);
            return new TurnResponse(session.sessionId, ASK_MODE, Next.listen, Result.of("outfit_mode"));
        }
        return toCamera(session, "");
    }

    /** "봐 드릴까요?"에 대한 답. 알아듣지 못하면 null(원래 대화로). */
    public TurnResponse answerCheckOffer(AgentSession session, String utterance) {
        session.checkOffered = false;
        String mode = modeFrom(utterance);
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (otherFeature(utterance)) {
            // "아니 그냥 날씨 어때?"처럼 다른 기능을 말하면 답이 아니라 새 요청입니다.
            sessions.save(session);
            return null;
        }
        if (mode == null && NO.stream().anyMatch(s::contains)) {
            sessions.save(session);
            return new TurnResponse(session.sessionId, "그럼요, 알겠어요. 옷 고르다가 봐 줄 게 생기면 언제든 불러 주세요.", Next.done, null);
        }
        if (mode != null) {
            session.outfitMode = mode;
        } else if (!YES.stream().anyMatch(s::startsWith) && !s.contains("봐줘") && !s.contains("봐주")) {
            sessions.save(session);
            return null;
        }
        if (session.outfitMode == null) {
            session.modeAsked = 1;
            sessions.save(session);
            return new TurnResponse(session.sessionId, ASK_MODE, Next.listen, Result.of("outfit_mode"));
        }
        return toCamera(session, "좋아요. ");
    }

    /** 고른 방식으로 카메라를 켭니다. */
    private TurnResponse toCamera(AgentSession session, String prefix) {
        String mode = session.outfitMode;
        session.wornStep = null;
        session.wornGarments = new ArrayList<>();
        sessions.save(session);
        return new TurnResponse(session.sessionId, prefix + (WORN.equals(mode) ? WORN_HINT : LAID_HINT),
                cameraFor(mode), new Result("outfit_mode", null, null, mode));
    }

    /** "어떻게 보여 주실래요?"에 대한 답. 알아들으면 추천으로, 아니면 한 번 더 묻거나 null(원래 대화로). */
    public TurnResponse answerMode(AgentSession session, String utterance) {
        String mode = modeFrom(utterance);
        if (mode == null) {
            String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
            if (session.modeAsked < MAX_MODE_ASKS && s.length() <= 10) {
                session.modeAsked++;
                sessions.save(session);
                return new TurnResponse(session.sessionId,
                        "\"내려놓고 볼게\" 또는 \"입고 볼게\"라고 말씀해 주세요.", Next.listen, Result.of("outfit_mode"));
            }
            session.modeAsked = 0;
            return null;
        }
        session.modeAsked = 0;
        session.outfitMode = mode;
        if (session.advice != null) {
            // 추천은 이미 했으니 바로 카메라
            return toCamera(session, "좋아요. ");
        }
        Location at = session.pendingLocation;
        session.pendingLocation = null;
        TurnResponse advice = advise(session, at);
        return new TurnResponse(advice.sessionId(), "좋아요. " + advice.speech(),
                advice.next(), advice.result());
    }

    /** 옷 추천 중 "입고 볼게", "내려놓고 볼게"로 방식을 바꿉니다. */
    public TurnResponse switchMode(AgentSession session, String mode) {
        session.outfitMode = mode;
        session.wornStep = null;
        session.wornGarments = new ArrayList<>();
        sessions.save(session);
        String speech = WORN.equals(mode) ? "입고 한 바퀴 돌면서 볼게요. " + WORN_HINT
                : "내려놓고 볼게요. " + LAID_HINT;
        return new TurnResponse(session.sessionId, speech, cameraFor(mode),
                new Result("outfit_mode", null, null, mode));
    }

    /** 추천 문장 끝의 카메라 안내("옷을 꺼내서 카메라에 보여 주세요")를 뺍니다. */
    static String withoutCameraHint(String speech) {
        String s = speech == null ? "" : speech.trim();
        int cut = Math.max(s.lastIndexOf(". ", s.length() - 2), s.lastIndexOf("요 ", s.length() - 2));
        String last = cut < 0 ? s : s.substring(cut + 1).trim();
        if (last.contains("카메라") || last.contains("보여 주") || last.contains("보여주")) {
            s = cut < 0 ? "" : s.substring(0, cut + 1).trim();
        }
        return s;
    }

    /** 추천 문장 끝의 카메라 안내를 고른 방식에 맞게 바꿉니다. */
    static String withCameraHint(String speech, String mode) {
        String s = speech == null ? "" : speech.trim();
        int cut = Math.max(s.lastIndexOf(". ", s.length() - 2), s.lastIndexOf("요 ", s.length() - 2));
        String last = cut < 0 ? s : s.substring(cut + 1).trim();
        if (last.contains("카메라") || last.contains("보여 주") || last.contains("보여주")) {
            s = cut < 0 ? "" : s.substring(0, cut + 1).trim();
        }
        String hint = WORN.equals(mode) ? WORN_HINT : LAID_HINT;
        return (s.isEmpty() ? "" : s + " ") + hint;
    }

    // ── 1단계: 말로 추천 ──────────────────────────────────────────────

    public TurnResponse advise(AgentSession session, Location location) {
        Location at = location == null ? Location.SEOUL : location;
        LocalDate day = targetDay(session);
        boolean isToday = day.equals(today());
        String[] weatherNote = {null};
        var weather = isToday
                ? async(() -> tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", at.lat(), "lon", at.lon())))
                : CompletableFuture.supplyAsync(() -> forecast(at, day, weatherNote), executor);
        var schedule = async(() -> tools.callForUser(ToolNames.SCHEDULE_LIST, session.userId,
                Map.of("date", day.toString())));
        session.weather = weather.join();
        JsonNode scheduleResult = schedule.join();
        session.events = scheduleResult == null ? null : scheduleResult.path("events");
        session.closet = props.closetSearch() ? closetSearch(session) : null;

        String label = SpokenDate.label(day, today());
        AdviceJudgement judged = judge.advise(new AdviceInput(session.weather, session.events,
                session.closet == null ? null : session.closet.path("candidates"),
                session.closet == null ? null : session.closet.path("styleNotes"),
                label, weatherNote[0]));
        // 날짜 → 날씨 → 일정 → 추천 순서는 코드가 정합니다(2026-11-09 발표 피드백: 문장이 이상했음).
        AdviceJudgement advice = new AdviceJudgement(judged.suggestions(), judged.closetPicks(),
                adviceSpeech(label, session.weather, weatherNote[0], session.events, judged.speech()));
        session.advice = advice;
        session.seen = new ArrayList<>();
        session.lastDamage = new ArrayList<>();
        session.wornStep = null;
        session.wornGarments = new ArrayList<>();
        String mode = session.outfitMode == null ? LAID : session.outfitMode;
        sessions.save(session);
        return new TurnResponse(session.sessionId, withCameraHint(advice.speech(), mode), cameraFor(mode),
                new Result("outfit_advice", advice.toAdvice(), null, mode));
    }

    static final String NO_FORECAST = "그날 날씨는 아직 예보가 없어요.";

    /**
     * 추천 문장. 2026-11-09 발표 피드백의 틀:
     * "{날짜}의 패션을 추천해 드릴게요. {날짜} 날씨는 ~예요. 일정은 ~이 있어요. ~한 옷을 추천드려요."
     * 너무 먼 날이라 예보가 없으면 날씨는 "아직 예보가 없어요"로 짧게 말합니다.
     */
    static String adviceSpeech(String label, JsonNode weather, String weatherNote, JsonNode events,
            String recommendation) {
        StringBuilder sb = new StringBuilder(label).append("의 패션을 추천해 드릴게요. ");
        if (weather != null && !weather.isMissingNode() && !weather.isNull()) {
            String condition = weather.path("condition").asString("");
            sb.append(label).append(" 날씨는 ");
            if (!condition.isBlank()) {
                sb.append(conditionClause(condition)).append(", ");
            }
            if (weather.has("minTemperature") && weather.has("maxTemperature")) {
                sb.append("최저 ").append(Math.round(weather.path("minTemperature").asDouble())).append("도, 최고 ")
                        .append(Math.round(weather.path("maxTemperature").asDouble())).append("도예요. ");
            } else {
                sb.append("체감 ").append(Math.round(weather.path("apparentTemperature").asDouble())).append("도예요. ");
            }
        } else if (weatherNote != null) {
            sb.append(weatherNote).append(" ");
        } else {
            sb.append("날씨는 불러오지 못했어요. ");
        }
        sb.append(scheduleSentence(events)).append(" ");
        String rec = withoutCameraHint(recommendation);
        if (!rec.isBlank()) {
            sb.append(rec);
        }
        return sb.toString().trim();
    }

    /** mcp-weather의 날씨 말을 이어지는 말로: 맑음 → "맑고", 비 → "비가 오고". */
    static String conditionClause(String condition) {
        return switch (condition.trim()) {
            case "맑음" -> "맑고";
            case "흐림" -> "흐리고";
            case "구름 조금" -> "구름이 조금 있고";
            case "구름 많음" -> "구름이 많고";
            case "안개" -> "안개가 끼고";
            case "이슬비" -> "이슬비가 내리고";
            case "비" -> "비가 오고";
            case "눈" -> "눈이 오고";
            case "뇌우" -> "천둥 번개가 치고";
            default -> Korean.josa(condition.trim(), "이고", "고");
        };
    }

    /** "일정은 오후 2시 친구 결혼식이 있어요." / "일정은 없어요." */
    static String scheduleSentence(JsonNode events) {
        if (events == null || events.isMissingNode() || events.isNull()) {
            return "일정은 불러오지 못했어요.";
        }
        if (events.isEmpty()) {
            return "일정은 없어요.";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode e : events) {
            String time = TurnService.spokenTime(e.path("start").asString(""));
            String title = e.path("title").asString("").trim();
            parts.add((time.isEmpty() ? "" : time + " ") + title);
        }
        String joined = String.join(", ", parts).trim();
        return "일정은 " + Korean.josa(joined, "이", "가") + " 있어요.";
    }

    /** 다른 날 예보. 16일 넘게 남았으면 null과 함께 까닭(note[0])을 남깁니다. */
    private JsonNode forecast(Location at, LocalDate day, String[] note) {
        try {
            return tools.call(ToolNames.WEATHER_FORECAST, Map.of("lat", at.lat(), "lon", at.lon(), "date", day.toString()));
        } catch (ToolException e) {
            log.warn("weather_forecast 실패, 없이 계속: {}", e.getMessage());
            if ("out_of_range".equals(e.code())) {
                note[0] = NO_FORECAST;
            }
            return null;
        }
    }

    /** 옷을 입을 날. 정하지 않았으면 오늘. */
    private LocalDate targetDay(AgentSession session) {
        if (session.targetDate != null) {
            try {
                return LocalDate.parse(session.targetDate);
            } catch (RuntimeException ignored) {
                // 이상한 값이면 오늘
            }
        }
        return today();
    }

    private JsonNode closetSearch(AgentSession session) {
        String situation = SpokenDate.label(targetDay(session), today()) + " " + titles(session.events) + (session.weather == null ? ""
                : ", 체감 " + Math.round(session.weather.path("apparentTemperature").asDouble()) + "도");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("situation", situation);
        args.put("limit", 8);
        Double temp = session.weather == null ? null : session.weather.path("apparentTemperature").asDouble();
        if (temp != null) {
            args.put("apparentTemperature", temp);
        }
        Occasion occasion = Occasion.strictest(session.events);
        // 자리에 맞지 않는 옷(면접에 운동복 등)을 빼고 격식에 가산점 (closet 실측: 상황 질문 위 5개 정답 37%→42%)
        args.put("occasion", occasion.name());
        args.put("imageQuery", ClosetQuery.english(occasion, temp));
        return safe(ToolNames.CLOSET_SEARCH, () -> tools.callForUser(ToolNames.CLOSET_SEARCH, session.userId, args));
    }

    // ── 2단계: 보여 준 옷 판단 ────────────────────────────────────────

    public TurnResponse check(AgentSession session, String imageBase64) {
        session.photoCount++;
        JsonNode inspect;
        try {
            inspect = tools.call(ToolNames.CLOTHES_INSPECT,
                    Map.of("imageBase64", imageBase64, "allowGpt", Boolean.TRUE.equals(session.gptConsent)));
        } catch (ToolException e) {
            log.warn("clothes_inspect 실패: {}", e.getMessage());
            sessions.save(session);
            return new TurnResponse(session.sessionId, "미안해요, 지금은 옷을 잘 못 봤어요. 조금 있다가 다시 보여 주실래요?",
                    Next.camera_outfit, null);
        }
        String prefix = "p" + session.photoCount + "_";
        List<Garment> now = new ArrayList<>();
        for (JsonNode g : inspect.path("garments")) {
            Garment garment = json.treeToValue(g, Garment.class);
            now.add(garment.withId(prefix + garment.id()));
            keepEmbedding(session, inspect, g, prefix + garment.id());
            keepThumbnail(session, imageBase64, garment, prefix + garment.id());
        }
        String source = inspect.path("source").asString(null);

        String retake = retake(inspect, now);
        if (retake != null) {
            sessions.save(session);
            var result = new OutfitCheck(now, List.of(), null, List.of(), List.of(), retake, source);
            return new TurnResponse(session.sessionId, retake, Next.camera_outfit,
                    new Result("outfit_check", null, result));
        }

        List<SeenBrief> before = session.seen.stream()
                .map(s -> new SeenBrief(s.garment().id(), s.garment().category(), s.garment().name(),
                        s.garment().colorName(), s.fit(), s.garment().label()))
                .toList();
        CheckJudgement j = judge.check(new CheckInput(session.weather, session.events, session.advice, now, before,
                SpokenDate.label(targetDay(session), today())));

        for (Garment g : now) {
            String fit = j.verdicts().stream().filter(v -> v.inputId().equals(g.id()))
                    .map(OutfitCheck.Verdict::fit).findFirst().orElse("ok");
            session.seen.add(new AgentSession.Seen(g, fit));
        }
        session.lastDamage = new ArrayList<>(j.damage());
        sessions.save(session);
        var result = new OutfitCheck(now, j.verdicts(), j.comparison(), j.damage(), j.stillMissing(), null, source);
        if (askGpt(session, now, hasPendingHelper(inspect), imageBase64)) {
            sessions.save(session);
            return new TurnResponse(session.sessionId, j.speech() + " " + GPT_ASK, Next.camera_outfit,
                    new Result("outfit_check", null, result));
        }
        return new TurnResponse(session.sessionId, withMore(j.speech()), Next.camera_outfit,
                new Result("outfit_check", null, result));
    }

    /** 2026-11-10 진행 님 대사: 처음 애매한 얼룩이 나왔을 때 한 번만 묻는다. */
    static final String GPT_ASK = "애매한 부분을 더 정확한 AI에게 한 번 더 물어봐도 될까요? 옷 사진 조각은 학습에 쓰일 수 있어요. "
            + "\"좋아\" 또는 \"아니\"라고 말씀해 주세요.";

    /** 입고 한 바퀴를 처음 다 봤을 때 묻는 말. 옷 실수 확인은 GPT 없이는 못 해서 애매한 얼룩이 없어도 한 번 묻습니다. */
    static final String GPT_ASK_WORN = "옷을 뒤집어 입었는지, 단추가 어긋났는지도 더 정확한 AI에게 물어봐도 될까요? "
            + "얼굴 쪽은 가리고 옷 부분만 보내요. 애매한 얼룩 조각은 학습에 쓰일 수 있어요. "
            + "\"좋아\" 또는 \"아니\"라고 말씀해 주세요.";

    /** 애매한 얼룩으로 보는 확신도 범위(옷 상태 서버가 GPT로 확인하는 범위와 같음). */
    static final double GPT_MIN = 0.2, GPT_MAX = 0.5;

    /**
     * 아직 묻지 않았고 애매한 부분이 있으면 물을 준비를 하고 true. 애매한 부분은
     * <ul>
     *   <li>옷 상태 서버가 pendingHelper로 돌려준 얼룩 후보(GPT 없이는 0.3~0.5라 defects에 안 들어옴)</li>
     *   <li>무늬를 모르는 옷(pattern 없음, patternGuess만 있음)</li>
     *   <li>defects 안의 GPT가 확인하지 않은 0.2~0.5 얼룩(예전 서버 호환)</li>
     * </ul>
     * 2026-11-13: 서버는 0.5 미만 얼룩을 defects에 넣지 않아 예전 조건만으로는 한 번도 묻지 못했습니다.
     */
    private static boolean askGpt(AgentSession session, List<Garment> garments, boolean pendingHelper,
            String imageBase64) {
        return askGpt(session, garments, pendingHelper, imageBase64, false);
    }

    /** wear=true(입고 한 바퀴)면 옷 실수 확인 때문에 애매한 부분이 없어도 묻습니다. */
    private static boolean askGpt(AgentSession session, List<Garment> garments, boolean pendingHelper,
            String imageBase64, boolean wear) {
        if (session.gptConsent != null || session.gptAsked) {
            return false;
        }
        boolean unsure = wear || pendingHelper
                || garments.stream().anyMatch(g -> g.pattern() == null && g.patternGuess() != null)
                || garments.stream().flatMap(g -> g.defects().stream())
                        .anyMatch(d -> !d.byGpt() && d.confidence() >= GPT_MIN && d.confidence() < GPT_MAX);
        if (!unsure) {
            return false;
        }
        session.gptAsked = true;
        session.gptAskImage = imageBase64;
        return true;
    }

    /** 옷 상태 서버가 확인하지 못한 애매한 얼룩 후보를 돌려줬는지. */
    static boolean hasPendingHelper(JsonNode inspect) {
        JsonNode p = inspect.path("pendingHelper");
        return p.isObject() && p.path("candidates").isArray() && !p.path("candidates").isEmpty();
    }

    /** "더 정확한 AI에게 물어봐도 될까요?"에 대한 답. 좋다면 방금 사진을 GPT 확인으로 다시 봅니다. 답이 아니면 null. */
    public TurnResponse answerGptAsk(AgentSession session, String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        boolean no = NO.stream().anyMatch(s::contains);
        boolean yes = !no && YES.stream().anyMatch(s::startsWith);
        String image = session.gptAskImage;
        session.gptAsked = false;
        session.gptAskImage = null;
        if (!yes && !no) {
            sessions.save(session);
            return null;
        }
        session.gptConsent = yes;
        session.gptDecided = true;
        boolean worn = WORN.equals(session.outfitMode);
        Next camera = worn ? Next.camera_worn : Next.camera_outfit;
        String after = worn ? WORN_AGAIN.strip() : MORE;
        if (no) {
            sessions.save(session);
            return new TurnResponse(session.sessionId, "알겠어요. 애매한 부분은 따로 물어보지 않을게요. " + after,
                    camera, null, false);
        }
        if (image == null && worn) {
            session.wornStep = null;
            session.wornGarments = new ArrayList<>();
            sessions.save(session);
            return new TurnResponse(session.sessionId,
                    "고마워요. 앞모습부터 한 번 더 서 주시면 옷 실수도 같이 볼게요. " + WORN_HINT, camera, null, true);
        }
        if (image == null) {
            sessions.save(session);
            return new TurnResponse(session.sessionId, "고마워요. 다음부터 애매한 부분은 한 번 더 확인할게요. " + after,
                    camera, null, true);
        }
        // 방금 본 옷을 지우고 같은 사진을 다시 봅니다(같은 옷이 두 번 세어지지 않게).
        String prefix = "p" + session.photoCount + "_";
        session.seen.removeIf(x -> x.garment().id().startsWith(prefix));
        session.photoCount--;
        TurnResponse again = check(session, image);
        return new TurnResponse(again.sessionId(), "고마워요. 한 번 더 확인해 봤어요. " + again.speech(), again.next(),
                again.result(), true);
    }

    /** 2026-11-09 진행 님: 한 벌 찍고 나면 "더 찍으실 옷이 있으면 비춰 주세요"로 다음 옷을 기다린다. */
    static final String MORE = "더 찍으실 옷이 있으면 비춰 주세요.";

    /** 판단 말이 이미 다른 옷을 보여 달라고 하면("겉옷도 보여 주세요") 겹치지 않게 붙이지 않는다. */
    static String withMore(String speech) {
        String s = speech == null ? "" : speech.strip();
        if (s.contains("보여 주세요") || s.contains("비춰 주세요") || s.contains("보여주세요") || s.contains("비춰주세요")) {
            return s;
        }
        return s.isEmpty() ? MORE : s + " " + MORE;
    }

    // ── 2단계(입고 한 바퀴): 앞·뒤 ───────────────────────────────────

    static final String WORN_BACK = "이제 뒤로 돌아 주세요.";
    static final String WORN_AGAIN = " 갈아입으면 앞모습부터 다시 볼게요.";

    /**
     * 입고 한 바퀴. 앞모습에서 옷을 알아보고, 뒤에서는 얼룩·찢어짐을 더 찾습니다.
     * 두 장을 다 받으면 한 번만 판단합니다(GPT 호출 1번). 옆모습은 2026-11-12 정지원 님 요청으로 뺐습니다.
     */
    public TurnResponse checkWorn(AgentSession session, String imageBase64) {
        session.photoCount++;
        String view = session.wornStep == null ? "front" : session.wornStep;
        if ("front".equals(view)) {
            session.wornGarments = new ArrayList<>();
            session.wornWearNotes = new ArrayList<>();
        }
        JsonNode inspect;
        try {
            inspect = tools.call(ToolNames.CLOTHES_INSPECT,
                    Map.of("imageBase64", imageBase64, "mode", WORN, "view", view, "allowGpt",
                            Boolean.TRUE.equals(session.gptConsent)));
        } catch (ToolException e) {
            log.warn("clothes_inspect(worn) 실패: {}", e.getMessage());
            sessions.save(session);
            return new TurnResponse(session.sessionId, "미안해요, 지금은 옷을 잘 못 봤어요. 조금 있다가 다시 서 주실래요?",
                    Next.camera_worn, null);
        }
        String prefix = "p" + session.photoCount + "_";
        List<Garment> now = new ArrayList<>();
        for (JsonNode g : inspect.path("garments")) {
            Garment garment = json.treeToValue(g, Garment.class);
            now.add(garment.withId(prefix + garment.id()));
            keepEmbedding(session, inspect, g, prefix + garment.id());
            keepThumbnail(session, imageBase64, garment, prefix + garment.id());
        }
        String source = inspect.path("source").asString(null);

        String retake = retakeWorn(inspect, now, view);
        if (retake != null) {
            sessions.save(session);
            var result = new OutfitCheck(now, List.of(), null, List.of(), List.of(), retake, source);
            return new TurnResponse(session.sessionId, retake, Next.camera_worn,
                    new Result("outfit_check", null, result, WORN));
        }

        mergeWorn(session.wornGarments, now);
        addWearNotes(session.wornWearNotes, inspect, now, prefix, view);
        if ("front".equals(view)) {
            session.wornUnsure = false;
        }
        session.wornUnsure |= hasPendingHelper(inspect);
        if (!"back".equals(view)) {
            session.wornStep = "back";
            sessions.save(session);
            return new TurnResponse(session.sessionId, WORN_BACK,
                    Next.camera_worn, null);
        }

        List<Garment> outfit = List.copyOf(session.wornGarments);
        List<SeenBrief> before = session.seen.stream()
                .map(s -> new SeenBrief(s.garment().id(), s.garment().category(), s.garment().name(),
                        s.garment().colorName(), s.fit(), s.garment().label()))
                .toList();
        CheckJudgement j = judge.check(new CheckInput(session.weather, session.events, session.advice, outfit, before,
                SpokenDate.label(targetDay(session), today())));
        for (Garment g : outfit) {
            String fit = j.verdicts().stream().filter(v -> v.inputId().equals(g.id()))
                    .map(OutfitCheck.Verdict::fit).findFirst().orElse("ok");
            session.seen.add(new AgentSession.Seen(g, fit));
        }
        session.lastDamage = new ArrayList<>(j.damage());
        session.lastWornIds = new ArrayList<>(outfit.stream().map(Garment::id).toList());
        session.wornStep = null;
        session.wornGarments = new ArrayList<>();
        sessions.save(session);
        var result = new OutfitCheck(outfit, j.verdicts(), j.comparison(), j.damage(), j.stillMissing(), null, source);
        boolean wornUnsure = session.wornUnsure;
        session.wornUnsure = false;
        String speech = j.speech() + wearSpeech(session.wornWearNotes);
        session.wornWearNotes = new ArrayList<>();
        if (askGpt(session, outfit, wornUnsure, null, true)) {
            sessions.save(session);
            return new TurnResponse(session.sessionId, speech + " " + GPT_ASK_WORN, Next.camera_worn,
                    new Result("outfit_check", null, result, WORN));
        }
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech + WORN_AGAIN, Next.camera_worn,
                new Result("outfit_check", null, result, WORN));
    }

    /**
     * 옷 상태 서버의 wearIssues를 모읍니다. garmentId로 이번 사진 옷을 찾아 이름을 붙이고, 같은 옷 종류·같은 실수는
     * 앞·뒤에서 두 번 나와도 한 번만 둡니다.
     */
    static void addWearNotes(List<AgentSession.WearNote> notes, JsonNode inspect, List<Garment> now, String prefix,
            String view) {
        for (JsonNode w : inspect.path("wearIssues")) {
            String kind = w.path("kind").asString(null);
            if (wearSentence(kind, "옷", view) == null) {
                continue;
            }
            String id = prefix + w.path("garmentId").asString("");
            Garment g = now.stream().filter(x -> id.equals(x.id())).findFirst().orElse(null);
            String category = g != null ? g.category() : w.path("category").asString("other");
            String label = g != null ? g.label() : "옷";
            if (notes.stream().noneMatch(n -> n.kind().equals(kind) && java.util.Objects.equals(n.category(), category))) {
                notes.add(new AgentSession.WearNote(kind, category, label, view));
            }
        }
    }

    /** 옷 실수 문장들(앞에 빈칸 하나). 없으면 "". 사진 한 장 판단이라 "~것 같아요"로 말하고 손으로 확인해 보라고 합니다. */
    static String wearSpeech(List<AgentSession.WearNote> notes) {
        if (notes == null || notes.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AgentSession.WearNote n : notes.stream().limit(3).toList()) {
            sb.append(' ').append(wearSentence(n.kind(), n.label(), n.view()));
        }
        return sb.toString();
    }

    static String wearSentence(String kind, String label, String view) {
        String l = label == null || label.isBlank() ? "옷" : label;
        return switch (kind == null ? "" : kind) {
            case "inside_out" -> Korean.josa(l, "이", "가") + " 뒤집혀서 안쪽이 밖으로 나온 것 같아요. 솔기가 겉에 있는지 만져 보세요.";
            case "backwards" -> Korean.josa(l, "을", "를") + " 앞뒤 바꿔 입은 것 같아요. 목 뒤 택이나 지퍼 위치를 만져 보세요.";
            case "buttons_misaligned" -> l + " 단추가 한 칸씩 어긋나게 잠긴 것 같아요. 맨 아래 단추부터 짝이 맞는지 만져 보세요.";
            case "tag_out" -> l + " 택이 " + ("back".equals(view) ? "뒤쪽 " : "") + "밖으로 나와 있는 것 같아요. 안으로 넣어 주세요.";
            default -> null;
        };
    }

    /** 앞모습 옷을 기준으로 두고, 뒤에서 본 같은 종류 옷의 얼룩을 더합니다. 처음 보는 종류면 더합니다. */
    static void mergeWorn(List<Garment> outfit, List<Garment> now) {
        for (Garment g : now) {
            int i = -1;
            for (int k = 0; k < outfit.size(); k++) {
                if (java.util.Objects.equals(outfit.get(k).category(), g.category())) {
                    i = k;
                    break;
                }
            }
            if (i < 0) {
                outfit.add(g);
            } else if (!g.defects().isEmpty()) {
                outfit.set(i, outfit.get(i).withMoreDefects(g.defects()));
            }
        }
    }

    private static String retakeWorn(JsonNode inspect, List<Garment> now, String view) {
        if ("dark".equals(inspect.path("lighting").asString("ok"))) {
            return "조금 어두워요. 불을 켜고 다시 서 주세요.";
        }
        String framing = inspect.path("framing").asString(null);
        if (framing != null && !framing.isBlank()) {
            return framing;
        }
        if (now.isEmpty() && "front".equals(view)) {
            return "몸이 다 안 보여요. 한 걸음만 뒤로 가 주실래요?";
        }
        return null;
    }

    private static String retake(JsonNode inspect, List<Garment> now) {
        if ("dark".equals(inspect.path("lighting").asString("ok"))) {
            return "조금 어두워요. 불을 켜거나 밝은 곳에서 다시 보여 주세요.";
        }
        String framing = inspect.path("framing").asString(null);
        if (framing != null && !framing.isBlank()) {
            return framing;
        }
        if (now.isEmpty()) {
            return "옷이 잘 안 보여요. 옷 위로 폰을 들어 주실래요?";
        }
        return null;
    }

    // ── "어디야?" ─────────────────────────────────────────────────────

    public TurnResponse where(AgentSession session) {
        List<String> sentences = new ArrayList<>();
        for (OutfitCheck.Damage d : session.lastDamage) {
            if (d.where() == null || d.where().isBlank()) {
                continue;
            }
            String label = session.seen.stream().filter(s -> s.garment().id().equals(d.inputId()))
                    .map(s -> s.garment().label()).findFirst().orElse("옷");
            sentences.add(label + "의 " + Korean.josa(kindName(d.kind()), "은", "는") + " " + d.where() + "에 있어요.");
        }
        String speech = sentences.isEmpty() ? "방금 본 옷에서는 얼룩이나 찢어진 곳을 찾지 못했어요."
                : String.join(" ", sentences) + (WORN.equals(session.outfitMode) ? " 입고 있는 몸 기준이에요."
                        : " 옷을 내려놓은 그대로, 사진에서 보이는 방향 기준이에요.");
        return new TurnResponse(session.sessionId, speech, cameraFor(session.outfitMode), null);
    }

    private static String kindName(String kind) {
        return switch (kind == null ? "" : kind) {
            case "stain" -> "얼룩";
            case "tear" -> "찢어진 곳";
            case "lint" -> "보풀";
            default -> "이상한 곳";
        };
    }

    // ── "이걸로 할게" ─────────────────────────────────────────────────

    public TurnResponse finish(AgentSession session) {
        // 입고 한 바퀴는 지금 입은 옷이 답입니다. 갈아입기 전 바퀴의 옷을 섞지 않습니다
        // (2026-11-12 시뮬: 두 바퀴 뒤 "이걸로 할게"에 첫 바퀴 자켓과 둘째 바퀴 옷을 함께 기록함).
        List<AgentSession.Seen> pool = finishPool(session);
        List<Garment> chosen = chosen(pool);
        // 아쉽다고 말한 옷만 봤어도 사용자가 "이걸로 할게"라고 하면 그 옷으로 합니다(2026-11-12 시뮬: 입은 모습이
        // 날씨에 가볍다고 했더니 기록을 거절하고 다시 보여 달라고만 했음).
        boolean overruled = chosen.isEmpty() && !pool.isEmpty();
        if (overruled) {
            chosen = anyChosen(pool);
        }
        chosen = withoutUnknown(chosen);
        if (chosen.isEmpty()) {
            return new TurnResponse(session.sessionId, "아직 잘 맞는 옷을 못 봤어요. 옷을 카메라에 한번 보여 주실래요?",
                    cameraFor(session.outfitMode), null);
        }
        boolean recorded = !session.recorded && record(session, chosen, targetDay(session));
        session.recorded = session.recorded || recorded;
        String names = String.join(", ", chosen.stream().map(Garment::label).toList());
        String picked = (overruled ? "좋아요, 입고 싶은 옷으로 하세요. " : "좋아요. ")
                + Korean.josa(names, "으로", "로") + (recorded ? " 기록해 둘게요." : " 정했어요.");
        session.seen = new ArrayList<>();
        session.lastWornIds = null;
        session.lastDamage = new ArrayList<>();
        session.embeddings = new java.util.HashMap<>();
        session.thumbnails = new java.util.HashMap<>();
        if (!WORN.equals(session.outfitMode)) {
            // 2026-11-06 진행 님 요청: 내려놓고 고른 사람에게 입은 모습도 볼지 묻기
            session.wornOfferAsked = true;
            sessions.save(session);
            return new TurnResponse(session.sessionId, picked + " " + OFFER_WORN, Next.listen,
                    Result.of("outfit_worn_offer"));
        }
        sessions.save(session);
        return new TurnResponse(session.sessionId, picked + " 오늘 멋지게 잘 다녀오세요.", Next.done, null);
    }

    private static boolean otherFeature(String utterance) {
        return switch (Intent.of(utterance, false)) {
            case WEATHER, SCHEDULE, FACE, HELP, OUTFIT -> true;
            default -> false;
        };
    }

    static final String OFFER_WORN = "다 입으신 뒤에 입은 모습도 확인해 보실래요? 네 또는 아니요로 말씀해 주세요.";
    private static final List<String> YES = List.of("네", "응", "좋아", "그래", "볼게", "해줘", "확인", "예");
    private static final List<String> NO = List.of("아니", "괜찮", "됐어", "안할", "안볼", "싫", "그만", "다음에");

    /** "입은 모습도 확인해 보실래요?"에 대한 답. 알아듣지 못하면 null(원래 대화로). */
    public TurnResponse answerWornOffer(AgentSession session, String utterance) {
        session.wornOfferAsked = false;
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (otherFeature(utterance)) {
            // "아니 그냥 날씨 어때?"처럼 다른 기능을 말하면 답이 아니라 새 요청입니다.
            sessions.save(session);
            return null;
        }
        if (NO.stream().anyMatch(s::contains)) {
            sessions.save(session);
            return new TurnResponse(session.sessionId, "그럼요. 오늘 멋지게 잘 다녀오세요.", Next.done, null);
        }
        if (YES.stream().anyMatch(s::startsWith) || s.contains("입고") || s.contains("볼게")) {
            session.outfitMode = WORN;
            session.wornStep = null;
            session.wornGarments = new ArrayList<>();
            sessions.save(session);
            return new TurnResponse(session.sessionId, "좋아요. " + WORN_HINT, Next.camera_worn,
                    new Result("outfit_mode", null, null, WORN));
        }
        sessions.save(session);
        return null;
    }

    /**
     * 종류를 모르는 옷("흰색 옷", category other)은 다른 옷이 있으면 기록하지 않습니다
     * (2026-11-12 시뮬: "흰색 옷, 네이비 원피스로 기록해 둘게요" — 옷장에 이름 없는 옷이 쌓임).
     */
    static List<Garment> withoutUnknown(List<Garment> chosen) {
        List<Garment> known = chosen.stream()
                .filter(g -> !("other".equals(g.category()) && (g.name() == null || "옷".equals(g.name().strip()))))
                .toList();
        return known.isEmpty() ? chosen : known;
    }

    /** 고를 후보. 입고 한 바퀴면 마지막 바퀴에 본 옷만, 아니면 본 옷 전부. */
    static List<AgentSession.Seen> finishPool(AgentSession session) {
        if (WORN.equals(session.outfitMode) && session.lastWornIds != null && !session.lastWornIds.isEmpty()) {
            List<AgentSession.Seen> last = session.seen.stream()
                    .filter(s -> session.lastWornIds.contains(s.garment().id())).toList();
            if (!last.isEmpty()) {
                return last;
            }
        }
        return session.seen;
    }

    /** 종류마다 가장 잘 맞는 옷 하나 (같으면 나중에 본 옷). bad는 뺍니다. */
    static List<Garment> chosen(AgentSession session) {
        return chosen(session.seen);
    }

    static List<Garment> chosen(List<AgentSession.Seen> seen) {
        Map<String, AgentSession.Seen> best = new LinkedHashMap<>();
        for (AgentSession.Seen s : seen) {
            if ("bad".equals(s.fit())) {
                continue;
            }
            best.merge(s.garment().category(), s, (old, neu) -> rank(neu.fit()) >= rank(old.fit()) ? neu : old);
        }
        return onePieceOrSeparates(best, seen);
    }

    /**
     * 원피스와 상의·하의를 함께 고르지 않습니다(2026-11-12 시뮬: 원피스·치마·티셔츠를 한 번에 기록함).
     * 더 잘 맞는 쪽, 같으면 나중에 본 쪽을 남깁니다.
     */
    static List<Garment> onePieceOrSeparates(Map<String, AgentSession.Seen> best, List<AgentSession.Seen> seen) {
        AgentSession.Seen dress = best.get("dress");
        List<AgentSession.Seen> parts = java.util.stream.Stream.of(best.get("top"), best.get("bottom"))
                .filter(java.util.Objects::nonNull).toList();
        if (dress != null && !parts.isEmpty()) {
            int partRank = parts.stream().mapToInt(p -> rank(p.fit())).max().orElse(0);
            int partLast = parts.stream().mapToInt(seen::lastIndexOf).max().orElse(-1);
            boolean keepDress = rank(dress.fit()) != partRank ? rank(dress.fit()) > partRank
                    : seen.lastIndexOf(dress) > partLast;
            if (keepDress) {
                best.remove("top");
                best.remove("bottom");
            } else {
                best.remove("dress");
            }
        }
        return best.values().stream().map(AgentSession.Seen::garment).toList();
    }

    /** 맞음·아쉬움을 가리지 않고 종류마다 가장 나은(같으면 나중에 본) 옷. 사용자가 아쉬운 옷을 고집할 때. */
    static List<Garment> anyChosen(List<AgentSession.Seen> seen) {
        Map<String, AgentSession.Seen> best = new LinkedHashMap<>();
        for (AgentSession.Seen s : seen) {
            best.merge(s.garment().category(), s, (old, neu) -> rank(neu.fit()) >= rank(old.fit()) ? neu : old);
        }
        return onePieceOrSeparates(best, seen);
    }

    private static int rank(String fit) {
        return "good".equals(fit) ? 2 : "ok".equals(fit) ? 1 : 0;
    }

    /** 옷 사진 벡터는 closet_save에만 넘깁니다(같은 옷 판단·이름 고르기). 모델 이름이 없으면 넘기지 않습니다. */
    private static void keepEmbedding(AgentSession session, JsonNode inspect, JsonNode garment, String id) {
        String model = inspect.path("embeddingModel").asString(null);
        JsonNode e = garment.path("embedding");
        if (model == null || !e.isArray() || e.isEmpty()) {
            return;
        }
        List<Double> v = new ArrayList<>(e.size());
        e.forEach(x -> v.add(x.asDouble()));
        session.embeddingModel = model;
        session.embeddings.put(id, v);
    }

    /** 옷 부분 작은 사진은 closet_save에만 넘깁니다(애매한 같은 옷을 GPT가 사진으로 비교). 못 만들면 넘기지 않습니다. */
    private static void keepThumbnail(AgentSession session, String imageBase64, Garment garment, String id) {
        String t = Thumbnails.crop(imageBase64, garment.box());
        if (t != null) {
            session.thumbnails.put(id, t);
        }
    }

    static Map<String, Object> saveArgs(List<Map<String, Object>> garments, Map<String, List<Double>> embeddings, String model) {
        return saveArgs(garments, embeddings, model, Map.of());
    }

    /** closet_save 입력: 고른 옷 JSON에 그 옷의 사진 벡터와 작은 사진을 붙입니다. */
    static Map<String, Object> saveArgs(List<Map<String, Object>> garments, Map<String, List<Double>> embeddings, String model,
            Map<String, String> thumbnails) {
        Map<String, Object> args = new LinkedHashMap<>();
        boolean any = false;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> g : garments) {
            String id = String.valueOf(g.get("id"));
            List<Double> v = model == null ? null : embeddings.get(id);
            String t = thumbnails.get(id);
            if (v != null || t != null) {
                g = new LinkedHashMap<>(g);
            }
            if (v != null) {
                g.put("embedding", v);
                any = true;
            }
            if (t != null) {
                g.put("thumbnail", t);
            }
            out.add(g);
        }
        args.put("garments", out);
        if (any) {
            args.put("embeddingModel", model);
        }
        return args;
    }

    @SuppressWarnings("unchecked")
    private boolean record(AgentSession session, List<Garment> chosen, LocalDate day) {
        String userId = session.userId;
        try {
            List<Map<String, Object>> garments = new ArrayList<>();
            for (Garment g : chosen) {
                garments.add(json.convertValue(g, Map.class));
            }
            JsonNode saved = tools.callForUser(ToolNames.CLOSET_SAVE, userId,
                    saveArgs(garments, session.embeddings, session.embeddingModel, session.thumbnails));
            List<String> ids = new ArrayList<>();
            for (JsonNode s : saved.path("saved")) {
                String id = s.path("garmentId").asString();
                String into = confirmSame(session, s);
                if (into != null && !ids.contains(into)) {
                    id = into;
                }
                ids.add(id);
            }
            ids = ids.stream().distinct().toList();
            tools.callForUser(ToolNames.CLOSET_WEAR, userId, Map.of("garmentIds", ids, "date", day.toString()));
            return true;
        } catch (ToolException e) {
            log.warn("옷장 기록 실패 (추천은 계속): {}", e.getMessage());
            return false;
        }
    }

    /**
     * closet_save가 "애매하게 비슷한 옷"(maybeSameAs)을 알려 주면 GPT에게 두 사진을 보여 같은 옷인지 묻고, 같으면 closet_merge로
     * 합칩니다(2026-11-12 박창근 님 결정). 사진을 GPT에 보내므로 GPT 사용에 동의한 사람만. 합친 옷 id를, 아니면 null을 돌려줍니다.
     */
    private String confirmSame(AgentSession session, JsonNode saved) {
        JsonNode maybe = saved.path("maybeSameAs");
        if (maybe.isMissingNode() || maybe.isNull() || !Boolean.TRUE.equals(session.gptConsent)) {
            return null;
        }
        String now = session.thumbnails.get(saved.path("inputId").asString(""));
        String theirs = maybe.path("thumbnail").asString(null);
        String into = maybe.path("garmentId").asString(null);
        String id = saved.path("garmentId").asString(null);
        if (now == null || theirs == null || into == null || id == null) {
            return null;
        }
        String desc = String.join(", ", java.util.stream.Stream.of(maybe.path("colorName").asString(null),
                maybe.path("name").asString(null), maybe.path("pattern").asString(null), maybe.path("material").asString(null))
                .filter(x -> x != null && !x.isBlank()).toList());
        if (!sameGarment.same(now, theirs, desc)) {
            return null;
        }
        try {
            tools.callForUser(ToolNames.CLOSET_MERGE, session.userId, Map.of("garmentId", id, "intoGarmentId", into));
            return into;
        } catch (ToolException e) {
            log.warn("closet_merge 실패(따로 둠): {}", e.getMessage());
            return null;
        }
    }

    // ── 공통 ──────────────────────────────────────────────────────────

    private LocalDate today() {
        return LocalDate.now(clock.withZone(SEOUL));
    }

    private CompletableFuture<JsonNode> async(Supplier<JsonNode> call) {
        return CompletableFuture.supplyAsync(() -> safe("동시 호출", call), executor);
    }

    /** 도구 하나가 실패해도 나머지로 계속합니다 (판단 쪽이 null을 "모름"으로 다룸). */
    private static JsonNode safe(String what, Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (ToolException e) {
            log.warn("{} 실패, 없이 계속: {}", what, e.getMessage());
            return null;
        }
    }

    private static String titles(JsonNode events) {
        if (events == null || events.isEmpty()) {
            return "일정 없음";
        }
        List<String> titles = new ArrayList<>();
        events.forEach(e -> titles.add(e.path("title").asString("")));
        return String.join(", ", titles);
    }
}
