package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.session.AgentSession;

/** GPT가 일정을 넣거나 지운 뒤 카메라를 이어 갈지: 지난 턴에 옷 카메라가 켜져 있었을 때만. */
class OutfitCameraTest {

    @Test
    void 추천만_듣고_카메라를_안_켰으면_일정을_지워도_카메라를_켜지_않는다_2026_11_12() {
        var session = new AgentSession("s", "u");
        session.lastNext = Next.listen; // 말로 추천을 들은 뒤
        assertThat(TurnService.outfitCamera(session)).isNull();
        session.lastNext = null; // 새 대화
        assertThat(TurnService.outfitCamera(session)).isNull();
        session.lastNext = Next.camera_face;
        assertThat(TurnService.outfitCamera(session)).isNull();
    }

    @Test
    void 카메라로_옷을_보던_중이면_그_카메라를_이어_간다() {
        var session = new AgentSession("s", "u");
        session.lastNext = Next.camera_outfit;
        assertThat(TurnService.outfitCamera(session)).isEqualTo(Next.camera_outfit);
        session.lastNext = Next.camera_worn;
        assertThat(TurnService.outfitCamera(session)).isEqualTo(Next.camera_worn);
    }
}
