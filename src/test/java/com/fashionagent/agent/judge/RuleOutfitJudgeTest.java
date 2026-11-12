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
    void GPT가_확인한_얼룩은_같아요로_말한다() {
        Garment g = top("g1", "흰색", "white", "smart");
        Damage gpt = RuleOutfitJudge.damage(g, new Garment.Defect("stain", "앞", 0.3, "gpt"));
        assertThat(gpt.action()).isEqualTo("wash");
        assertThat(gpt.say()).contains("같아요");
    }

    @Test
    void GPT가_없어도_옷장_후보에서_종류마다_한_벌씩_고른다_2026_11_12() {
        JsonNode weather = json.readTree("{\"apparentTemperature\": 1, \"dailyRange\": 8}");
        JsonNode closet = json.readTree("""
                [{"garmentId":"c_1","category":"outer","name":"패딩","colorName":"검정"},
                 {"garmentId":"c_2","category":"bottom","name":"청바지","colorName":"청색"},
                 {"garmentId":"c_3","category":"outer","name":"코트","colorName":"베이지"},
                 {"garmentId":"c_4","category":"top","name":"니트","colorName":"아이보리"}]""");
        var out = judge.advise(new OutfitJudge.AdviceInput(weather, json.readTree("[]"), closet, null));
        assertThat(out.closetPicks()).extracting(p -> p.garmentId()).containsExactly("c_4", "c_2", "c_1");
        assertThat(out.speech()).endsWith("옷장에 있는 아이보리 니트, 청색 청바지, 검정 패딩이 잘 맞겠어요.");

        JsonNode warm = json.readTree("{\"apparentTemperature\": 24, \"dailyRange\": 6}");
        var noOuter = judge.advise(new OutfitJudge.AdviceInput(warm, json.readTree("[]"), closet, null));
        assertThat(noOuter.closetPicks()).extracting(p -> p.garmentId()).containsExactly("c_4", "c_2");

        JsonNode dress = json.readTree("[{\"garmentId\":\"c_9\",\"category\":\"dress\",\"name\":\"원피스\",\"colorName\":\"남색\"},"
                + "{\"garmentId\":\"c_2\",\"category\":\"bottom\",\"name\":\"청바지\",\"colorName\":\"청색\"}]");
        assertThat(judge.advise(new OutfitJudge.AdviceInput(warm, json.readTree("[]"), dress, null)).closetPicks())
                .extracting(p -> p.garmentId()).containsExactly("c_9");

        var empty = judge.advise(new OutfitJudge.AdviceInput(weather, json.readTree("[]"), json.readTree("[]"), null));
        assertThat(empty.closetPicks()).isEmpty();
        assertThat(empty.speech()).doesNotContain("옷장");
    }

    @Test
    void 종류가_다른_옷은_서로_견주지_않는다_2026_11_12() {
        var padding = new Garment("g1", "outer", "패딩", "검정", "black", "dark", null, null, null, "casual", List.of(),
                null, "center", 0.9);
        var jeans = new Garment("g2", "bottom", "청바지", "청색", "blue", "mid", null, null, null, "casual", List.of(),
                null, "center", 0.9);
        var out = judge.check(new OutfitJudge.CheckInput(null, json.readTree("[]"), null, List.of(padding, jeans), List.of()));
        assertThat(out.comparison()).isNull();
        assertThat(out.speech()).doesNotContain("보다");

        var two = judge.check(new OutfitJudge.CheckInput(null, json.readTree("[]"), null,
                List.of(padding, top("g3", "흰색", "white", "smart"), top("g4", "회색", "gray", "casual")), List.of()));
        assertThat(two.comparison()).isNotNull();
        assertThat(two.comparison().betterInputId()).isIn("g3", "g4");
    }

    @Test
    void 내일_입을_옷을_견줄_때는_오늘이라고_하지_않는다_2026_11_12_시뮬() {
        var out = judge.check(new OutfitJudge.CheckInput(null, json.readTree("[]"), null,
                List.of(top("g1", "흰색", "white", "smart"), top("g2", "회색", "gray", "casual")), List.of(), "내일"));
        assertThat(out.speech()).contains("내일 더 잘 맞아요").doesNotContain("오늘");
    }

    @Test
    void 쌀쌀하면_겉옷을_추천한다() {
        JsonNode weather = json.readTree("{\"apparentTemperature\": 9.5, \"dailyRange\": 11}");
        var out = judge.advise(new OutfitJudge.AdviceInput(weather, json.readTree("[]"), null, null));
        assertThat(out.suggestions()).extracting(s -> s.category()).contains("outer");
        assertThat(out.speech()).contains("겉옷을 추천드려요");
    }

    @Test
    void 날씨를_못_불러와도_추천은_한다() {
        var out = judge.advise(new OutfitJudge.AdviceInput(null, null, null, null));
        assertThat(out.speech()).contains("추천드려요").contains("겉옷도 챙겨");
    }

    @Test
    void 추운날_반바지는_bad() {
        JsonNode weather = json.readTree("{\"apparentTemperature\": 5, \"dailyRange\": 6}");
        Garment shorts = new Garment("g1", "bottom", "반바지", "갈색", "brown", "dark", null, null, null, "casual",
                List.of(), null, "center", 0.9);
        var out = judge.check(new OutfitJudge.CheckInput(weather, json.readTree("[]"), null, List.of(shorts), List.of()));
        assertThat(out.verdicts().get(0).fit()).isEqualTo("bad");
        assertThat(out.speech()).contains("추울");
    }

    @Test
    void 옷_종류를_모르면_겉옷이_없다고_하지_않고_확인을_부탁한다() {
        JsonNode weather = json.readTree("{\"apparentTemperature\": 5, \"dailyRange\": 6}");
        var advice = judge.advise(new OutfitJudge.AdviceInput(weather, json.readTree("[]"), null, null));
        Garment other = new Garment("g1", "other", null, "검정", "black", "dark", null, null, null, "unknown",
                List.of(), null, null, null);
        var out = judge.check(new OutfitJudge.CheckInput(weather, json.readTree("[]"), advice, List.of(other),
                List.of()));
        assertThat(out.verdicts().get(0).fit()).isEqualTo("ok");
        assertThat(out.stillMissing()).isEmpty();
        assertThat(out.speech()).contains("겉옷을 입으셨는지").doesNotContain("겉옷도 보여");
    }

    @Test
    void 무지_plain은_무늬로_치지_않는다_조문에_무지_검정은_good() {
        Garment plain = new Garment("g1", "top", "셔츠", "검정", "black", "dark", "plain", null, null, "smart", List.of(),
                null, null, 0.9);
        Garment check = new Garment("g1", "top", "셔츠", "검정", "black", "dark", "check", null, null, "smart", List.of(),
                null, null, 0.9);
        var ok = judge.check(new OutfitJudge.CheckInput(null, events("mourning"), null, List.of(plain), List.of()));
        var bad = judge.check(new OutfitJudge.CheckInput(null, events("mourning"), null, List.of(check), List.of()));
        assertThat(ok.verdicts().get(0).fit()).isEqualTo("good");
        assertThat(bad.verdicts().get(0).fit()).isEqualTo("bad");
    }
}
