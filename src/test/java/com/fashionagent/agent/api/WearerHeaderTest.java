package com.fashionagent.agent.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

class WearerHeaderTest {

    static String enc(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 정해진_칸만_남긴다() {
        JsonNode w = TurnController.wearer(enc("""
                {"gender":"여자","ageGroup":"20대","heightCm":162.5,"weightKg":50,"job":"회사원","name":"진행","birthDate":"2001-01-01"}"""));
        assertThat(w.propertyNames()).containsExactlyInAnyOrder("gender", "ageGroup", "heightCm", "weightKg", "job");
        assertThat(w.path("job").asString()).isEqualTo("회사원");
        assertThat(w.path("heightCm").asDouble()).isEqualTo(162.5);
    }

    @Test
    void 없거나_깨졌거나_비면_null() {
        assertThat(TurnController.wearer(null)).isNull();
        assertThat(TurnController.wearer("")).isNull();
        assertThat(TurnController.wearer("!!!not-base64")).isNull();
        assertThat(TurnController.wearer(enc("[1,2]"))).isNull();
        assertThat(TurnController.wearer(enc("{\"name\":\"진행\",\"job\":\" \"}"))).isNull();
    }
}
