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
        assertThat(advice.path("speech").asString()).contains("면접").contains("겉옷").doesNotContain("봐 드릴까요");
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
    void 무슨_색이냐고_물으면_카메라를_켜고_색만_읽어준다() throws Exception {
        JsonNode start = turn("u_color", """
                {"utterance": "이거 무슨 색이야?"}""");
        assertThat(start.path("next").asString()).isEqualTo("camera_outfit");
        assertThat(start.path("speech").asString()).contains("색 봐 드릴게요");
        String sid = start.path("sessionId").asString();

        // 두 벌을 비추면 위치와 색·무늬·종류만. 추천·비교 없이.
        JsonNode seen = turn("u_color", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "outfit", "autoCaptured": true}""".formatted(sid));
        assertThat(seen.path("next").asString()).isEqualTo("camera_outfit");
        assertThat(seen.path("speech").asString())
                .startsWith("왼쪽은 흰색 셔츠, 오른쪽은 빨간색 체크 셔츠예요.")
                .contains("얼룩")
                .doesNotContain("면접")
                .doesNotContain("기록");

        JsonNode done = turn("u_color", """
                {"sessionId": "%s", "utterance": "됐어 고마워"}""".formatted(sid));
        assertThat(done.path("next").asString()).isEqualTo("done");
    }

    @Test
    void 무슨_색_입을지는_색_확인이_아니라_추천() throws Exception {
        JsonNode advice = turn("u_color2", """
                {"utterance": "오늘 무슨 색 입을까?"}""");
        assertThat(advice.path("result").path("type").asString()).isEqualTo("outfit_advice");
    }

    @Test
    void 날짜를_말하면_그날_일정과_예보로_추천한다() throws Exception {
        var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(5);
        String said = day.getMonthValue() + "월 " + day.getDayOfMonth() + "일";
        JsonNode advice = turn("u20", """
                {"utterance": "%s 일정에 어울릴만한 패션 추천해줘", "location": {"lat": 37.5, "lon": 127.0}}"""
                .formatted(said));
        assertThat(advice.path("result").path("type").asString()).isEqualTo("outfit_advice");
        assertThat(advice.path("speech").asString())
                .startsWith(said)
                .contains("결혼식")
                .contains("우산")
                .doesNotContain("면접")
                .doesNotContain("봐 드릴까요");

        // 일정 읽기도 그날로
        JsonNode schedule = turn("u20", """
                {"utterance": "%s 일정 뭐야"}""".formatted(said));
        assertThat(schedule.path("speech").asString()).startsWith(said).contains("오후 2시 친구 결혼식");
    }

    @Test
    void 내일_옷_추천은_날짜_날씨_일정_추천_순서로_말하고_봐_줄지_묻는다() throws Exception {
        // 2026-11-09 발표 피드백: "내일 입을 거 추천해 줘"에 오늘 옷을 추천했고, 문장도 이상했음.
        JsonNode advice = turn("u22", """
                {"utterance": "내일 입을 옷 추천해 줘", "location": {"lat": 37.5, "lon": 127.0}}""");
        String speech = advice.path("speech").asString();
        assertThat(speech).startsWith("내일의 패션을 추천해 드릴게요. 내일 날씨는 ")
                .contains("일정은 ")
                .contains("추천드려요.")
                .doesNotContain("봐 드릴까요");
        assertThat(speech.indexOf("날씨는")).isLessThan(speech.indexOf("일정은"));
        assertThat(speech.indexOf("일정은")).isLessThan(speech.indexOf("추천드려요"));

        String sid = advice.path("sessionId").asString();
        JsonNode ask = turn("u22", """
                {"sessionId": "%s", "utterance": "어 봐줘"}""".formatted(sid));
        assertThat(ask.path("speech").asString()).isEqualTo("옷을 내려놓고 찍으실래요, 입어 보고 한 바퀴 도는 모습을 찍으실래요?");
        JsonNode camera = turn("u22", """
                {"sessionId": "%s", "utterance": "입어 보고 돌아볼게"}""".formatted(sid));
        assertThat(camera.path("next").asString()).isEqualTo("camera_worn");
    }

    @Test
    void 예보가_없는_먼_날은_일정만으로_추천한다() throws Exception {
        var day = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(40);
        String said = day.getMonthValue() + "월 " + day.getDayOfMonth() + "일";
        JsonNode advice = turn("u21", """
                {"utterance": "%s 일정에 맞는 옷 추천해 줘"}""".formatted(said));
        assertThat(advice.path("speech").asString())
                .startsWith(said)
                .contains("결혼식")
                .contains("예보가 없어요")
                .doesNotContain("불러오지 못했어요");
    }

    @Test
    void 다_보여_줬어면_이걸로_할까요_묻고_네면_기록한다() throws Exception {
        // 2026-11-13 실서버 시뮬: "다 보여 줬어"가 GPT로 가서 처음 추천을 다시 하고 본 옷을 잊었음
        String sid = turn("u31", """
                {"utterance": "옷 내려놓고 볼게 뭐 입지"}""").path("sessionId").asString();
        turn("u31", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "outfit"}""".formatted(sid));
        JsonNode shown = turn("u31", """
                {"sessionId": "%s", "utterance": "다 보여 줬어"}""".formatted(sid));
        assertThat(shown.path("next").asString()).isEqualTo("listen");
        assertThat(shown.path("speech").asString()).contains("셔츠").contains("할까요").doesNotContain("추천해 드릴게요");
        JsonNode yes = turn("u31", """
                {"sessionId": "%s", "utterance": "네"}""".formatted(sid));
        assertThat(yes.path("speech").asString()).contains("셔츠").doesNotContain("못 봤어요");
        assertThat(yes.path("result").path("type").asString()).isEqualTo("outfit_worn_offer");
    }

    @Test
    void 이걸로_할까요에_아니요면_추천하고_다른_옷을_기다린다() throws Exception {
        String sid = turn("u32", """
                {"utterance": "옷 내려놓고 볼게 뭐 입지"}""").path("sessionId").asString();
        turn("u32", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "outfit"}""".formatted(sid));
        turn("u32", """
                {"sessionId": "%s", "utterance": "이게 다야"}""".formatted(sid));
        JsonNode no = turn("u32", """
                {"sessionId": "%s", "utterance": "아니요"}""".formatted(sid));
        assertThat(no.path("next").asString()).isEqualTo("camera_outfit");
        assertThat(no.path("speech").asString()).startsWith("그럼 ").contains("다시 봐 드릴게요");
        assertThat(no.path("result").path("type").asString()).isEqualTo("outfit_recommend");
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
        assertThat(worn.path("speech").asString()).contains("두세 걸음");

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
    void 내일_날씨는_그날_예보로_답한다() throws Exception {
        JsonNode r = turn("u5b", """
                {"utterance": "내일 날씨 어때?"}""");
        // 가짜 예보: 비, 최저 3도 최고 13도, 비 70%
        assertThat(r.path("speech").asString()).startsWith("내일은 비, 최저 3도, 최고 13도예요.")
                .contains("우산을 챙기세요").doesNotContain("체감");
        assertThat(r.path("result").path("type").asString()).isEqualTo("weather");
    }

    @Test
    void 우산을_물으면_우산부터_답한다() throws Exception {
        JsonNode today = turn("u5c", """
                {"utterance": "우산 챙겨야 해?"}""");
        assertThat(today.path("speech").asString()).startsWith("우산은 안 챙기셔도 돼요. 비 올 확률이 10%예요.");
        assertThat(today.path("result").path("type").asString()).isEqualTo("weather");

        JsonNode tomorrow = turn("u5c", """
                {"utterance": "내일 우산 필요해?"}""");
        assertThat(tomorrow.path("speech").asString()).startsWith("우산 챙기세요. 비나 눈 올 확률이 70%예요. 내일은 비");
    }

    @Test
    void 요일로_물으면_요일로_답한다() throws Exception {
        // 오늘이 무슨 요일이든 오늘·내일·모레가 아닌 요일 하나를 고름
        var today = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul"));
        var day = today.plusDays(3).getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN);
        JsonNode r = turn("u5e", """
                {"utterance": "%s 날씨 알려 줘"}""".formatted(day));
        assertThat(r.path("speech").asString()).startsWith(day + "은 비,");

        // 모레인 요일도 "모레"가 아니라 그 요일로
        var two = today.plusDays(2).getDayOfWeek().getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.KOREAN);
        JsonNode r2 = turn("u5e", """
                {"utterance": "%s 날씨 어때"}""".formatted(two));
        assertThat(r2.path("speech").asString()).startsWith(two + "은 비,");
    }

    @Test
    void 추운지_물으면_춥다_안_춥다부터_답한다() throws Exception {
        // 가짜 오늘 체감 9.5도
        JsonNode r = turn("u5f", """
                {"utterance": "오늘 추워?"}""");
        assertThat(r.path("speech").asString()).startsWith("조금 쌀쌀해요. 지금 맑음, 11도이고 체감 10도예요.");
        JsonNode hot = turn("u5f", """
                {"utterance": "오늘 더워?"}""");
        assertThat(hot.path("speech").asString()).startsWith("아니요, 덥지 않아요.");
    }

    @Test
    void 너무_먼_날_날씨는_모른다고_답한다() throws Exception {
        String far = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(40).toString();
        String[] p = far.split("-");
        JsonNode r = turn("u5d", """
                {"utterance": "%s월 %s일 날씨 어때?"}""".formatted(Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        assertThat(r.path("speech").asString()).contains("아직 알 수 없어요").contains("16일");
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

        // 윤곽점을 못 찾으면 카메라를 켠 채 다시 비추게
        JsonNode noFace = turn("u6", """
                {"sessionId": "%s", "image": "NOFACE", "imageKind": "face", "autoCaptured": true}""".formatted(sid));
        assertThat(noFace.path("speech").asString()).contains("얼굴이 잘 안 보여요");
        assertThat(noFace.path("next").asString()).isEqualTo("camera_face");

        // 멈춘 1초 동안의 여러 장(faceFrames)도 받음
        JsonNode ok = turn("u6", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "face", "autoCaptured": true,
                 "faceFrames": [{"image": "AAAB", "faceLandmarks": {}}, {"image": "AAAC"}]}""".formatted(sid));
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

        // 모델이 애매하면(foreignObject: null) 단정하지 않고 다시 찍어 보라고 권함
        JsonNode unsure = turn("u6t", """
                {"sessionId": "%s", "image": "UNSURE...", "imageKind": "teeth"}""".formatted(sid));
        assertThat(unsure.path("speech").asString()).contains("확실하지 않아요", "혀로 한 번 확인", "다시 찍으면")
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
    void 옷_검사해_달라면_추천_없이_방식부터_묻는다_리허설() throws Exception {
        JsonNode ask = turn("rc1", """
                {"utterance": "옷 검사해 줘"}""");
        assertThat(ask.path("next").asString()).isEqualTo("listen");
        assertThat(ask.path("speech").asString()).contains("내려놓고").doesNotContain("얼굴");
        String sid = ask.path("sessionId").asString();
        JsonNode cam = turn("rc1", """
                {"sessionId": "%s", "utterance": "내려놓고 볼게"}""".formatted(sid));
        assertThat(cam.path("next").asString()).isEqualTo("camera_outfit");
    }

    @Test
    void 얼굴_이를_묻는_중에_옷_검사를_말하면_옷_검사로() throws Exception {
        JsonNode ask = turn("rc2", """
                {"utterance": "얼굴 확인해 줘"}""");
        String sid = ask.path("sessionId").asString();
        JsonNode r = turn("rc2", """
                {"sessionId": "%s", "utterance": "옷 검사해 줘"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotContain("얼굴").contains("내려놓고");
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
                {"utterance": "고마워"}""").path("speech").asString()).contains("별말씀을요");
        assertThat(turn("h2", """
                {"utterance": "바보야"}""").path("speech").asString()).contains("서운해요");
        assertThat(turn("h2", """
                {"utterance": "지금 몇 시야?"}""").path("speech").asString()).contains("지금은", "시");
    }

    @Test
    void 방식을_모르면_먼저_묻고_입고_한_바퀴를_앞_옆_뒤로_본다() throws Exception {
        JsonNode offer = turn("w1", """
                {"utterance": "오늘 뭐 입지?"}""");
        assertThat(offer.path("next").asString()).isEqualTo("listen");
        assertThat(offer.path("speech").asString()).doesNotContain("봐 드릴까요");
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
                .contains("두세 걸음").doesNotContain("옷을 꺼내서 카메라에");

        JsonNode front = turn("w1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        assertThat(front.path("speech").asString()).contains("뒤로").doesNotContain("옆으로");
        assertThat(front.path("next").asString()).isEqualTo("camera_worn");

        JsonNode back = turn("w1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn", "allowGpt": false}""".formatted(sid));
        assertThat(back.path("next").asString()).isEqualTo("camera_worn");
        JsonNode oc = back.path("result").path("outfitCheck");
        assertThat(oc.path("garments")).hasSize(2);
        assertThat(oc.path("damage").get(0).path("action").asString()).isEqualTo("wash");
        assertThat(back.path("speech").asString()).contains("빨래").endsWith("앞모습부터 다시 볼게요.");

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
        assertThat(r.path("speech").asString()).contains("내려놓고 찍으실래요");

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
        assertThat(again.path("speech").asString()).contains("뒤로");
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

    @Test
    void 처음_애매한_얼룩이_나오면_GPT_확인을_한_번만_묻고_좋다면_다시_본다() throws Exception {
        JsonNode first = turn("u-gpt", """
                {"image": "UNSURE1", "imageKind": "outfit"}""");
        assertThat(first.path("speech").asString()).contains("더 정확한 AI에게");
        assertThat(first.has("gptConsent")).isFalse();
        String sid = first.path("sessionId").asString();

        JsonNode yes = turn("u-gpt", """
                {"sessionId": "%s", "utterance": "좋아"}""".formatted(sid));
        assertThat(yes.path("gptConsent").asBoolean()).isTrue();
        assertThat(yes.path("speech").asString()).startsWith("고마워요. 한 번 더 확인해 봤어요.").contains("같아요");
        assertThat(yes.path("next").asString()).isEqualTo("camera_outfit");

        // 이미 답했으면 다시 묻지 않습니다.
        JsonNode again = turn("u-gpt", """
                {"sessionId": "%s", "image": "UNSURE2", "imageKind": "outfit"}""".formatted(sid));
        assertThat(again.path("speech").asString()).doesNotContain("더 정확한 AI에게");
    }

    @Test
    void GPT_확인을_거절하면_기억하고_앱이_false를_보내면_묻지_않는다() throws Exception {
        JsonNode first = turn("u-gpt2", """
                {"image": "UNSURE1", "imageKind": "outfit"}""");
        String sid = first.path("sessionId").asString();
        JsonNode no = turn("u-gpt2", """
                {"sessionId": "%s", "utterance": "아니"}""".formatted(sid));
        assertThat(no.path("gptConsent").asBoolean(true)).isFalse();

        JsonNode later = turn("u-gpt3", """
                {"image": "UNSURE1", "imageKind": "outfit", "allowGpt": false}""");
        assertThat(later.path("speech").asString()).doesNotContain("더 정확한 AI에게");
    }

    @Test
    void 무늬를_모르는_옷이_나와도_GPT_확인을_묻는다() throws Exception {
        JsonNode first = turn("u-gpt4", """
                {"image": "PATTERN?1", "imageKind": "outfit"}""");
        assertThat(first.path("speech").asString()).contains("더 정확한 AI에게");
    }

    @Test
    void 입고_한_바퀴에서_앞모습에_애매한_얼룩이_있으면_다_본_뒤에_묻는다() throws Exception {
        String sid = turn("u-gpt5", """
                {"utterance": "입고 볼게 오늘 뭐 입지"}""").path("sessionId").asString();
        JsonNode front = turn("u-gpt5", """
                {"sessionId": "%s", "image": "UNSURE1", "imageKind": "worn"}""".formatted(sid));
        assertThat(front.path("speech").asString()).contains("뒤로").doesNotContain("더 정확한 AI에게");
        JsonNode back = turn("u-gpt5", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        assertThat(back.path("speech").asString()).contains("더 정확한 AI에게");
        assertThat(back.path("next").asString()).isEqualTo("camera_worn");

        JsonNode yes = turn("u-gpt5", """
                {"sessionId": "%s", "utterance": "좋아"}""".formatted(sid));
        assertThat(yes.path("gptConsent").asBoolean()).isTrue();
        assertThat(yes.path("next").asString()).isEqualTo("camera_worn");
    }

    @Test
    void 애매한_부분이_없으면_묻지_않는다() throws Exception {
        JsonNode first = turn("u-gpt6", """
                {"image": "AAAA", "imageKind": "outfit"}""");
        assertThat(first.path("speech").asString()).doesNotContain("더 정확한 AI에게");
    }

    @Test
    void 입고_한_바퀴_처음엔_옷_실수_확인을_위해_GPT_동의를_묻는다() throws Exception {
        String sid = turn("u-wear1", """
                {"utterance": "입고 볼게 오늘 뭐 입지"}""").path("sessionId").asString();
        turn("u-wear1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        JsonNode back = turn("u-wear1", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        assertThat(back.path("speech").asString()).contains("뒤집어 입었는지").contains("얼굴 쪽은 가리고");

        JsonNode yes = turn("u-wear1", """
                {"sessionId": "%s", "utterance": "좋아"}""".formatted(sid));
        assertThat(yes.path("gptConsent").asBoolean()).isTrue();
        assertThat(yes.path("speech").asString()).contains("앞모습부터 한 번 더");
        assertThat(yes.path("next").asString()).isEqualTo("camera_worn");
    }

    @Test
    void 동의했으면_앞뒤에서_본_옷_실수를_한_번씩_말한다() throws Exception {
        String sid = turn("u-wear2", """
                {"utterance": "입고 볼게 오늘 뭐 입지", "allowGpt": true}""").path("sessionId").asString();
        JsonNode front = turn("u-wear2", """
                {"sessionId": "%s", "image": "WEAR1", "imageKind": "worn", "allowGpt": true}""".formatted(sid));
        assertThat(front.path("speech").asString()).contains("뒤로").doesNotContain("단추");
        JsonNode back = turn("u-wear2", """
                {"sessionId": "%s", "image": "WEAR2", "imageKind": "worn", "allowGpt": true}""".formatted(sid));
        String speech = back.path("speech").asString();
        assertThat(speech).contains("흰색 셔츠 단추가 한 칸씩 어긋나게 잠긴 것 같아요")
                .contains("흰색 셔츠 택이 밖으로 나와 있는 것 같아요")
                .doesNotContain("더 정확한 AI에게").endsWith("앞모습부터 다시 볼게요.");
        assertThat(speech.split("택이", -1)).hasSize(2);
    }

    @Test
    void 옷_실수가_없으면_아무_말도_더하지_않는다() throws Exception {
        String sid = turn("u-wear3", """
                {"utterance": "입고 볼게 오늘 뭐 입지", "allowGpt": true}""").path("sessionId").asString();
        turn("u-wear3", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn", "allowGpt": true}""".formatted(sid));
        JsonNode back = turn("u-wear3", """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "worn", "allowGpt": true}""".formatted(sid));
        assertThat(back.path("speech").asString()).doesNotContain("같아요. 솔기").doesNotContain("택이")
                .doesNotContain("단추가");
    }
}
