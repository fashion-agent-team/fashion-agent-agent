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
 * 옷 추천 2단계 (약속 5-1절). 도구를 부르는 순서는 여기 코드로 정해져 있고, Gemini는 판단만 합니다.
 * <ol>
 * <li>{@link #advise}: weather_today + schedule_list (+ closet_search) → 추천 모드 → next=camera_outfit</li>
 * <li>{@link #check}: 자동 전송된 한 장 → clothes_inspect → 확인 모드 → next=camera_outfit</li>
 * <li>{@link #where}: "어디야?" → 직전 얼룩 위치</li>
 * <li>{@link #finish}: "이걸로 할게" → closet_save + closet_wear → next=done</li>
 * </ol>
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
        sessions.save(session);
        return new TurnResponse(session.sessionId, advice.speech(), Next.camera_outfit,
                new Result("outfit_advice", advice.toAdvice(), null));
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
                : String.join(" ", sentences) + " 옷을 내려놓은 그대로, 사진에서 보이는 방향 기준이에요.";
        return new TurnResponse(session.sessionId, speech, Next.camera_outfit, null);
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
                    Next.camera_outfit, null);
        }
        boolean recorded = record(session.userId, chosen);
        String names = String.join(", ", chosen.stream().map(Garment::label).toList());
        String speech = "좋아요. " + Korean.josa(names, "으로", "로") + (recorded ? " 기록해 둘게요." : " 정했어요.")
                + " 잘 다녀오세요.";
        session.seen = new ArrayList<>();
        session.lastDamage = new ArrayList<>();
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, Next.done, null);
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
