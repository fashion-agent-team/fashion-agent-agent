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
                {"utterance": "내려놓고 볼게, 오늘 뭐 입지?", "location": {"lat": 37.5, "lon": 127.0}}""");
        assertThat(advice.path("next").asString()).isEqualTo("listen");
        assertThat(advice.path("result").path("type").asString()).isEqualTo("outfit_advice");
        assertThat(advice.path("speech").asString()).contains("면접").contains("겉옷").endsWith("봐 드릴까요?");
        String sid = advice.path("sessionId").asString();

        // "봐 드릴까요?" → 네 → 말에 있던 방식(내려놓기)으로 바로 카메라
        JsonNode camera = turn("u1", """
                {"sessionId": "%s", "utterance": "네 봐 줘"}""".formatted(sid));
        assertThat(camera.path("next").asString()).isEqualTo("camera_outfit");
        assertThat(camera.path("speech").asString()).contains("펼쳐 놓고");

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

        // "이걸로 할게" → 기록하고, 입은 모습도 볼지 묻기
        JsonNode picked = turn("u1", """
                {"sessionId": "%s", "utterance": "이걸로 할게"}""".formatted(sid));
        assertThat(picked.path("next").asString()).isEqualTo("listen");
        assertThat(picked.path("speech").asString()).contains("흰색 셔츠").contains("기록").contains("입은 모습");

        JsonNode done = turn("u1", """
                {"sessionId": "%s", "utterance": "아니요 괜찮아요"}""".formatted(sid));
        assertThat(done.path("next").asString()).isEqualTo("done");
        assertThat(done.path("speech").asString()).contains("잘 다녀오세요");
    }

    @Test
    void 내려놓고_고른_뒤_입은_모습도_보면_입고_한_바퀴로() throws Exception {
        String sid = turn("u9", """
                {"utterance": "옷 내려놓고 볼게 뭐 입지"}""").path("sessionId").asString();
        turn("u9", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "outfit"}""".formatted(sid));
        turn("u9", """
                {"sessionId": "%s", "utterance": "이걸로 할게"}""".formatted(sid));

        JsonNode worn = turn("u9", """
                {"sessionId": "%s", "utterance": "네 볼게요"}""".formatted(sid));
        assertThat(worn.path("next").asString()).isEqualTo("camera_worn");
        assertThat(worn.path("speech").asString()).contains("머리부터 발끝까지");

        for (int i = 0; i < 3; i++) {
            turn("u9", """
                    {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        }
        JsonNode done = turn("u9", """
                {"sessionId": "%s", "utterance": "이걸로 할게"}""".formatted(sid));
        assertThat(done.path("next").asString()).isEqualTo("done");
        assertThat(done.path("speech").asString()).doesNotContain("기록");
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
                {"utterance": "옷 추천해 줘", "outfitMode": "laid"}""").path("sessionId").asString();
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
    void 얼굴_확인은_얼굴과_이_중_먼저_묻고_화장을_본다() throws Exception {
        JsonNode ask = turn("u6", """
                {"utterance": "얼굴 확인해 줘"}""");
        assertThat(ask.path("speech").asString()).contains("화장을 볼까요").contains("이를 확인할까요");
        assertThat(ask.path("next").asString()).isEqualTo("listen");
        String sid = ask.path("sessionId").asString();

        JsonNode start = turn("u6", """
                {"sessionId": "%s", "utterance": "얼굴"}""".formatted(sid));
        assertThat(start.path("next").asString()).isEqualTo("camera_face");

        // 구도가 안 맞으면 그 안내만 하고 카메라 유지
        JsonNode cut = turn("u6", """
                {"sessionId": "%s", "image": "CUT...", "imageKind": "face", "autoCaptured": true}""".formatted(sid));
        assertThat(cut.path("speech").asString()).contains("위쪽에 잘렸어요");
        assertThat(cut.path("next").asString()).isEqualTo("camera_face");

        JsonNode ok = turn("u6", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "face", "autoCaptured": true}""".formatted(sid));
        assertThat(ok.path("result").path("type").asString()).isEqualTo("face_makeup_check");
        assertThat(ok.path("speech").asString()).contains("왼쪽 눈썹").doesNotContain("앞니");
        assertThat(ok.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void 이_라고_답하면_치아를_본다() throws Exception {
        JsonNode ask = turn("u6t", """
                {"utterance": "얼굴 확인해 줘"}""");
        String sid = ask.path("sessionId").asString();

        JsonNode start = turn("u6t", """
                {"sessionId": "%s", "utterance": "이요"}""".formatted(sid));
        assertThat(start.path("next").asString()).isEqualTo("camera_teeth");
        assertThat(start.path("speech").asString()).contains("입 가까이");

        JsonNode cut = turn("u6t", """
                {"sessionId": "%s", "image": "CUT...", "imageKind": "teeth", "mouthBox": {"x": 0.3, "y": 0.5, "w": 0.4, "h": 0.2}}""".formatted(sid));
        assertThat(cut.path("speech").asString()).contains("입을 조금 더 벌려");
        assertThat(cut.path("next").asString()).isEqualTo("camera_teeth");

        JsonNode ok = turn("u6t", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "teeth"}""".formatted(sid));
        assertThat(ok.path("result").path("type").asString()).isEqualTo("teeth_check");
        assertThat(ok.path("speech").asString()).contains("앞니").doesNotContain("눈썹");

        // 모델이 애매하면(foreignObject: null) 단정하지 않고 가족에게 물어볼지 제안
        JsonNode unsure = turn("u6t", """
                {"sessionId": "%s", "image": "UNSURE...", "imageKind": "teeth"}""".formatted(sid));
        assertThat(unsure.path("speech").asString()).contains("확실하지 않아요", "물어볼까요")
                .doesNotContain("없어 보여요");
        assertThat(unsure.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void 처음부터_이_확인을_말하면_묻지_않는다() throws Exception {
        JsonNode r = turn("u6d", """
                {"utterance": "이빨에 뭐 꼈는지 봐 줘"}""");
        assertThat(r.path("next").asString()).isEqualTo("camera_teeth");

        JsonNode m = turn("u6e", """
                {"utterance": "화장 잘 됐는지 봐 줘"}""");
        assertThat(m.path("next").asString()).isEqualTo("camera_face");
    }

    @Test
    void 얼굴_이_대신_다른_말을_하면_원래_대화로() throws Exception {
        JsonNode ask = turn("u6f", """
                {"utterance": "얼굴 확인해 줘"}""");
        String sid = ask.path("sessionId").asString();
        JsonNode w = turn("u6f", """
                {"sessionId": "%s", "utterance": "아니 오늘 날씨 어때?"}""".formatted(sid));
        assertThat(w.path("speech").asString()).contains("체감");
    }

    @Test
    void GPT가_꺼져_있으면_개별_기능은_안내만() throws Exception {
        JsonNode r = turn("u7", """
                {"utterance": "토요일 두 시에 결혼식 넣어 줘"}""");
        assertThat(r.path("speech").asString()).contains("할 수 없는", "옷 추천");
        assertThat(r.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void 할_줄_아는_거를_물으면_기능을_알려준다() throws Exception {
        JsonNode r = turn("h1", """
                {"utterance": "너 할 줄 아는 거 말해 봐"}""");
        assertThat(r.path("speech").asString()).contains("옷을 추천", "날씨", "일정");
        assertThat(r.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void GPT가_꺼져_있어도_인사와_고맙다는_말에는_답한다() throws Exception {
        assertThat(turn("h2", """
                {"utterance": "안녕"}""").path("speech").asString()).contains("안녕하세요");
        assertThat(turn("h2", """
                {"utterance": "고마워"}""").path("speech").asString()).contains("천만에요");
        assertThat(turn("h2", """
                {"utterance": "바보야"}""").path("speech").asString()).contains("바보 아니에요");
        assertThat(turn("h2", """
                {"utterance": "지금 몇 시야?"}""").path("speech").asString()).contains("지금은", "시");
    }

    @Test
    void 방식을_모르면_먼저_묻고_입고_한_바퀴를_앞_옆_뒤로_본다() throws Exception {
        JsonNode offer = turn("w1", """
                {"utterance": "오늘 뭐 입지?"}""");
        assertThat(offer.path("next").asString()).isEqualTo("listen");
        assertThat(offer.path("speech").asString()).contains("봐 드릴까요");
        String sid = offer.path("sessionId").asString();

        JsonNode ask = turn("w1", """
                {"sessionId": "%s", "utterance": "응"}""".formatted(sid));
        assertThat(ask.path("next").asString()).isEqualTo("listen");
        assertThat(ask.path("speech").asString()).contains("내려놓고").contains("한 바퀴");

        JsonNode advice = turn("w1", """
                {"sessionId": "%s", "utterance": "입고 돌아볼게"}""".formatted(sid));
        assertThat(advice.path("next").asString()).isEqualTo("camera_worn");
        assertThat(advice.path("result").path("outfitMode").asString()).isEqualTo("worn");
        assertThat(advice.path("speech").asString()).startsWith("좋아요.")
                .contains("머리부터 발끝까지").doesNotContain("옷을 꺼내서 카메라에");

        JsonNode front = turn("w1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        assertThat(front.path("speech").asString()).contains("옆으로");
        assertThat(front.path("next").asString()).isEqualTo("camera_worn");

        JsonNode side = turn("w1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        assertThat(side.path("speech").asString()).contains("뒤로");

        JsonNode back = turn("w1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        assertThat(back.path("next").asString()).isEqualTo("camera_worn");
        JsonNode oc = back.path("result").path("outfitCheck");
        assertThat(oc.path("garments")).hasSize(2);
        assertThat(oc.path("damage").get(0).path("action").asString()).isEqualTo("wash");
        assertThat(back.path("speech").asString()).contains("빨래").endsWith("앞모습부터 보여 주세요.");

        JsonNode where = turn("w1", """
                {"sessionId": "%s", "utterance": "얼룩 어디야?"}""".formatted(sid));
        assertThat(where.path("speech").asString()).contains("등 가운데").contains("몸 기준");
        assertThat(where.path("next").asString()).isEqualTo("camera_worn");
    }

    @Test
    void 앱이_기억한_방식이_있어도_매번_묻고_말에_방식이_있으면_바로() throws Exception {
        String sid = turn("w2", """
                {"utterance": "옷 추천해 줘", "outfitMode": "worn"}""").path("sessionId").asString();
        JsonNode r = turn("w2", """
                {"sessionId": "%s", "utterance": "네", "outfitMode": "worn"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("listen");
        assertThat(r.path("speech").asString()).contains("어떻게 보여");

        String sid2 = turn("w2b", """
                {"utterance": "입고 볼게 오늘 뭐 입지"}""").path("sessionId").asString();
        JsonNode direct = turn("w2b", """
                {"sessionId": "%s", "utterance": "응"}""".formatted(sid2));
        assertThat(direct.path("next").asString()).isEqualTo("camera_worn");
    }

    @Test
    void 추천_중에_말로_방식을_바꾼다() throws Exception {
        String sid = turn("w3", """
                {"utterance": "옷 추천해 줘", "outfitMode": "laid"}""").path("sessionId").asString();
        JsonNode r = turn("w3", """
                {"sessionId": "%s", "utterance": "그냥 입고 보여줄게", "outfitMode": "laid"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("camera_worn");
        assertThat(r.path("result").path("outfitMode").asString()).isEqualTo("worn");
    }

    @Test
    void 몸이_다_안_보이면_같은_방향을_다시_보여_달라고_한다() throws Exception {
        String sid = turn("w4", """
                {"utterance": "옷 추천해 줘", "outfitMode": "worn"}""").path("sessionId").asString();
        JsonNode r = turn("w4", """
                {"sessionId": "%s", "image": "FAR", "imageKind": "worn"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("물러나");
        JsonNode again = turn("w4", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        assertThat(again.path("speech").asString()).contains("옆으로");
    }

    @Test
    void 봐_드릴까요에_아니요면_끝낸다() throws Exception {
        String sid = turn("w6", """
                {"utterance": "오늘 뭐 입지?"}""").path("sessionId").asString();
        JsonNode r = turn("w6", """
                {"sessionId": "%s", "utterance": "아니 괜찮아"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("done");
    }

    @Test
    void 봐_드릴까요에_다른_기능을_말하면_원래_대화로() throws Exception {
        String sid = turn("w5", """
                {"utterance": "오늘 뭐 입지?"}""").path("sessionId").asString();
        JsonNode r = turn("w5", """
                {"sessionId": "%s", "utterance": "아니 그냥 오늘 날씨 어때?"}""".formatted(sid));
        assertThat(r.path("result").path("type").asString()).isEqualTo("weather");
    }

    @Test
    void health() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
