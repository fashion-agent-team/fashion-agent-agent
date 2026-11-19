package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;

import tools.jackson.databind.json.JsonMapper;

/** 앱이 보낸 faceFrames [{image, faceLandmarks}]를 face-check의 frames [{imageBase64, landmarks}]로 넘김 */
class FaceFlowFramesTest {

    @Test
    void 여러_장을_frames로_넘기고_5장을_넘지_않는다() {
        List<Map<String, Object>> seen = new ArrayList<>();
        AgentTools tools = (tool, args) -> {
            seen.add(args);
            return JsonMapper.builder().build().readTree("{\"lighting\":\"ok\",\"symmetry\":{\"ok\":true,\"notes\":[]}}");
        };
        SessionStore store = new SessionStore() {
            @Override
            public Optional<AgentSession> find(String userId, String sessionId) {
                return Optional.empty();
            }

            @Override
            public void save(AgentSession session) {
            }
        };
        var flow = new FaceFlow(tools, store);
        List<Map<String, Object>> frames = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            frames.add(Map.of("image", "IMG" + i, "faceLandmarks", Map.of("leftEye", List.of())));
        }
        flow.checkMakeup(new AgentSession("s_1", "u"), "FIRST", Map.of("leftEye", List.of()), frames);

        Map<String, Object> args = seen.getFirst();
        assertThat(args.get("imageBase64")).isEqualTo("FIRST");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sent = (List<Map<String, Object>>) args.get("frames");
        assertThat(sent).hasSize(4);
        assertThat(sent.getFirst()).containsEntry("imageBase64", "IMG0").containsKey("landmarks");
    }
}
