package com.fashionagent.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 기본 설정(가짜 도구 + 규칙 판단 + 메모리 세션)으로 옷 추천 2단계를 처음부터 끝까지 돌려 봅니다. */
@SpringBootTest
@AutoConfigureMockMvc
class TurnFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private JsonNode turn(String userId, String body) throws Exception {
        String out = mvc.perform(post("/api/v1/agent/turn").header("X-User-Id", userId)
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    @Test
    void 말로_추천_카메라_확인_어디야_이걸로할게() throws Exception {
        // 1단계: 말로 추천
        JsonNode advice = turn("u1", """
                {"utterance": "오늘 뭐 입지?", "location": {"lat": 37.5, "lon": 127.0}}""");
        assertThat(advice.path("next").asString()).isEqualTo("camera_outfit");
        assertThat(advice.path("result").path("type").asString()).isEqualTo("outfit_advice");
        assertThat(advice.path("speech").asString()).contains("면접").contains("겉옷").contains("카메라");
        String sid = advice.path("sessionId").asString();

        // 2단계: 두 벌 나란히 → 비교 + 얼룩은 할 일 중심, 위치는 말하지 않음
        JsonNode check = turn("u1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "outfit", "autoCaptured": true}""".formatted(sid));
        assertThat(check.path("next").asString()).isEqualTo("camera_outfit");
        JsonNode oc = check.path("result").path("outfitCheck");
        assertThat(oc.path("comparison").path("betterInputId").asString()).isEqualTo("p1_g1");
        assertThat(oc.path("damage").get(0).path("action").asString()).isEqualTo("wash");
        assertThat(check.path("speech").asString())
                .startsWith("왼쪽 흰색 셔츠가 오른쪽 빨간색 체크 셔츠보다 면접에 더 잘 맞아요.")
                .contains("빨래")
                .doesNotContain("소매");

        // "어디야?" → 그때만 위치
        JsonNode where = turn("u1", """
                {"sessionId": "%s", "utterance": "얼룩 어디야?"}""".formatted(sid));
        assertThat(where.path("speech").asString()).contains("오른쪽 소매 끝");

        // "이걸로 할게" → 기록하고 끝
        JsonNode done = turn("u1", """
                {"sessionId": "%s", "utterance": "이걸로 할게"}""".formatted(sid));
        assertThat(done.path("next").asString()).isEqualTo("done");
        assertThat(done.path("speech").asString()).contains("흰색 셔츠").contains("기록");
    }

    @Test
    void 어두운_사진은_다시_보여달라고_한다() throws Exception {
        JsonNode r = turn("u2", """
                {"image": "DARK...", "imageKind": "outfit"}""");
        assertThat(r.path("result").path("outfitCheck").path("retake").asString()).contains("어두워요");
        assertThat(r.path("next").asString()).isEqualTo("camera_outfit");
    }

    @Test
    void 다른_사람의_세션은_이어지지_않는다() throws Exception {
        String sid = turn("u3", """
                {"utterance": "옷 추천해 줘"}""").path("sessionId").asString();
        JsonNode other = turn("someone-else", """
                {"sessionId": "%s", "utterance": "이걸로 할게"}""".formatted(sid));
        assertThat(other.path("sessionId").asString()).isNotEqualTo(sid);
        assertThat(other.path("next").asString()).isNotEqualTo("done");
    }

    @Test
    void X_User_Id가_없으면_401() throws Exception {
        mvc.perform(post("/api/v1/agent/turn").contentType(MediaType.APPLICATION_JSON)
                .content("{\"utterance\":\"오늘 뭐 입지?\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void 말도_사진도_없으면_400() throws Exception {
        mvc.perform(post("/api/v1/agent/turn").header("X-User-Id", "u4")
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("bad_request"));
    }

    @Test
    void 날씨를_물으면_읽어준다() throws Exception {
        JsonNode r = turn("u5", """
                {"utterance": "오늘 날씨 어때?"}""");
        assertThat(r.path("speech").asString()).contains("체감").contains("자켓");
        assertThat(r.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void 얼굴_확인_카메라_켜고_사진으로_안내() throws Exception {
        JsonNode start = turn("u6", """
                {"utterance": "얼굴 확인해 줘"}""");
        assertThat(start.path("next").asString()).isEqualTo("camera_face");
        String sid = start.path("sessionId").asString();

        // 구도가 안 맞으면 그 안내만 하고 카메라 유지
        JsonNode cut = turn("u6", """
                {"sessionId": "%s", "image": "CUT...", "imageKind": "face", "autoCaptured": true}""".formatted(sid));
        assertThat(cut.path("speech").asString()).contains("위쪽에 잘렸어요");
        assertThat(cut.path("next").asString()).isEqualTo("camera_face");

        JsonNode ok = turn("u6", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "face", "autoCaptured": true}""".formatted(sid));
        assertThat(ok.path("result").path("type").asString()).isEqualTo("face_check");
        assertThat(ok.path("speech").asString()).contains("왼쪽 눈썹").contains("앞니");
        assertThat(ok.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void GPT가_꺼져_있으면_개별_기능은_안내만() throws Exception {
        JsonNode r = turn("u7", """
                {"utterance": "토요일 두 시에 결혼식 넣어 줘"}""");
        assertThat(r.path("speech").asString()).contains("오늘 뭐 입지");
        assertThat(r.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
