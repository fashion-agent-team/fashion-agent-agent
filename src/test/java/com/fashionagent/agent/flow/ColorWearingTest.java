package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.session.AgentSession;

/** 색 어울림 점수에 넘길 옷: 지금 사진의 옷 + 앞서 본 옷 중 다른 종류(어울린다고 한 것). */
class ColorWearingTest {

    private static Garment g(String id, String category, String family) {
        return new Garment(id, category, "옷", family, family, "mid", null, null, null, null, List.of(), null, null, 0.9);
    }

    @Test
    @DisplayName("바지를 먼저 보고 셔츠 두 벌을 비교하면 셔츠 두 벌 + 바지")
    void addsEarlierBottom() {
        var pants = new AgentSession.Seen(g("p1_g1", "bottom", "navy"), "good");
        var now = List.of(g("p2_g1", "top", "white"), g("p2_g2", "top", "red"));
        assertThat(OutfitFlow.wearing(now, List.of(pants))).extracting(Garment::id)
                .containsExactly("p2_g1", "p2_g2", "p1_g1");
    }

    @Test
    @DisplayName("바꾸자고 한(bad) 옷과 지금 사진과 같은 종류는 넣지 않고, 같은 종류면 가장 최근 것 하나")
    void skipsBadAndSameKind() {
        var oldPants = new AgentSession.Seen(g("p1_g1", "bottom", "green"), "ok");
        var badOuter = new AgentSession.Seen(g("p2_g1", "outer", "red"), "bad");
        var newPants = new AgentSession.Seen(g("p3_g1", "bottom", "black"), "good");
        var oldTop = new AgentSession.Seen(g("p3_g2", "top", "gray"), "good");
        var now = List.of(g("p4_g1", "top", "white"));
        assertThat(OutfitFlow.wearing(now, List.of(oldPants, badOuter, newPants, oldTop))).extracting(Garment::id)
                .containsExactly("p4_g1", "p3_g1");
    }
}
