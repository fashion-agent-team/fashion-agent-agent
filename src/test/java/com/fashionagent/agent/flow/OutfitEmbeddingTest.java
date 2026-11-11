package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.judge.OutfitJudge.CheckInput;
import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.session.AgentSession;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** clothes_inspect의 옷 벡터는 옷장(closet_save)에만 넘기고 GPT 프롬프트에는 넣지 않습니다. */
class OutfitEmbeddingTest {

    private final JsonMapper json = JsonMapper.builder().build();

    private final JsonNode inspect = json.readTree("""
            {"garments": [
               {"id": "g1", "category": "top", "name": "셔츠", "colorName": "흰색", "embedding": [0.6, 0.8]},
               {"id": "g2", "category": "bottom", "name": "슬랙스", "colorName": "남색", "embedding": null}],
             "embeddingModel": "marqo-fashionSigLIP"}""");

    @Test
    void 벡터는_세션에_따로_두고_closet_save에만_붙인다() {
        var session = new AgentSession("s", "u");
        OutfitFlow.keepEmbeddings(session, inspect, "p1_");
        assertThat(session.embeddings).containsOnlyKeys("p1_g1");

        List<Garment> chosen = List.of(
                json.treeToValue(inspect.path("garments").get(0), Garment.class).withId("p1_g1"),
                json.treeToValue(inspect.path("garments").get(1), Garment.class).withId("p1_g2"));
        List<Map<String, Object>> out = OutfitFlow.forCloset(json, chosen, session.embeddings);

        JsonNode g1 = json.valueToTree(out.get(0));
        assertThat(g1.path("embeddingModel").asString()).isEqualTo("marqo-fashionSigLIP");
        assertThat(g1.path("embedding").get(0).asDouble()).isEqualTo(0.6f);
        assertThat(json.valueToTree(out.get(1)).has("embedding")).isFalse();
    }

    @Test
    void GPT로_가는_확인_입력에는_벡터가_없다() {
        Garment g = json.treeToValue(inspect.path("garments").get(0), Garment.class);
        String prompt = json.writeValueAsString(new CheckInput(null, null, null, List.of(g), List.of()));
        assertThat(prompt).contains("셔츠").doesNotContain("embedding").doesNotContain("0.8");
    }

    @Test
    void 모델_이름이_없으면_벡터를_두지_않는다() {
        var session = new AgentSession("s", "u");
        OutfitFlow.keepEmbeddings(session, json.readTree("""
                {"garments": [{"id": "g1", "embedding": [0.6, 0.8]}]}"""), "p1_");
        assertThat(session.embeddings).isEmpty();
    }
}
