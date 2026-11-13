package com.fashionagent.agent.judge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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

    private final ChatClient chat;
    private final JsonMapper json;

    public LlmOutfitJudge(ChatClient.Builder builder, JsonMapper json) {
        this.chat = builder.defaultSystem(Prompts.SYSTEM).build();
        this.json = json;
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
        AdviceJudgement out = chat.prompt()
                .user(Prompts.ADVICE.formatted(json.writeValueAsString(input)))
                .call()
                .entity(AdviceJudgement.class);
        if (out == null || out.speech() == null || out.speech().isBlank()) {
            throw new IllegalStateException("GPT 추천 결과가 비었어요.");
        }
        Set<String> allowed = in.closetCandidates() == null ? Set.of()
                : in.closetCandidates().valueStream().map(c -> c.path("garmentId").asString("")).collect(Collectors.toSet());
        // 옷장에 없는 옷을 지어내면 뺍니다.
        return new AdviceJudgement(out.suggestions(),
                out.closetPicks().stream().filter(p -> allowed.contains(p.garmentId())).toList(), out.speech());
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
        CheckJudgement out = chat.prompt()
                .user(Prompts.CHECK.formatted(json.writeValueAsString(input)))
                .call()
                .entity(CheckJudgement.class);
        if (out == null || out.speech() == null || out.speech().isBlank()) {
            throw new IllegalStateException("GPT 확인 결과가 비었어요.");
        }
        Set<String> ids = in.now().stream().map(Garment::id).collect(Collectors.toSet());
        var comparison = in.now().size() >= 2 && out.comparison() != null
                && ids.contains(out.comparison().betterInputId()) ? out.comparison() : null;
        return new CheckJudgement(
                out.verdicts().stream().filter(v -> ids.contains(v.inputId())).toList(),
                comparison,
                out.damage().stream().filter(d -> ids.contains(d.inputId())).toList(),
                out.stillMissing(),
                out.speech());
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
