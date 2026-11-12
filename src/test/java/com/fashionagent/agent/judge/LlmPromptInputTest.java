package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.Garment;

class LlmPromptInputTest {

    private final Garment shirt = new Garment("g1", "top", "셔츠", "검정", "black", "dark", "check", null, null, "smart",
            List.of(), null, "center", 0.94, "베이지", "beige", "model");

    @Test
    @DisplayName("옷이 한 벌이면 GPT에 위치를 주지 않는다 (2026-11-12: \"중앙 검정 셔츠\")")
    void onlyOne() {
        var m = LlmOutfitJudge.forPrompt(shirt, false);
        assertThat(m).doesNotContainKey("position");
        assertThat(m.get("label")).isEqualTo("검정·베이지 체크 셔츠");
        assertThat(m.get("subColorName")).isEqualTo("베이지");
    }

    @Test
    @DisplayName("두 벌 이상이면 위치를 한국어로(가운데)")
    void many() {
        assertThat(LlmOutfitJudge.forPrompt(shirt, true).get("position")).isEqualTo("가운데");
    }
}
