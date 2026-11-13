package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.OutfitCheck.Verdict;

class PraiseGuardTest {

    private static List<Verdict> fits(String... fits) {
        return java.util.Arrays.stream(fits).map(f -> new Verdict("g1", null, f, List.of())).toList();
    }

    @Test
    void 안_맞는_옷이면_잘_고르셨어요를_뺀다() {
        // 2026-11-13: 면접에 호피 무늬 자켓
        assertThat(PraiseGuard.strip("잘 고르셨어요. 면접에는 갈색·밝은 베이지 호피 무늬 자켓이 너무 캐주얼해서 아쉬워요.", fits("bad")))
                .isEqualTo("면접에는 갈색·밝은 베이지 호피 무늬 자켓이 너무 캐주얼해서 아쉬워요.");
        assertThat(PraiseGuard.strip("잘 고르셨어요! 다만 면접에는 조금 캐주얼해요.", fits("ok")))
                .isEqualTo("면접에는 조금 캐주얼해요.");
        assertThat(PraiseGuard.strip("호피 무늬 자켓, 잘 고르셨네요. 그런데 면접에는 캐주얼해요.", fits("good", "bad")))
                .isEqualTo("면접에는 캐주얼해요.");
    }

    @Test
    void 맞는_옷이면_그대로_둔다() {
        String s = "잘 고르셨어요. 면접에 단정한 네이비 셔츠가 잘 어울려요.";
        assertThat(PraiseGuard.strip(s, fits("good"))).isEqualTo(s);
        assertThat(PraiseGuard.strip(s, fits("good", "good"))).isEqualTo(s);
    }

    @Test
    void 칭찬으로_시작하지_않거나_칭찬뿐이면_그대로_둔다() {
        String s = "면접에는 호피 무늬 자켓이 조금 캐주얼해요. 잘 고르셨어요는 아니에요.";
        assertThat(PraiseGuard.strip(s, fits("bad"))).isEqualTo(s);
        assertThat(PraiseGuard.strip("잘 고르셨어요.", fits("bad"))).isEqualTo("잘 고르셨어요.");
        assertThat(PraiseGuard.strip("잘 고르셨어요. 괜찮아요.", List.of())).isEqualTo("잘 고르셨어요. 괜찮아요.");
        assertThat(PraiseGuard.strip(null, fits("bad"))).isNull();
    }
}
