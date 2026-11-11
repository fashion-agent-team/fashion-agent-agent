package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.judge.Occasion;

class ClosetCallsTest {

    @Test
    @DisplayName("imageQuery: 자리와 체감 온도로 짧은 영어 문장")
    void imageQuery() {
        assertThat(ClosetQuery.english(Occasion.business, 2.0))
                .isEqualTo("neat office attire, dress shirt and slacks, warm winter clothes, coat and knit sweater");
        assertThat(ClosetQuery.english(Occasion.active, 28.0)).isEqualTo("workout sportswear, light summer clothes, short sleeves");
        assertThat(ClosetQuery.english(null, null)).isEqualTo("casual everyday clothes");
    }

    @Test
    @DisplayName("closet_save: 벡터가 있는 옷에만 embedding, 하나라도 있으면 embeddingModel")
    void saveArgs() {
        Map<String, Object> a = new LinkedHashMap<>(Map.of("id", "p1_g1", "category", "top"));
        Map<String, Object> b = new LinkedHashMap<>(Map.of("id", "p2_g1", "category", "bottom"));
        var args = OutfitFlow.saveArgs(List.of(a, b), Map.of("p1_g1", List.of(0.1, 0.2)), "marqo-fashionSigLIP");
        @SuppressWarnings("unchecked")
        var gs = (List<Map<String, Object>>) args.get("garments");
        assertThat(gs.get(0)).containsEntry("embedding", List.of(0.1, 0.2));
        assertThat(gs.get(1)).doesNotContainKey("embedding");
        assertThat(args).containsEntry("embeddingModel", "marqo-fashionSigLIP");
        assertThat(a).doesNotContainKey("embedding");

        var none = OutfitFlow.saveArgs(List.of(b), Map.of(), null);
        assertThat(none).doesNotContainKey("embeddingModel");
    }
}
