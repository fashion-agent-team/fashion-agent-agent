package com.fashionagent.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/** 바디의 wearer(WearerProfile) → GPT에 넘길 JSON. */
class WearerProfileTest {

    @Test
    @DisplayName("Given 모든 칸이 있는 wearer When 바꾸면 Then 다섯 칸을 그대로 담는다")
    void allFields() {
        // when
        JsonNode w = TurnController.wearer(new WearerProfile("여자", "20대", 162.5, 50.0, " 회사원 "));

        // then
        assertThat(w.propertyNames()).containsExactlyInAnyOrder("gender", "ageGroup", "heightCm", "weightKg", "job");
        assertThat(w.path("job").asString()).isEqualTo("회사원");
        assertThat(w.path("heightCm").asDouble()).isEqualTo(162.5);
    }

    @Test
    @DisplayName("Given 없거나 빈 칸뿐인 wearer When 바꾸면 Then null(정보 없이 대화)")
    void emptyIsNull() {
        assertThat(TurnController.wearer(null)).isNull();
        assertThat(TurnController.wearer(new WearerProfile(null, " ", null, null, ""))).isNull();
    }
}
