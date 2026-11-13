package com.fashionagent.agent.judge;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
import com.fashionagent.agent.model.Garment;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GPT로 판단합니다. 구조화 출력(entity)으로 받습니다. 모델은 application-openai.yml에서 정합니다.
 * 사진은 넣지 않습니다. clothes_inspect가 돌려준 JSON만 들어갑니다(약속 5-3절).
 */
public class LlmOutfitJudge implements OutfitJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmOutfitJudge.class);

    /** 마지막 판단 뒤 이만큼은 연결을 데워 둡니다(대화 중엔 늘 빠르게, 밤새 쉴 땐 부르지 않게). */
    static final Duration KEEP_WARM_FOR = Duration.ofMinutes(30);
    /** OpenAI 연결·지시문 캐시가 식기 전에 다시 부르는 간격. */
    static final Duration WARM_EVERY = Duration.ofMinutes(4);

    private final ChatClient chat;
    private final JsonMapper json;
    private volatile long lastUsedNanos = System.nanoTime();

    public LlmOutfitJudge(ChatClient.Builder builder, JsonMapper json) {
        this.chat = builder.defaultSystem(Prompts.SYSTEM).build();
        this.json = json;
    }

    /**
     * 켜질 때 한 번, 그 뒤 판단을 쓴 지 {@link #KEEP_WARM_FOR} 안이면 {@link #WARM_EVERY}마다 아주 짧은 GPT 호출을 합니다.
     * 2026-11-13 측정: 첫 판단은 2.1초, 그다음부터 1.0~1.4초(연결 맺기·지시문 캐시). 데워 두면 첫 판단도 빨라집니다.
     * 지시문(SYSTEM)이 같아서 OpenAI 지시문 캐시도 함께 데워집니다. 실패해도 괜찮습니다(로그만).
     */
    public void startWarming() {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "gpt-warm");
            t.setDaemon(true);
            return t;
        });
        timer.schedule(this::warm, 0, TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(() -> {
            if (System.nanoTime() - lastUsedNanos < KEEP_WARM_FOR.toNanos()) {
                warm();
            }
        }, WARM_EVERY.toSeconds(), WARM_EVERY.toSeconds(), TimeUnit.SECONDS);
    }

    void warm() {
        long started = System.nanoTime();
        try {
            chat.prompt().user(Prompts.WARM).call().content();
            log.info("[시간] GPT 데우기 {}ms", (System.nanoTime() - started) / 1_000_000);
        } catch (RuntimeException e) {
            log.info("GPT 데우기 실패(괜찮음): {}", e.toString());
        }
    }

    @Override
    public AdviceJudgement advise(AdviceInput in) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("day", in.day() == null ? "오늘" : in.day());
        input.put("weather", in.weather());
        if (in.weatherNote() != null) {
            input.put("weatherNote", in.weatherNote());
        }
        input.put("events", in.events());
        input.put("closetCandidates", in.closetCandidates());
        input.put("styleNotes", in.styleNotes());
        putWearer(input, in.wearer());
        long started = System.nanoTime();
        lastUsedNanos = started;
        AdviceJudgement out = chat.prompt()
                .user(Prompts.ADVICE.formatted(json.writeValueAsString(input)))
                .call()
                .entity(AdviceJudgement.class);
        log.info("[시간] GPT 추천 {}ms", (System.nanoTime() - started) / 1_000_000);
        if (out == null || out.speech() == null || out.speech().isBlank()) {
            throw new IllegalStateException("GPT 추천 결과가 비었어요.");
        }
        Set<String> allowed = in.closetCandidates() == null ? Set.of()
                : in.closetCandidates().valueStream().map(c -> c.path("garmentId").asString("")).collect(Collectors.toSet());
        // 옷장에 없는 옷을 지어내면 뺍니다.
        return new AdviceJudgement(out.suggestions(),
                out.closetPicks().stream().filter(p -> allowed.contains(p.garmentId())).toList(), Korean.spoken(out.speech()));
    }

    @Override
    public CheckJudgement check(CheckInput in) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("day", in.day());
        input.put("weather", in.weather());
        input.put("events", in.events());
        input.put("advice", in.advice() == null ? null : in.advice().suggestions());
        boolean many = in.now().size() >= 2;
        input.put("now", in.now().stream().map(g -> forPrompt(g, many)).toList());
        input.put("seenBefore", in.seenBefore());
        putWearer(input, in.wearer());
        input.put("colorHarmony", colorForPrompt(in.colorHarmony()));
        long started = System.nanoTime();
        lastUsedNanos = started;
        CheckJudgement out = chat.prompt()
                .user(Prompts.CHECK.formatted(json.writeValueAsString(input)))
                .call()
                .entity(CheckJudgement.class);
        log.info("[시간] GPT 옷 확인 {}ms (옷 {}벌)", (System.nanoTime() - started) / 1_000_000, in.now().size());
        if (out == null || out.speech() == null || out.speech().isBlank()) {
            throw new IllegalStateException("GPT 확인 결과가 비었어요.");
        }
        Set<String> ids = in.now().stream().map(Garment::id).collect(Collectors.toSet());
        var comparison = in.now().size() >= 2 && out.comparison() != null
                && ids.contains(out.comparison().betterInputId()) ? out.comparison() : null;
        var verdicts = out.verdicts().stream().filter(v -> ids.contains(v.inputId())).toList();
        return new CheckJudgement(
                verdicts,
                comparison,
                out.damage().stream().filter(d -> ids.contains(d.inputId())).toList(),
                out.stillMissing(),
                // 안 맞는 옷에 "잘 고르셨어요"로 시작하면 뺍니다.
                Korean.spoken(PraiseGuard.strip(out.speech(), verdicts)));
    }

    /**
     * 색 조합 결과 중 GPT가 말할 때 쓸 값만. 점수 계산 내역(parts)은 발표·로그용이라 빼고, 조합마다 점수·까닭만 넣습니다.
     * 점수가 없으면(옷이 한 벌뿐 등) null.
     */
    static Map<String, Object> colorForPrompt(JsonNode c) {
        if (c == null || c.isNull() || !c.path("score").isNumber()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", c.path("score").asInt());
        m.put("scheme", c.path("scheme").asString(null));
        m.put("reason", c.path("scoreReason").asString(null));
        m.put("verdict", c.path("verdict").asString(null));
        m.put("issues", c.path("issues").valueStream()
                .filter(i -> !"info".equals(i.path("level").asString("")))
                .map(i -> i.path("say").asString("")).filter(s -> !s.isBlank()).toList());
        if (c.path("outfits").size() >= 2) {
            m.put("outfits", c.path("outfits").valueStream().map(o -> Map.of(
                    "garmentIds", o.path("garmentIds").valueStream().map(id -> id.asString("")).toList(),
                    "score", o.path("score").asInt(),
                    "scheme", o.path("schemeName").asString(""))).toList());
        }
        return m;
    }

    /**
     * GPT에는 판단에 필요한 값만 (box 좌표 등은 뺌). label은 읽어 줄 이름(색·무늬·종류)이라 GPT가 그대로 쓰게 합니다.
     * 옷이 한 벌이면 position을 넣지 않습니다. 2026-11-12: 한 벌인데 "중앙 검정 셔츠"라고 읽음.
     */
    /** 내 정보가 있을 때만 넣습니다(건너뛴 칸은 게이트웨이가 이미 뺌). 이름·생년월일은 오지 않습니다. */
    static void putWearer(Map<String, Object> input, JsonNode wearer) {
        if (wearer != null && wearer.isObject() && !wearer.isEmpty()) {
            input.put("wearer", wearer);
        }
    }

    static Map<String, Object> forPrompt(Garment g, boolean withPosition) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("label", g.label());
        if (withPosition) {
            m.put("position", Korean.position(g.position()).isEmpty() ? null : Korean.position(g.position()));
        }
        m.put("category", g.category());
        m.put("name", g.name());
        m.put("colorName", g.colorName());
        m.put("subColorName", g.subColorName());
        m.put("colorFamily", g.colorFamily());
        m.put("tone", g.tone());
        m.put("pattern", g.pattern());
        if (g.pattern() == null && g.patternGuess() != null) {
            m.put("patternGuess", Garment.patternName(g.patternGuess()));
        }
        if ("gpt".equals(g.patternCheckedBy())) {
            m.put("patternCheckedBy", "gpt");
        }
        m.put("material", g.material());
        m.put("formality", g.formality());
        m.put("defects", g.defects());
        return m;
    }
}
