package com.fashionagent.agent.judge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.chat.client.ChatClient;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
import com.fashionagent.agent.model.Garment;

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
        input.put("weather", in.weather());
        input.put("events", in.events());
        input.put("closetCandidates", in.closetCandidates());
        input.put("styleNotes", in.styleNotes());
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
        input.put("weather", in.weather());
        input.put("events", in.events());
        input.put("advice", in.advice() == null ? null : in.advice().suggestions());
        input.put("now", in.now().stream().map(LlmOutfitJudge::forPrompt).toList());
        input.put("seenBefore", in.seenBefore());
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

    /** GPT에는 판단에 필요한 값만 (box 좌표 등은 뺌). */
    private static Map<String, Object> forPrompt(Garment g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.id());
        m.put("position", g.position());
        m.put("category", g.category());
        m.put("name", g.name());
        m.put("colorName", g.colorName());
        m.put("colorFamily", g.colorFamily());
        m.put("tone", g.tone());
        m.put("pattern", g.pattern());
        m.put("material", g.material());
        m.put("formality", g.formality());
        m.put("defects", g.defects());
        return m;
    }
}
