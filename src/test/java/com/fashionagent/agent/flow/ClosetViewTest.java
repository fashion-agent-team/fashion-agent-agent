package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

class ClosetViewTest {

    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    @DisplayName("말에 종류가 있으면 closet_list category로")
    void category() {
        assertThat(ClosetView.category("옷장에 바지 뭐 있어")).isEqualTo("bottom");
        assertThat(ClosetView.category("옷장 겉옷 보여 줘")).isEqualTo("outer");
        assertThat(ClosetView.category("옷장 확인해줘")).isNull();
    }

    @Test
    @DisplayName("빈 옷장은 어떻게 채우는지 알려 준다")
    void empty() {
        var list = json.readTree("{\"garments\":[],\"total\":0}");
        assertThat(ClosetView.speech(list, null)).startsWith("옷장이 아직 비어 있어요.").contains("이걸로 할게");
        assertThat(ClosetView.speech(list, "bottom")).startsWith("옷장에 하의는 아직 없어요.");
        // 2026-11-12 e2e: "신발는" → "신발은"
        assertThat(ClosetView.speech(list, "shoes")).startsWith("옷장에 신발은 아직 없어요.");
        var one = json.readTree("{\"total\":1,\"garments\":[{\"category\":\"outer\",\"name\":\"패딩\"}]}");
        assertThat(ClosetView.speech(one, "outer")).startsWith("옷장에 겉옷이 1벌 있어요.");
    }

    @Test
    @DisplayName("종류별로 벌 수와 이름(색+이름), 4벌 넘으면 외 n벌")
    void speech() {
        var list = json.readTree("""
                {"total": 8, "garments": [
                  {"category":"outer","name":"패딩","colorName":"검정"},
                  {"category":"top","name":"니트","colorName":"아이보리"},
                  {"category":"top","name":"셔츠","colorName":""},
                  {"category":"top","name":"티셔츠","colorName":"흰색"},
                  {"category":"top","name":"맨투맨","colorName":"회색"},
                  {"category":"top","name":"블라우스","colorName":"분홍"},
                  {"category":"bottom","name":"청바지","colorName":"청색"},
                  {"category":"bottom","name":"검정 슬랙스","colorName":"검정"}
                ]}""");
        assertThat(ClosetView.speech(list, null)).isEqualTo("옷장에 옷이 8벌 있어요. 겉옷 1벌: 검정 패딩. "
                + "상의 5벌: 아이보리 니트, 셔츠, 흰색 티셔츠, 회색 맨투맨 외 1벌. 하의 2벌: 청색 청바지, 검정 슬랙스.");
    }

    @Test
    @DisplayName("다 못 받아 왔으면 종류를 말해 달라고 덧붙인다")
    void more() {
        var list = json.readTree("{\"total\": 40, \"garments\": [{\"category\":\"bottom\",\"name\":\"청바지\"}]}");
        assertThat(ClosetView.speech(list, "bottom")).isEqualTo("옷장에 하의가 40벌 있어요. 청바지. "
                + "나머지 39벌은 \"바지 뭐 있어?\"처럼 종류를 말씀해 주시면 읽어 드릴게요.");
    }
}
