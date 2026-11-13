package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.Garment;

class LlmPromptInputTest {

    private final Garment shirt = new Garment("g1", "top", "셔츠", "검정", "black", "dark", "check", null, null, "smart",
            List.of(), null, "center", 0.94, "베이지", "beige", "model", null);

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

    @Test
    @DisplayName("무늬를 모르면(pattern=null) label에 무늬를 넣지 않고 patternGuess만 한국어로")
    void guess() {
        var g = new Garment("g1", "top", "셔츠", "검정", "black", "dark", null, null, null, "smart", List.of(), null,
                "center", 0.51, "회색", "gray", null, "animal");
        var m = LlmOutfitJudge.forPrompt(g, false);
        assertThat(m.get("label")).isEqualTo("검정·회색 셔츠");
        assertThat(m.get("patternGuess")).isEqualTo("호피 무늬");
    }

    @Test
    @DisplayName("내 정보(wearer)는 있을 때만 GPT 입력에 넣는다 (2026-11-13)")
    void wearer() {
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var input = new java.util.LinkedHashMap<String, Object>();
        LlmOutfitJudge.putWearer(input, null);
        LlmOutfitJudge.putWearer(input, json.createObjectNode());
        assertThat(input).doesNotContainKey("wearer");

        LlmOutfitJudge.putWearer(input, json.readTree("{\"gender\":\"남자\",\"heightCm\":180}"));
        assertThat(input.get("wearer").toString()).contains("남자").contains("180");
    }
}
