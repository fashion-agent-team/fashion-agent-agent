package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.model.OutfitCheck.Damage;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RuleOutfitJudgeTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final RuleOutfitJudge judge = new RuleOutfitJudge();

    private static Garment top(String id, String color, String family, String formality, Garment.Defect... d) {
        return new Garment(id, "top", "셔츠", color, family, "light", null, null, null, formality, List.of(d), null,
                "center", 0.9);
    }

    private JsonNode events(String occasion) {
        return json.readTree("[{\"title\":\"결혼식\",\"occasion\":\"" + occasion + "\"}]");
    }

    @Test
    void 결혼식에_흰색_상의는_bad() {
        var out = judge.check(new OutfitJudge.CheckInput(null, events("wedding"), null,
                List.of(top("g1", "흰색", "white", "smart")), List.of()));
        assertThat(out.verdicts().get(0).fit()).isEqualTo("bad");
        assertThat(out.comparison()).isNull();
    }

    @Test
    void 얼룩은_확신도에_따라_할일이_다르다() {
        Garment g = top("g1", "흰색", "white", "smart");
        Damage sure = RuleOutfitJudge.damage(g, new Garment.Defect("stain", "앞", 0.85));
        Damage maybe = RuleOutfitJudge.damage(g, new Garment.Defect("stain", "앞", 0.6));
        Damage ignore = RuleOutfitJudge.damage(g, new Garment.Defect("stain", "앞", 0.3));
        Damage tear = RuleOutfitJudge.damage(g, new Garment.Defect("tear", "앞", 0.9));
        Damage lint = RuleOutfitJudge.damage(g, new Garment.Defect("lint", "앞", 0.9));
        assertThat(sure.action()).isEqualTo("wash");
        assertThat(sure.say()).contains("빨래").doesNotContain("앞");
        assertThat(maybe.action()).isEqualTo("check");
        assertThat(ignore).isNull();
        assertThat(tear.action()).isEqualTo("repair");
        assertThat(lint.action()).isEqualTo("ok");
    }

    @Test
    void 쌀쌀하면_겉옷을_추천한다() {
        JsonNode weather = json.readTree("{\"apparentTemperature\": 9.5, \"dailyRange\": 11}");
        var out = judge.advise(new OutfitJudge.AdviceInput(weather, json.readTree("[]"), null, null));
        assertThat(out.suggestions()).extracting(s -> s.category()).contains("outer");
        assertThat(out.speech()).contains("체감 10도");
    }

    @Test
    void 날씨를_못_불러와도_추천은_한다() {
        var out = judge.advise(new OutfitJudge.AdviceInput(null, null, null, null));
        assertThat(out.speech()).contains("불러오지 못했어요").contains("카메라");
    }
}
