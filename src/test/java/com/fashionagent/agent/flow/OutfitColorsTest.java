package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.judge.OutfitJudge.CheckInput;
import com.fashionagent.agent.judge.RuleOutfitJudge;
import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.session.AgentSession;

import tools.jackson.databind.json.JsonMapper;

class OutfitColorsTest {

    @Test
    void 면접날_남색_셔츠는_느낌과_어울림을_말함() {
        var session = new AgentSession("s", "u");
        session.events = JsonMapper.builder().build().readTree("[{\"title\":\"면접\",\"occasion\":\"formal\"}]");
        var shirt = new Garment("p1_g1", "top", "셔츠", "남색", "navy", "dark", "plain", null, null, "smart",
                List.of(), null, null, 0.9);
        var judged = new RuleOutfitJudge().check(new CheckInput(null, session.events, null, List.of(shirt), List.of()));
        String speech = OutfitFlow.withColors(session, List.of(shirt), judged).speech();
        System.out.println(speech);
        assertThat(speech).startsWith("남색 셔츠는").contains("깊은 바닷물처럼").contains("느낌이에요. 면접에 잘 어울리는 색이에요.")
                .endsWith("라고 말해 주세요.");

        session.colorDetail = false;
        session.colorsTold.clear();
        session.colorsToldEvent.clear();
        String brief = OutfitFlow.withColors(session, List.of(shirt), judged).speech();
        assertThat(brief).doesNotContain("바닷물").contains("면접에 잘 어울리는 색");
    }
}
