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
import com.fashionagent.agent.judge.OutfitJudge.AdviceInput;
import com.fashionagent.agent.judge.OutfitJudge.CheckInput;
import com.fashionagent.agent.judge.OutfitJudge.SeenBrief;
import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
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
 * <li>worn: 입고 폰을 세워 둔 채 한 바퀴 돌기 → camera_worn, 앞·옆·뒤 세 장을 받은 뒤 한 번 판단 ({@link #checkWorn})</li>
 * </ul>
 */
@Component
public class OutfitFlow {

    private static final Logger log = LoggerFactory.getLogger(OutfitFlow.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AgentTools tools;
    private final OutfitJudge judge;
    private final SessionStore sessions;
    private final AgentProperties props;
    private final ExecutorService executor;
    private final JsonMapper json;
    private final Clock clock;

    public OutfitFlow(AgentTools tools, OutfitJudge judge, SessionStore sessions, AgentProperties props,
            ExecutorService agentExecutor, JsonMapper json, Clock clock) {
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
    static final String ASK_MODE = "옷은 어떻게 보여 주실래요? 침대나 바닥에 내려놓고 비추기, "
            + "아니면 입고 카메라 앞에서 한 바퀴 돌기 중에 골라 주세요.";
    static final String LAID_HINT = "옷을 꺼내서 침대나 바닥에 펼쳐 놓고 폰으로 비춰 주세요. 두 벌을 나란히 놓으면 비교해 드려요.";
    static final String WORN_HINT = "옷을 입은 뒤 폰을 가슴 높이에 세워 두고, 두세 걸음 물러나 머리부터 발끝까지 보이게 서 주세요. "
            + "앞모습부터 볼게요.";
    static final int MAX_MODE_ASKS = 2;

    private static final List<String> WORN_WORDS = List.of("입고", "입은채", "입어서", "입은거", "한바퀴", "돌게", "돌기",
            "돌면서");
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
     * "오늘 뭐 입지?" 보여 주는 방식을 매번 먼저 묻습니다. 말에 방식이 있으면("입고 볼게, 뭐 입지?") 바로 추천합니다.
     * 2026-11-06 진행 님 결정: 기억한 방식을 쓰지 않고 매번 고르게(내려놓고 본 뒤 입고도 보고 싶은 사람이 있어서).
     */
    public TurnResponse start(AgentSession session, Location location, String utterance) {
        String mode = modeFrom(utterance);
        if (mode == null) {
            session.modeAsked = 1;
            session.pendingLocation = location;
            sessions.save(session);
            return new TurnResponse(session.sessionId, ASK_MODE, Next.listen, Result.of("outfit_mode"));
        }
        session.outfitMode = mode;
        return advise(session, location);
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
        var weather = async(() -> tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", at.lat(), "lon", at.lon())));
        var schedule = async(() -> tools.callForUser(ToolNames.SCHEDULE_LIST, session.userId,
                Map.of("date", today().toString())));
        session.weather = weather.join();
        JsonNode scheduleResult = schedule.join();
        session.events = scheduleResult == null ? null : scheduleResult.path("events");
        session.closet = props.closetSearch() ? closetSearch(session) : null;

        AdviceJudgement advice = judge.advise(new AdviceInput(session.weather, session.events,
                session.closet == null ? null : session.closet.path("candidates"),
                session.closet == null ? null : session.closet.path("styleNotes")));
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

    private JsonNode closetSearch(AgentSession session) {
        String situation = "오늘 " + titles(session.events) + (session.weather == null ? ""
                : ", 체감 " + Math.round(session.weather.path("apparentTemperature").asDouble()) + "도");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("situation", situation);
        args.put("limit", 8);
        if (session.weather != null) {
            args.put("apparentTemperature", session.weather.path("apparentTemperature").asDouble());
        }
        return safe(ToolNames.CLOSET_SEARCH, () -> tools.callForUser(ToolNames.CLOSET_SEARCH, session.userId, args));
    }

    // ── 2단계: 보여 준 옷 판단 ────────────────────────────────────────

    public TurnResponse check(AgentSession session, String imageBase64) {
        session.photoCount++;
        JsonNode inspect;
        try {
            inspect = tools.call(ToolNames.CLOTHES_INSPECT, Map.of("imageBase64", imageBase64));
        } catch (ToolException e) {
            log.warn("clothes_inspect 실패: {}", e.getMessage());
            sessions.save(session);
            return new TurnResponse(session.sessionId, "지금은 옷을 확인하지 못했어요. 잠시 뒤에 다시 보여 주세요.",
                    Next.camera_outfit, null);
        }
        String prefix = "p" + session.photoCount + "_";
        List<Garment> now = new ArrayList<>();
        for (JsonNode g : inspect.path("garments")) {
            Garment garment = json.treeToValue(g, Garment.class);
            now.add(garment.withId(prefix + garment.id()));
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
                        s.garment().colorName(), s.fit()))
                .toList();
        CheckJudgement j = judge.check(new CheckInput(session.weather, session.events, session.advice, now, before));

        for (Garment g : now) {
            String fit = j.verdicts().stream().filter(v -> v.inputId().equals(g.id()))
                    .map(OutfitCheck.Verdict::fit).findFirst().orElse("ok");
            session.seen.add(new AgentSession.Seen(g, fit));
        }
        session.lastDamage = new ArrayList<>(j.damage());
        sessions.save(session);
        var result = new OutfitCheck(now, j.verdicts(), j.comparison(), j.damage(), j.stillMissing(), null, source);
        return new TurnResponse(session.sessionId, j.speech(), Next.camera_outfit,
                new Result("outfit_check", null, result));
    }

    // ── 2단계(입고 한 바퀴): 앞·옆·뒤 ─────────────────────────────────

    static final String WORN_SIDE = "앞모습을 봤어요. 이제 천천히 옆으로 돌아서 잠시 멈춰 주세요.";
    static final String WORN_BACK = "옆모습도 봤어요. 이제 뒤로 돌아서 잠시 멈춰 주세요.";
    static final String WORN_AGAIN = " 다른 옷으로 갈아입으면 다시 앞모습부터 보여 주세요.";

    /**
     * 입고 한 바퀴. 앞모습에서 옷을 알아보고, 옆·뒤에서는 얼룩·찢어짐을 더 찾습니다.
     * 세 장을 다 받으면 한 번만 판단합니다(GPT 호출 1번).
     */
    public TurnResponse checkWorn(AgentSession session, String imageBase64) {
        session.photoCount++;
        String view = session.wornStep == null ? "front" : session.wornStep;
        if ("front".equals(view)) {
            session.wornGarments = new ArrayList<>();
        }
        JsonNode inspect;
        try {
            inspect = tools.call(ToolNames.CLOTHES_INSPECT,
                    Map.of("imageBase64", imageBase64, "mode", WORN, "view", view));
        } catch (ToolException e) {
            log.warn("clothes_inspect(worn) 실패: {}", e.getMessage());
            sessions.save(session);
            return new TurnResponse(session.sessionId, "지금은 옷을 확인하지 못했어요. 잠시 뒤에 다시 서 주세요.",
                    Next.camera_worn, null);
        }
        String prefix = "p" + session.photoCount + "_";
        List<Garment> now = new ArrayList<>();
        for (JsonNode g : inspect.path("garments")) {
            Garment garment = json.treeToValue(g, Garment.class);
            now.add(garment.withId(prefix + garment.id()));
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
        if (!"back".equals(view)) {
            session.wornStep = "front".equals(view) ? "side" : "back";
            sessions.save(session);
            return new TurnResponse(session.sessionId, "front".equals(view) ? WORN_SIDE : WORN_BACK,
                    Next.camera_worn, null);
        }

        List<Garment> outfit = List.copyOf(session.wornGarments);
        List<SeenBrief> before = session.seen.stream()
                .map(s -> new SeenBrief(s.garment().id(), s.garment().category(), s.garment().name(),
                        s.garment().colorName(), s.fit()))
                .toList();
        CheckJudgement j = judge.check(new CheckInput(session.weather, session.events, session.advice, outfit, before));
        for (Garment g : outfit) {
            String fit = j.verdicts().stream().filter(v -> v.inputId().equals(g.id()))
                    .map(OutfitCheck.Verdict::fit).findFirst().orElse("ok");
            session.seen.add(new AgentSession.Seen(g, fit));
        }
        session.lastDamage = new ArrayList<>(j.damage());
        session.wornStep = null;
        session.wornGarments = new ArrayList<>();
        sessions.save(session);
        var result = new OutfitCheck(outfit, j.verdicts(), j.comparison(), j.damage(), j.stillMissing(), null, source);
        return new TurnResponse(session.sessionId, j.speech() + WORN_AGAIN, Next.camera_worn,
                new Result("outfit_check", null, result, WORN));
    }

    /** 앞모습 옷을 기준으로 두고, 옆·뒤에서 본 같은 종류 옷의 얼룩을 더합니다. 처음 보는 종류면 더합니다. */
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
            return "몸이 다 보이지 않아요. 한두 걸음 더 물러나서 머리부터 발끝까지 보이게 서 주세요.";
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
            return "옷이 안 보여요. 옷 위로 폰을 들어 주세요.";
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
        List<Garment> chosen = chosen(session);
        if (chosen.isEmpty()) {
            return new TurnResponse(session.sessionId, "아직 잘 맞는 옷을 못 봤어요. 옷을 카메라에 보여 주세요.",
                    cameraFor(session.outfitMode), null);
        }
        boolean recorded = !session.recorded && record(session.userId, chosen);
        session.recorded = session.recorded || recorded;
        String names = String.join(", ", chosen.stream().map(Garment::label).toList());
        String picked = "좋아요. " + Korean.josa(names, "으로", "로") + (recorded ? " 기록해 둘게요." : " 정했어요.");
        session.seen = new ArrayList<>();
        session.lastDamage = new ArrayList<>();
        if (!WORN.equals(session.outfitMode)) {
            // 2026-11-06 진행 님 요청: 내려놓고 고른 사람에게 입은 모습도 볼지 묻기
            session.wornOfferAsked = true;
            sessions.save(session);
            return new TurnResponse(session.sessionId, picked + " " + OFFER_WORN, Next.listen,
                    Result.of("outfit_worn_offer"));
        }
        sessions.save(session);
        return new TurnResponse(session.sessionId, picked + " 잘 다녀오세요.", Next.done, null);
    }

    static final String OFFER_WORN = "다 입으신 뒤에 입은 모습도 확인해 보실래요? 네 또는 아니요로 말씀해 주세요.";
    private static final List<String> YES = List.of("네", "응", "좋아", "그래", "볼게", "해줘", "확인", "예");
    private static final List<String> NO = List.of("아니", "괜찮", "됐어", "안할", "안볼", "싫", "그만", "다음에");

    /** "입은 모습도 확인해 보실래요?"에 대한 답. 알아듣지 못하면 null(원래 대화로). */
    public TurnResponse answerWornOffer(AgentSession session, String utterance) {
        session.wornOfferAsked = false;
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (NO.stream().anyMatch(s::contains)) {
            sessions.save(session);
            return new TurnResponse(session.sessionId, "알겠어요. 잘 다녀오세요.", Next.done, null);
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

    /** 종류마다 가장 잘 맞는 옷 하나 (같으면 나중에 본 옷). bad는 뺍니다. */
    static List<Garment> chosen(AgentSession session) {
        Map<String, AgentSession.Seen> best = new LinkedHashMap<>();
        for (AgentSession.Seen s : session.seen) {
            if ("bad".equals(s.fit())) {
                continue;
            }
            best.merge(s.garment().category(), s, (old, neu) -> rank(neu.fit()) >= rank(old.fit()) ? neu : old);
        }
        return best.values().stream().map(AgentSession.Seen::garment).toList();
    }

    private static int rank(String fit) {
        return "good".equals(fit) ? 2 : "ok".equals(fit) ? 1 : 0;
    }

    private boolean record(String userId, List<Garment> chosen) {
        try {
            List<?> garments = json.convertValue(chosen, List.class);
            JsonNode saved = tools.callForUser(ToolNames.CLOSET_SAVE, userId, Map.of("garments", garments));
            List<String> ids = new ArrayList<>();
            for (JsonNode s : saved.path("saved")) {
                ids.add(s.path("garmentId").asString());
            }
            tools.callForUser(ToolNames.CLOSET_WEAR, userId, Map.of("garmentIds", ids, "date", today().toString()));
            return true;
        } catch (ToolException e) {
            log.warn("옷장 기록 실패 (추천은 계속): {}", e.getMessage());
            return false;
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
