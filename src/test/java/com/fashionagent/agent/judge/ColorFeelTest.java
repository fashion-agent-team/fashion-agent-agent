package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.Garment;

class ColorFeelTest {

    private static Garment g(String id, String colorName, String family) {
        return new Garment(id, "top", "셔츠", colorName, family, null, "plain", null, null, "smart", List.of(), null,
                null, 0.9);
    }

    @Test
    void 자세히는_비유_온도_느낌을_한_문장에() {
        assertThat(ColorFeel.describe(g("a", "베이지", "beige"), true))
                .isEqualTo("베이지는 햇볕 받은 모래처럼 따뜻하고 밝은 색이라, 부드럽고 편안한 느낌이에요.");
    }

    @Test
    void 이름만이어도_빨강_초록은_비유를_붙임() {
        assertThat(ColorFeel.describe(g("a", "빨간색", "red"), false)).isEqualTo("빨간색은 잘 익은 딸기 같은 색이에요.");
        assertThat(ColorFeel.describe(g("a", "초록색", "green"), false)).isEqualTo("초록색은 싱그러운 풀 같은 색이에요.");
        assertThat(ColorFeel.describe(g("a", "남색", "navy"), false)).isEmpty();
    }

    @Test
    void 중요한_일정이면_어울리는_색이라고_말함() {
        assertThat(ColorFeel.forEvent(g("a", "남색", "navy"), Occasion.formal, "면접"))
                .isEqualTo("남색은 면접에 잘 어울리는 색이에요. 신뢰감을 줘요.");
        assertThat(ColorFeel.forEvent(g("a", "흰색", "white"), Occasion.wedding, "결혼식")).isEmpty();
        assertThat(ColorFeel.forEvent(g("a", "남색", "navy"), Occasion.casual, null)).isEmpty();
    }

    @Test
    void 같은_색은_한_대화에서_한_번만() {
        List<String> told = new ArrayList<>();
        List<String> toldEvent = new ArrayList<>();
        var first = ColorFeel.sentences(List.of(g("a", "남색", "navy")), true, Occasion.formal, "면접", told, toldEvent);
        assertThat(first).hasSize(2);
        var again = ColorFeel.sentences(List.of(g("b", "남색", "navy")), true, Occasion.formal, "면접", told, toldEvent);
        assertThat(again).isEmpty();
    }

    @Test
    void 첫_문장_뒤에_넣고_끝_안내는_뒤에_남김() {
        String speech = "남색 셔츠는 단정해 보여서 잘 맞아요. 마음에 들면 \"이걸로 할게\"라고 말해 주세요.";
        assertThat(ColorFeel.insertAfterFirst(speech, List.of("남색은 깊은 바닷물처럼 시원해요.")))
                .isEqualTo("남색 셔츠는 단정해 보여서 잘 맞아요. 남색은 깊은 바닷물처럼 시원해요. 마음에 들면 \"이걸로 할게\"라고 말해 주세요.");
    }

    @Test
    void 색_설명_명령() {
        assertThat(ColorFeel.detailCommand("색 설명 짧게 해 줘")).isFalse();
        assertThat(ColorFeel.detailCommand("색은 이름만 말해 줘")).isFalse();
        assertThat(ColorFeel.detailCommand("색 설명 자세히 해 줘")).isTrue();
        assertThat(ColorFeel.detailCommand("색 느낌도 말해 줘")).isTrue();
        assertThat(ColorFeel.detailCommand("이 옷 무슨 색이야")).isNull();
        assertThat(ColorFeel.detailCommand("오늘 날씨 어때")).isNull();
    }
}
