package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.session.AgentSession;

/** "이걸로 할게"에서 고르는 옷 (2026-11-12 시뮬 4회차). */
class OutfitChosenTest {

    private static AgentSession.Seen seen(String id, String category, String name, String fit) {
        return new AgentSession.Seen(new Garment(id, category, name, "검정", "black", "dark", null, null, null,
                "casual", List.of(), null, null, 0.9), fit);
    }

    private static List<String> names(List<Garment> gs) {
        return gs.stream().map(Garment::name).toList();
    }

    @Test
    void 원피스와_상의_하의를_함께_고르지_않는다_나중에_본_쪽() {
        var seen = List.of(seen("p1_g1", "dress", "원피스", "ok"), seen("p2_g1", "bottom", "치마", "ok"),
                seen("p3_g1", "top", "티셔츠", "ok"));
        assertThat(names(OutfitFlow.chosen(seen))).containsExactlyInAnyOrder("치마", "티셔츠");
    }

    @Test
    void 원피스가_더_잘_맞으면_원피스만() {
        var seen = List.of(seen("p1_g1", "top", "티셔츠", "ok"), seen("p2_g1", "dress", "원피스", "good"));
        assertThat(names(OutfitFlow.chosen(seen))).containsExactly("원피스");
        assertThat(names(OutfitFlow.anyChosen(seen))).containsExactly("원피스");
    }

    @Test
    void 원피스_신발은_같이() {
        var seen = List.of(seen("p1_g1", "dress", "원피스", "good"), seen("p1_g2", "shoes", "구두", "ok"));
        assertThat(names(OutfitFlow.chosen(seen))).containsExactly("원피스", "구두");
    }

    @Test
    void 입고_한_바퀴는_마지막_바퀴_옷만() {
        var session = new AgentSession("s", "u");
        session.outfitMode = "worn";
        session.seen.add(seen("p1_g1", "outer", "자켓", "good"));
        session.seen.add(seen("p1_g2", "bottom", "슬랙스", "good"));
        session.seen.add(seen("p3_g1", "top", "니트", "ok"));
        session.seen.add(seen("p3_g2", "bottom", "청바지", "ok"));
        session.lastWornIds = List.of("p3_g1", "p3_g2");
        assertThat(names(OutfitFlow.chosen(OutfitFlow.finishPool(session)))).containsExactly("니트", "청바지");

        session.outfitMode = "laid";
        assertThat(OutfitFlow.finishPool(session)).hasSize(4);
    }

    @Test
    void 종류_모르는_옷은_다른_옷이_있으면_빼고_기록() {
        var unknown = seen("p1_g1", "other", "옷", "ok").garment();
        var dress = seen("p1_g2", "dress", "원피스", "ok").garment();
        assertThat(names(OutfitFlow.withoutUnknown(List.of(unknown, dress)))).containsExactly("원피스");
        assertThat(names(OutfitFlow.withoutUnknown(List.of(unknown)))).containsExactly("옷");
    }

    @Test
    void 날씨에_안_맞는_티셔츠도_보여_준_옷이면_함께_고른다() {
        // 2026-11-13 실서버 시뮬: 티셔츠(얇음)와 바지를 보여 주고 "이걸로 할게" → 바지만 기록됐음
        var seen = List.of(seen("p1_g1", "top", "티셔츠", "bad"), seen("p2_g1", "bottom", "바지", "good"));
        var pick = OutfitFlow.pick(seen);
        assertThat(names(pick.garments())).containsExactly("바지", "티셔츠");
        assertThat(pick.overruled()).isTrue();
    }

    @Test
    void 모두_잘_맞으면_그대로() {
        var seen = List.of(seen("p1_g1", "top", "니트", "good"), seen("p2_g1", "bottom", "바지", "ok"));
        var pick = OutfitFlow.pick(seen);
        assertThat(names(pick.garments())).containsExactly("니트", "바지");
        assertThat(pick.overruled()).isFalse();
    }

    @Test
    void 안_맞는_원피스는_상의_하의와_함께_넣지_않는다() {
        var seen = List.of(seen("p1_g1", "dress", "원피스", "bad"), seen("p2_g1", "top", "니트", "good"),
                seen("p3_g1", "bottom", "바지", "good"));
        assertThat(names(OutfitFlow.pick(seen).garments())).containsExactly("니트", "바지");
    }
}
