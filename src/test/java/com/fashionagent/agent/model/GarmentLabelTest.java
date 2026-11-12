package com.fashionagent.agent.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GarmentLabelTest {

    private static Garment g(String color, String sub, String pattern, String name) {
        return new Garment("g1", "top", name, color, "black", "dark", pattern, null, null, "smart", List.of(), null,
                "center", 0.9, sub, null, "model");
    }

    @Test
    @DisplayName("무늬 옷은 두 색과 무늬를 붙여 읽는다 (2026-11-12: 체크·호피 셔츠를 \"검정 셔츠\"라고 함)")
    void patterned() {
        assertThat(g("검정", "베이지", "check", "셔츠").label()).isEqualTo("검정·베이지 체크 셔츠");
        assertThat(g("검정", "회색", "animal", "셔츠").label()).isEqualTo("검정·회색 호피 무늬 셔츠");
        assertThat(g("흰색", null, "stripe", "셔츠").label()).isEqualTo("흰색 줄무늬 셔츠");
        assertThat(g("검정", null, "other", "니트").label()).isEqualTo("무늬 있는 검정 니트");
    }

    @Test
    @DisplayName("무지·모름은 예전처럼 색 + 종류, 위치는 이름에 넣지 않는다")
    void plain() {
        assertThat(g("흰색", null, "plain", "셔츠").label()).isEqualTo("흰색 셔츠");
        assertThat(g("흰색", null, null, "셔츠").label()).isEqualTo("흰색 셔츠");
        assertThat(g(null, null, null, null).label()).isEqualTo("옷");
        assertThat(g("흰색", null, "plain", "셔츠").patterned()).isFalse();
        assertThat(g("흰색", null, "check", "셔츠").patterned()).isTrue();
    }
}
