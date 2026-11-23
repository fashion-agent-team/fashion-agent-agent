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
import com.fashionagent.agent.api.TestTokens;

/** 기본 설정(가짜 도구 + 규칙 판단 + 메모리 세션)으로 옷 추천 2단계를 처음부터 끝까지 돌려 봅니다. */
@SpringBootTest(properties = TestTokens.PROPERTY)
@AutoConfigureMockMvc
class TurnFlowTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    private JsonNode turn(String userId, String body) throws Exception {
        String out = mvc.perform(post("/api/v1/agent/turn").header("Authorization", TestTokens.bearer(userId))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    @Test
    void 미리_준비는_바로_202_본문은_없어도_된다() throws Exception {
        mvc.perform(post("/api/v1/agent/warm").header("Authorization", TestTokens.bearer("u-warm"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"location": {"lat": 37.5, "lon": 127.0}}"""))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/agent/warm").header("Authorization", TestTokens.bearer("u-warm")))
                .andExpect(status().isAccepted());
        mvc.perform(post("/api/v1/agent/warm").header("Authorization", "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());
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

    @Autowired
    com.fashionagent.agent.session.SessionStore sessions;

    @Test
    void 게이트웨이가_붙인_내_정보는_세션에_두고_다음_말에서_바뀌면_바꾼다() throws Exception {
        String out = mvc.perform(post("/api/v1/agent/turn").header("Authorization", TestTokens.bearer("u-wearer"))
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"utterance": "오늘 뭐 입지?", "wearer": {"gender": "남자", "heightCm": 180, "name": "진행"}}"""))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String sid = json.readTree(out).path("sessionId").asString();
        var session = sessions.find(TestTokens.id("u-wearer"), sid).orElseThrow();
        assertThat(session.wearer.path("heightCm").asInt()).isEqualTo(180);
        assertThat(session.wearer.has("name")).isFalse();

        turn("u-wearer", "{\"sessionId\": \"%s\", \"utterance\": \"아니요\"}".formatted(sid));
        assertThat(sessions.find(TestTokens.id("u-wearer"), sid).map(s -> s.wearer).orElse(null)).isNull();
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
        mvc.perform(post("/api/v1/agent/turn").header("Authorization", TestTokens.bearer("u4"))
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("bad_request"));
    }

    @Test
    void 보여_주는_방식을_물었을_때_아니면_접고_짧게_내려놓고도_알아듣는다() throws Exception {
        // 2026-11-23 시뮬: "아니"에 방식을 또 물었고, 두 번 물은 뒤 "내려놓고"는 못 알아들었음
        JsonNode ask = turn("u_mode", """
                {"utterance": "옷 검사해 줘"}""");
        String sid = ask.path("sessionId").asString();
        JsonNode no = turn("u_mode", """
                {"sessionId": "%s", "utterance": "아니"}""".formatted(sid));
        assertThat(no.path("speech").asString()).doesNotContain("입고 볼게");
        assertThat(no.path("next").asString()).isEqualTo("listen");

        turn("u_mode", """
                {"sessionId": "%s", "utterance": "응"}""".formatted(sid));
        JsonNode laid = turn("u_mode", """
                {"sessionId": "%s", "utterance": "내려놓고"}""".formatted(sid));
        assertThat(laid.path("next").asString()).isEqualTo("camera_outfit");
    }

    @Test
    void 추천_뒤_날씨를_물어도_봐_줘로_옷_보기를_이어_간다() throws Exception {
        // 2026-11-23 시뮬: 날씨를 한 번 물은 뒤 "봐 줘"를 못 알아들었음
        JsonNode advice = turn("u_look", """
                {"utterance": "오늘 뭐 입지?"}""");
        String sid = advice.path("sessionId").asString();
        turn("u_look", """
                {"sessionId": "%s", "utterance": "아니 그냥 날씨 어때?"}""".formatted(sid));
        JsonNode look = turn("u_look", """
                {"sessionId": "%s", "utterance": "봐 줘"}""".formatted(sid));
        assertThat(look.path("speech").asString()).contains("내려놓고");
    }

    @Test
    void 겉옷_입어야_하냐고_물으면_네_아니요부터_답한다() throws Exception {
        JsonNode r = turn("u_outer", """
                {"utterance": "패딩 입어야 돼?"}""");
        assertThat(r.path("speech").asString()).matches("^(네, 겉옷을 챙기세요|아니요, 겉옷은 없어도 돼요)\\..*");
    }

    @Test
    void 반팔_입어도_되냐고_물으면_날씨로_답한다() throws Exception {
        JsonNode r = turn("u_light", """
                {"utterance": "오늘 반팔 입어도 돼?"}""");
        assertThat(r.path("speech").asString())
                .matches("^(아니요, 그렇게 입기엔 추워요|네, 그렇게 입어도 돼요|조금 쌀쌀할 수 있어요).*");
    }

    @Test
    void 뭐라고_하면_지난_말을_다시_읽어_준다() throws Exception {
        JsonNode first = turn("u_repeat", """
                {"utterance": "오늘 날씨 어때?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode again = turn("u_repeat", """
                {"sessionId": "%s", "utterance": "뭐라고?"}""".formatted(sid));
        assertThat(again.path("speech").asString()).isEqualTo("다시 말씀드릴게요. " + first.path("speech").asString());
        JsonNode twice = turn("u_repeat", """
                {"sessionId": "%s", "utterance": "다시 말해 줘"}""".formatted(sid));
        assertThat(twice.path("speech").asString()).isEqualTo(again.path("speech").asString());
    }

    @Test
    void 이_카메라를_켠_뒤_응이라고_하면_카메라를_이어_간다() throws Exception {
        JsonNode first = turn("u_ack", """
                {"utterance": "이에 뭐 꼈어?"}""");
        assertThat(first.path("next").asString()).isEqualTo("camera_teeth");
        JsonNode ack = turn("u_ack", """
                {"sessionId": "%s", "utterance": "응"}""".formatted(first.path("sessionId").asString()));
        assertThat(ack.path("next").asString()).isEqualTo("camera_teeth");
        assertThat(ack.path("speech").asString()).startsWith("네,");
    }

    @Test
    void 도시_날씨를_물으면_어느_도시인지_말한다() throws Exception {
        JsonNode r = turn("u_city", """
                {"utterance": "부산 날씨 어때?"}""");
        assertThat(r.path("speech").asString()).startsWith("부산 날씨예요. ");
    }

    @Test
    void 추천_뒤_입고_싶다는_말로_카메라를_켜지_않고_다른_거는_자리를_이어_간다() throws Exception {
        JsonNode advice = turn("u_again", """
                {"utterance": "결혼식 가는데 뭐 입지?"}""");
        String sid = advice.path("sessionId").asString();
        JsonNode warm = turn("u_again", """
                {"sessionId": "%s", "utterance": "더 따뜻하게 입고 싶어"}""".formatted(sid));
        assertThat(warm.path("next").asString()).isNotEqualTo("camera_worn");
        JsonNode again = turn("u_again", """
                {"sessionId": "%s", "utterance": "다른 거 추천해 줘"}""".formatted(sid));
        assertThat(again.path("speech").asString()).contains("결혼식");
    }

    @Test
    void 추천_뒤_좀_더_캐주얼하게는_같은_자리로_다시_추천한다() throws Exception {
        JsonNode advice = turn("u_casual", """
                {"utterance": "결혼식 가는데 뭐 입지?"}""");
        String sid = advice.path("sessionId").asString();
        JsonNode again = turn("u_casual", """
                {"sessionId": "%s", "utterance": "좀 더 캐주얼하게"}""".formatted(sid));
        assertThat(again.path("speech").asString()).contains("결혼식");
        JsonNode warm = turn("u_casual", """
                {"sessionId": "%s", "utterance": "더 따뜻하게"}""".formatted(sid));
        assertThat(warm.path("speech").asString()).contains("결혼식");
    }

    @Test
    void 옷을_안_찍었는데_어디냐고_물으면_아직_본_옷이_없다고_한다() throws Exception {
        JsonNode start = turn("u_where0", """
                {"utterance": "옷 검사해 줘"}""");
        String sid = start.path("sessionId").asString();
        turn("u_where0", """
                {"sessionId": "%s", "utterance": "내려놓고"}""".formatted(sid));
        JsonNode where = turn("u_where0", """
                {"sessionId": "%s", "utterance": "어디야?"}""".formatted(sid));
        assertThat(where.path("speech").asString()).startsWith("아직 본 옷이 없어요");
        assertThat(where.path("next").asString()).isEqualTo("camera_outfit");
    }

    @Test
    void 날씨_뒤_날짜만_말하면_그날_날씨를_답하고_그럼_뭐_입지는_그날로_추천한다() throws Exception {
        JsonNode first = turn("u_follow", """
                {"utterance": "내일 날씨 어때?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode next = turn("u_follow", """
                {"sessionId": "%s", "utterance": "모레는?"}""".formatted(sid));
        assertThat(next.path("speech").asString()).startsWith("모레는");
        JsonNode wear = turn("u_follow", """
                {"sessionId": "%s", "utterance": "그럼 뭐 입지?"}""".formatted(sid));
        assertThat(wear.path("speech").asString()).startsWith("모레의 패션을 추천해 드릴게요.");
        JsonNode sat = turn("u_follow", """
                {"sessionId": "%s", "utterance": "토요일은?"}""".formatted(sid));
        assertThat(sat.path("speech").asString()).contains("의 패션을 추천해 드릴게요.");
        JsonNode add = turn("u_follow", """
                {"sessionId": "%s", "utterance": "내일 3시 치과"}""".formatted(sid));
        assertThat(add.path("speech").asString()).doesNotContain("패션을 추천");
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
    void 내일_추천_뒤_치마_입어도_되냐면_내일_날씨로_답한다() throws Exception {
        JsonNode first = turn("u_skirt", """
                {"utterance": "내일 뭐 입지?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_skirt", """
                {"sessionId": "%s", "utterance": "치마 입어도 돼?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("입어도 돼요").doesNotContain("지금 ");
        JsonNode boots = turn("u_skirt", """
                {"sessionId": "%s", "utterance": "장화 신어야 돼?"}""".formatted(sid));
        assertThat(boots.path("speech").asString()).doesNotContain("지금 ");
    }

    @Test
    void 면접이라고_말한_뒤_뭐_입을지_물으면_면접에_맞춘다() throws Exception {
        JsonNode first = turn("u_plan", """
                {"utterance": "나 모레 면접이야"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_plan", """
                {"sessionId": "%s", "utterance": "뭐 입고 가면 좋을까?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레의 패션");
    }

    @Test
    void 추천_뒤_괜찮아_질문은_대화를_끝내지_않는다() throws Exception {
        JsonNode first = turn("u_suit", """
                {"utterance": "오늘 뭐 입지?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_suit", """
                {"sessionId": "%s", "utterance": "검정 정장 괜찮아?"}""".formatted(sid));
        assertThat(r.path("next").asString()).isNotEqualTo("done");
        assertThat(r.path("speech").asString()).startsWith("네, 입어도 돼요.");
    }

    @Test
    void 결혼식_추천_뒤_흰_셔츠를_물으면_피하라고_한다() throws Exception {
        JsonNode first = turn("u_white", """
                {"utterance": "결혼식에 뭐 입지?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_white", """
                {"sessionId": "%s", "utterance": "흰 셔츠 입어도 돼?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("결혼식에는 흰색 옷은").doesNotContain("네, 입어도 돼요");
    }

    @Test
    void 흰_셔츠_뒤_그럼_베이지는_같은_옷을_그_색으로_본다() throws Exception {
        JsonNode first = turn("u_beige", """
                {"utterance": "결혼식에 뭐 입지?"}""");
        String sid = first.path("sessionId").asString();
        turn("u_beige", """
                {"sessionId": "%s", "utterance": "흰 셔츠 입어도 돼?"}""".formatted(sid));
        JsonNode r = turn("u_beige", """
                {"sessionId": "%s", "utterance": "그럼 베이지?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("네,").doesNotContain("결혼식에는 흰색");
    }

    @Test
    void 추천_뒤_신발은_날씨로_신발을_답한다() throws Exception {
        JsonNode first = turn("u_shoes2", """
                {"utterance": "내일 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_shoes2", """
                {"sessionId": "%s", "utterance": "신발은?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotContain("할 수 없는").contains("신발");
    }

    @Test
    void 추천_뒤_치마_말고_바지로는_다시_추천한다() throws Exception {
        JsonNode first = turn("u_skirt", """
                {"utterance": "내일 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_skirt", """
                {"sessionId": "%s", "utterance": "치마 말고 바지로"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotContain("할 수 없는");
    }

    @Test
    void 화장_카메라_중_그만할게는_카메라를_끈다() throws Exception {
        JsonNode first = turn("u_stop2", """
                {"utterance": "얼굴 화장 봐줘"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_stop2", """
                {"sessionId": "%s", "utterance": "그만할게"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("listen");
        assertThat(r.path("speech").asString()).startsWith("네, 알겠어요.");
    }

    @Test
    void 반바지_입어도_돼_뒤_내일은도_그_옷으로_답한다() throws Exception {
        JsonNode first = turn("u_shorts", """
                {"utterance": "반바지 입어도 돼?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_shorts", """
                {"sessionId": "%s", "utterance": "내일은?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).matches("^(네|아니요).*").contains("내일");
    }

    @Test
    void 면접에_정장_입어야_돼는_자리에_맞는지부터_답한다() throws Exception {
        JsonNode r = turn("u_suit2", """
                {"utterance": "내일 면접인데 정장 입어야 돼?"}""");
        assertThat(r.path("speech").asString()).startsWith("네, 격식 있는 자리에는").doesNotContain("네, 겉옷");
    }

    @Test
    void 장례식_추천_뒤_청바지는_단정한_옷을_권한다() throws Exception {
        JsonNode first = turn("u_jeans", """
                {"utterance": "장례식 가는데 뭐 입어"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_jeans", """
                {"sessionId": "%s", "utterance": "청바지 입어도 돼?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("조문 자리에는 너무 편한 옷보다");
        JsonNode shoes = turn("u_jeans", """
                {"sessionId": "%s", "utterance": "운동화 신어도 돼?"}""".formatted(sid));
        assertThat(shoes.path("speech").asString()).startsWith("조문 자리에는 편한 신발보다 단정한 구두");
    }

    @Test
    void 면접_추천_뒤_넥타이와_백팩을_바로_답한다() throws Exception {
        JsonNode first = turn("u_tie", """
                {"utterance": "면접 가는데 뭐 입어"}""");
        String sid = first.path("sessionId").asString();
        JsonNode tie = turn("u_tie", """
                {"sessionId": "%s", "utterance": "넥타이 매야 돼?"}""".formatted(sid));
        assertThat(tie.path("speech").asString()).startsWith("격식 있는 자리에는 넥타이를");
        JsonNode bag = turn("u_tie", """
                {"sessionId": "%s", "utterance": "백팩 메도 돼?"}""".formatted(sid));
        assertThat(bag.path("speech").asString()).contains("백팩보다");
    }

    @Test
    void 결혼식_추천_뒤_넥타이_색과_가방을_답한다() throws Exception {
        JsonNode first = turn("u_tie2", """
                {"utterance": "결혼식 하객룩 추천"}""");
        String sid = first.path("sessionId").asString();
        JsonNode color = turn("u_tie2", """
                {"sessionId": "%s", "utterance": "흰 넥타이 괜찮아?"}""".formatted(sid));
        assertThat(color.path("speech").asString()).startsWith("결혼식에는 네이비나 회색");
        JsonNode bag = turn("u_tie2", """
                {"sessionId": "%s", "utterance": "가방은 뭐 들어"}""".formatted(sid));
        assertThat(bag.path("speech").asString()).startsWith("결혼식에는 단정한 손가방");
    }

    @Test
    void 추천_뒤_음_별로야는_다시_추천한다() throws Exception {
        JsonNode first = turn("u_meh", """
                {"utterance": "오늘 뭐입지?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_meh", """
                {"sessionId": "%s", "utterance": "음 별로야"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotContain("할 수 없는");
        JsonNode r2 = turn("u_meh", """
                {"sessionId": "%s", "utterance": "더 밝은 색으로"}""".formatted(sid));
        assertThat(r2.path("speech").asString()).doesNotContain("할 수 없는");
    }

    @Test
    void 치아_카메라_중_없어는_카메라를_유지한다() throws Exception {
        JsonNode first = turn("u_none", """
                {"utterance": "이 사이에 뭐 꼈어?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_none", """
                {"sessionId": "%s", "utterance": "없어?"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("camera_teeth");
    }

    @Test
    void 회사_추천_뒤_넥타이와_청바지를_답한다() throws Exception {
        JsonNode first = turn("u_office", """
                {"utterance": "내일 회사 가는데 뭐 입어"}""");
        String sid = first.path("sessionId").asString();
        JsonNode tie = turn("u_office", """
                {"sessionId": "%s", "utterance": "넥타이 매야 해?"}""".formatted(sid));
        assertThat(tie.path("speech").asString()).startsWith("회사라면 꼭 매지 않아도");
        JsonNode jeans = turn("u_office", """
                {"sessionId": "%s", "utterance": "청바지 돼?"}""".formatted(sid));
        assertThat(jeans.path("speech").asString()).startsWith("네, 회사라면 청바지도");
    }

    @Test
    void 다음주_날씨_뒤_그럼_화요일_뭐_입지는_다음주_화요일로_추천한다() throws Exception {
        JsonNode first = turn("u_nextweek", """
                {"utterance": "다음주 월요일 날씨"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_nextweek", """
                {"sessionId": "%s", "utterance": "그럼 화요일 뭐 입지"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotStartWith("내일의 패션").contains("화요일");
    }

    @Test
    void 회식_추천_뒤_후드티는_그날_자리에_맞춰_답한다() throws Exception {
        JsonNode first = turn("u_hoodie", """
                {"utterance": "모레 회식인데 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        turn("u_hoodie", """
                {"sessionId": "%s", "utterance": "다른 거"}""".formatted(sid));
        JsonNode r = turn("u_hoodie", """
                {"sessionId": "%s", "utterance": "후드티 괜찮아?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("일하는 자리라").contains("모레");
    }

    @Test
    void 추천_뒤_또_다른_거도_다시_추천한다() throws Exception {
        JsonNode first = turn("u_again3", """
                {"utterance": "추천해줘"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_again3", """
                {"sessionId": "%s", "utterance": "또 다른 거"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotContain("할 수 없는");
    }

    @Test
    void 네이비를_물으면_우산_답을_하지_않는다() throws Exception {
        JsonNode r = turn("u_navy", """
                {"utterance": "네이비 셔츠 입어도 돼?"}""");
        assertThat(r.path("speech").asString()).startsWith("네, 입어도 돼요").doesNotContain("우산");
    }

    @Test
    void 화장_카메라_중_취소하면_카메라를_끈다() throws Exception {
        JsonNode first = turn("u_cancel", """
                {"utterance": "얼굴 화장 봐줘"}""");
        assertThat(first.path("next").asString()).isEqualTo("camera_face");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_cancel", """
                {"sessionId": "%s", "utterance": "취소"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("listen");
        assertThat(r.path("speech").asString()).startsWith("네, 알겠어요.");
    }

    @Test
    void 날과_계획만_말한_뒤_추천은_그날로() throws Exception {
        JsonNode first = turn("u_date_plan", """
                {"utterance": "모레 데이트 있어"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_date_plan", """
                {"sessionId": "%s", "utterance": "옷 추천해줘"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레의 패션");
    }

    @Test
    void 도시_날씨_뒤_거기와_다른_도시를_이어_묻는다() throws Exception {
        JsonNode first = turn("u_city", """
                {"utterance": "부산 날씨 어때?"}""");
        assertThat(first.path("speech").asString()).startsWith("부산 날씨예요.");
        String sid = first.path("sessionId").asString();
        JsonNode there = turn("u_city", """
                {"sessionId": "%s", "utterance": "거기 우산 필요해?"}""".formatted(sid));
        assertThat(there.path("speech").asString()).startsWith("부산 날씨예요.");
        JsonNode seoul = turn("u_city", """
                {"sessionId": "%s", "utterance": "서울은?"}""".formatted(sid));
        assertThat(seoul.path("speech").asString()).startsWith("서울 날씨예요.");
        JsonNode here = turn("u_city", """
                {"sessionId": "%s", "utterance": "여기는?"}""".formatted(sid));
        assertThat(here.path("speech").asString()).doesNotContain("날씨예요.").doesNotContain("할 수 없는");
    }

    @Test
    void 일정_뒤_그날_뭐_입지는_그날로() throws Exception {
        JsonNode first = turn("u_sched_day", """
                {"utterance": "모레 일정 있어?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_sched_day", """
                {"sessionId": "%s", "utterance": "그날 뭐 입지?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레의 패션");
    }

    @Test
    void 날씨_뒤_저녁엔은_같은_날_예보로() throws Exception {
        JsonNode first = turn("u_evening", """
                {"utterance": "모레 아침 추워?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_evening", """
                {"sessionId": "%s", "utterance": "저녁엔?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레").doesNotContain("할 수 없는");
    }

    @Test
    void 추천_뒤_그날_일정은은_그날로() throws Exception {
        JsonNode first = turn("u_xmas_sched", """
                {"utterance": "모레 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_xmas_sched", """
                {"sessionId": "%s", "utterance": "그날 일정은?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레").doesNotContain("오늘");
    }

    @Test
    void 다른_도시_날씨_뒤_그날_뭐_입지는_그_도시로() throws Exception {
        JsonNode first = turn("u_city_outfit", """
                {"utterance": "모레 제주도 날씨"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_city_outfit", """
                {"sessionId": "%s", "utterance": "그날 뭐 입지"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("제주 날씨로 볼게요. 모레의 패션");
        JsonNode again = turn("u_city_outfit", """
                {"sessionId": "%s", "utterance": "다른 거"}""".formatted(sid));
        assertThat(again.path("speech").asString()).startsWith("제주 날씨로 볼게요.");
    }

    @Test
    void 오늘_도시_날씨_뒤_그럼_뭐_입지는_그_도시로() throws Exception {
        JsonNode first = turn("u_city_today", """
                {"utterance": "부산 날씨"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_city_today", """
                {"sessionId": "%s", "utterance": "그럼 뭐 입지"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("부산 날씨로 볼게요. 오늘의 패션");
        JsonNode w = turn("u_city_today", """
                {"sessionId": "%s", "utterance": "거기 비 와?"}""".formatted(sid));
        assertThat(w.path("speech").asString()).startsWith("부산 날씨예요.");
        JsonNode again = turn("u_city_today", """
                {"sessionId": "%s", "utterance": "다른 거"}""".formatted(sid));
        assertThat(again.path("speech").asString()).startsWith("부산 날씨로 볼게요.");
    }

    @Test
    void 일정_뒤_그날_부산_날씨는은_그날_부산으로() throws Exception {
        JsonNode first = turn("u_sched_city", """
                {"utterance": "모레 일정 알려줘"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_sched_city", """
                {"sessionId": "%s", "utterance": "그날 부산 날씨는?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("부산 날씨예요. 모레").doesNotContain("지금");
    }

    @Test
    void 추천_뒤_체육_있는데는_운동_자리로_다시_추천() throws Exception {
        JsonNode first = turn("u_gym", """
                {"utterance": "모레 학교 가는데 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_gym", """
                {"sessionId": "%s", "utterance": "체육 있는데"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레의 패션").doesNotContain("할 수 없는");
    }

    @Test
    void 추천_뒤_다른_도시는은_그_도시로_다시_추천() throws Exception {
        JsonNode first = turn("u_city_switch", """
                {"utterance": "내일 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_city_switch", """
                {"sessionId": "%s", "utterance": "서울 말고 부산은?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("부산 날씨로 볼게요. 내일의 패션");
    }

    @Test
    void 날씨_뒤_거기_뭐_입어는_그날로() throws Exception {
        JsonNode first = turn("u_there_day", """
                {"utterance": "모레 날씨 어때"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_there_day", """
                {"sessionId": "%s", "utterance": "거기 뭐 입어"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레의 패션");
    }

    @Test
    void 토요일_결혼식이라고_말한_뒤_흰_블라우스를_물으면_피하라고_한다() throws Exception {
        JsonNode plan = turn("u_plan_white", """
                {"utterance": "이번주 토요일 결혼식이야"}""");
        String sid = plan.path("sessionId").asString();
        JsonNode r = turn("u_plan_white", """
                {"sessionId": "%s", "utterance": "흰 블라우스 입어도 돼?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("결혼식에는 흰색 옷은");
    }

    @Test
    void 입은_옷_기록_뒤_어제는은_어제_기록을_읽는다() throws Exception {
        JsonNode first = turn("u_hist_follow", """
                {"utterance": "나 오늘 뭐 입었는지 기억해?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_hist_follow", """
                {"sessionId": "%s", "utterance": "어제는?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("어제").doesNotContain("할 수 없는");
    }

    @Test
    void 비_와_뒤_내일은은_내일_비_소식으로_답한다() throws Exception {
        JsonNode first = turn("u_rain_follow", """
                {"utterance": "오늘 비 와?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_rain_follow", """
                {"sessionId": "%s", "utterance": "내일은?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).containsAnyOf("우산", "비");
    }

    @Test
    void 일정_뒤_그_다음날은과_그날_날씨는을_이어_답한다() throws Exception {
        JsonNode first = turn("u_nextday", """
                {"utterance": "내일 일정 뭐 있어?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode next = turn("u_nextday", """
                {"sessionId": "%s", "utterance": "그 다음날은?"}""".formatted(sid));
        assertThat(next.path("speech").asString()).contains("모레").doesNotContain("할 수 없는");
        JsonNode w = turn("u_nextday", """
                {"sessionId": "%s", "utterance": "그날 날씨는?"}""".formatted(sid));
        assertThat(w.path("speech").asString()).startsWith("모레").doesNotContain("지금");
    }

    @Test
    void 영하_질문_뒤_모레는은_영하인지로_답한다() throws Exception {
        JsonNode first = turn("u_freeze_follow", """
                {"utterance": "내일 영하야?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_freeze_follow", """
                {"sessionId": "%s", "utterance": "모레는?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("영하");
    }

    @Test
    void 다음주_월요일_뒤_화요일은은_다음주_화요일() throws Exception {
        JsonNode first = turn("u_nextweek_day", """
                {"utterance": "다음주 월요일 비 와?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_nextweek_day", """
                {"sessionId": "%s", "utterance": "화요일은?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("화요일").doesNotContain("내일").doesNotContain("모레");
    }

    @Test
    void 추천_뒤_너무_꾸민_건_싫어는_다시_추천() throws Exception {
        JsonNode first = turn("u_dislike", """
                {"utterance": "모레 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_dislike", """
                {"sessionId": "%s", "utterance": "너무 꾸민 건 싫어"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레").doesNotContain("언제든 불러");
        assertThat(r.path("next").asString()).isNotEqualTo("done");
    }

    @Test
    void 추천_뒤_어제_입은_거_말고는_다시_추천() throws Exception {
        JsonNode first = turn("u_not_yesterday", """
                {"utterance": "모레 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_not_yesterday", """
                {"sessionId": "%s", "utterance": "어제 입은 거 말고"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레");
        assertThat(r.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void 데이트야_뒤_뭐_입지는_그날로() throws Exception {
        JsonNode first = turn("u_date_plan", """
                {"utterance": "모레 데이트야"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_date_plan", """
                {"sessionId": "%s", "utterance": "뭐 입지"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레");
    }

    @Test
    void 추천_뒤_그거_말고는_다시_추천() throws Exception {
        JsonNode first = turn("u_not_that", """
                {"utterance": "모레 뭐 입지"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_not_that", """
                {"sessionId": "%s", "utterance": "그거 말고"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레").doesNotContain("할 수 없는");
    }

    @Test
    void 운동화_젖어는_비로_답한다() throws Exception {
        JsonNode r = turn("u_wet", """
                {"utterance": "모레 운동화 젖어?"}""");
        assertThat(r.path("speech").asString()).doesNotContain("신어도 돼요").doesNotContain("할 수 없는");
    }

    @Test
    void 우산_뒤_겉옷은은_챙기라고_답한다() throws Exception {
        JsonNode first = turn("u_outer_follow", """
                {"utterance": "모레 우산 챙겨?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_outer_follow", """
                {"sessionId": "%s", "utterance": "겉옷은?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotContain("입어도 돼요").doesNotContain("할 수 없는");
        JsonNode lt = turn("u_outer_follow", """
                {"sessionId": "%s", "utterance": "그럼 반팔?"}""".formatted(sid));
        assertThat(lt.path("speech").asString()).doesNotContain("할 수 없는");
        JsonNode sh = turn("u_outer_follow", """
                {"sessionId": "%s", "utterance": "샌들은?"}""".formatted(sid));
        assertThat(sh.path("speech").asString()).doesNotContain("입어도").doesNotContain("할 수 없는");
    }

    @Test
    void 옷_카메라에서_본_옷이_없을_때_그만은_카메라를_끈다() throws Exception {
        JsonNode first = turn("u_outfit_stop", """
                {"utterance": "옷 검사해줘"}""");
        String sid = first.path("sessionId").asString();
        turn("u_outfit_stop", """
                {"sessionId": "%s", "utterance": "내려놓고"}""".formatted(sid));
        JsonNode r = turn("u_outfit_stop", """
                {"sessionId": "%s", "utterance": "그만"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("listen");
        assertThat(r.path("speech").asString()).doesNotContain("보여 주실래요");
    }

    @Test
    void 화장_카메라_중_괜찮아는_비춰_달라고_한다() throws Exception {
        JsonNode first = turn("u_face_ok", """
                {"utterance": "화장 좀 봐줘"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_face_ok", """
                {"sessionId": "%s", "utterance": "괜찮아?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("비춰").doesNotContain("할 수 없는");

        for (String u : new String[] {"번졌어?", "너무 진해?", "어색해?", "오른쪽 봐줘", "다시 봐줘", "다 됐어"}) {
            JsonNode q = turn("u_face_ok", """
                    {"sessionId": "%s", "utterance": "%s"}""".formatted(sid, u));
            assertThat(q.path("speech").asString()).contains("비춰");
        }
        JsonNode end = turn("u_face_ok", """
                {"sessionId": "%s", "utterance": "끝"}""".formatted(sid));
        assertThat(end.path("next").asString()).isNotEqualTo("camera_face");
    }

    @Test
    void 추워_뒤_얼마나는_기온으로_답한다() throws Exception {
        JsonNode first = turn("u_howmuch", """
                {"utterance": "추워?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_howmuch", """
                {"sessionId": "%s", "utterance": "얼마나?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("도").doesNotContain("할 수 없는");
    }

    @Test
    void 방식을_묻는_중_옷장을_물으면_옷장으로_답한다() throws Exception {
        JsonNode first = turn("u_mode_escape", """
                {"utterance": "옷 검사해 줘"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_mode_escape", """
                {"sessionId": "%s", "utterance": "옷장에 셔츠 있어?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("옷장").doesNotContain("내려놓고 볼게");
    }

    @Test
    void 내일_추천_뒤_추천해준_거_말고는_내일로_다시_추천한다() throws Exception {
        JsonNode first = turn("u_notthis", """
                {"utterance": "내일 뭐 입어?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_notthis", """
                {"sessionId": "%s", "utterance": "추천해준 거 말고"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("내일");
    }

    @Test
    void 내일_나간다고_한_뒤_그때_추워는_내일_날씨로_답한다() throws Exception {
        JsonNode first = turn("u_then_cold", """
                {"utterance": "내일 아침 7시에 나가"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_then_cold", """
                {"sessionId": "%s", "utterance": "그때 추워?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).contains("내일").doesNotContain("지금");
    }

    @Test
    void 날씨_뒤_내일_낮엔은_내일_날씨로_답한다() throws Exception {
        JsonNode first = turn("u_daytime", """
                {"utterance": "오늘 밤에 추워?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_daytime", """
                {"sessionId": "%s", "utterance": "내일 낮엔?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("내일");
    }

    @Test
    void 추천_뒤_아니_내일_거는_내일로_다시_추천한다() throws Exception {
        JsonNode first = turn("u_no_tomorrow", """
                {"utterance": "옷 추천해줘"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_no_tomorrow", """
                {"sessionId": "%s", "utterance": "아니 내일 거"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("내일");
        assertThat(r.path("next").asString()).isNotEqualTo("done");
    }

    @Test
    void 추천_뒤_편하게_입고_싶어는_카메라가_아니라_다시_추천한다() throws Exception {
        JsonNode first = turn("u_comfy", """
                {"utterance": "오늘 뭐 입을지 모르겠어"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_comfy", """
                {"sessionId": "%s", "utterance": "편하게 입고 싶어"}""".formatted(sid));
        assertThat(r.path("next").asString()).isEqualTo("listen");
        assertThat(r.path("speech").asString()).contains("추천");
    }

    @Test
    void 내일_추천_뒤_날_없이_다시_물으면_내일로_추천한다() throws Exception {
        JsonNode first = turn("u_keepday", """
                {"utterance": "내일 등교하는데 뭐 입지?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_keepday", """
                {"sessionId": "%s", "utterance": "교복 위에 뭐 입어?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).doesNotStartWith("오늘");
    }

    @Test
    void 결혼식_원피스_뒤_흰_원피스는_피하라고_한다() throws Exception {
        JsonNode first = turn("u_dress", """
                {"utterance": "모레 결혼식 가는데 원피스 입어도 돼?"}""");
        assertThat(first.path("speech").asString()).contains("입어도 돼요");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_dress", """
                {"sessionId": "%s", "utterance": "흰 원피스는?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("결혼식에는 흰색 옷은");
    }

    @Test
    void 출근하는데_비_와_뒤_그럼_뭐_입지는_출근에_맞춘다() throws Exception {
        JsonNode first = turn("u_work_rain", """
                {"utterance": "모레 출근하는데 비 와?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode r = turn("u_work_rain", """
                {"sessionId": "%s", "utterance": "그럼 뭐 입지?"}""".formatted(sid));
        assertThat(r.path("speech").asString()).startsWith("모레의 패션");
    }

    @Test
    void 지난_날씨는_알_수_없다고_한다() throws Exception {
        JsonNode r = turn("u_past_weather", """
                {"utterance": "어제 날씨 어땠어?"}""");
        assertThat(r.path("speech").asString()).startsWith("지난 날씨는 알 수 없어요.");
    }

    @Test
    void 어제_일정을_물으면_어제_일정을_읽는다() throws Exception {
        JsonNode r = turn("u_past_sched", """
                {"utterance": "어제 일정 뭐였지?"}""");
        assertThat(r.path("speech").asString()).startsWith("어제");
    }

    @Test
    void 얼굴_고르기_중_그만할래면_멈춘다() throws Exception {
        JsonNode ask = turn("u_face_stop", """
                {"utterance": "얼굴 봐줘"}""");
        String sid = ask.path("sessionId").asString();
        JsonNode stop = turn("u_face_stop", """
                {"sessionId": "%s", "utterance": "그만할래"}""".formatted(sid));
        assertThat(stop.path("speech").asString()).startsWith("네, 알겠어요.");
        assertThat(stop.path("next").asString()).isEqualTo("listen");
    }

    @Test
    void 추천_뒤_다른_거면_같은_날로_다시_추천한다() throws Exception {
        JsonNode first = turn("u_other", """
                {"utterance": "내일 뭐 입지?"}""");
        String sid = first.path("sessionId").asString();
        JsonNode again = turn("u_other", """
                {"sessionId": "%s", "utterance": "다른 거"}""".formatted(sid));
        assertThat(again.path("speech").asString()).startsWith("내일의 패션을 추천해 드릴게요.");
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
