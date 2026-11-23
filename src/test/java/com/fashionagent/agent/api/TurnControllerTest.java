package com.fashionagent.agent.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fashionagent.agent.flow.Prefetch;
import com.fashionagent.agent.flow.TurnService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * TurnController 슬라이스 테스트(@WebMvcTest). 대화 처리(TurnService)와 미리 준비(Prefetch)는 정상 동작한다고 보고 Mock으로 바꿉니다.
 * 로그인 토큰 확인(AccessTokens)도 Mock으로 두고("Bearer t-사용자" → 사용자), 컨트롤러가 맡은 일
 * (토큰으로 사용자 정하기, 입력 확인, 응답 모양, 오류 모양)만 확인합니다. 토큰 확인 자체는 AccessTokensTest가 봅니다.
 * 대화 흐름 자체(추천 → 카메라 → 비교 …)는 TurnFlowTest가 앱 전체를 띄워 봅니다.
 * agent는 specs/에 기능 spec이 없어서 openapi.yaml(POST /api/v1/agent/turn·warm) 약속을 기준으로 씁니다.
 */
@WebMvcTest(controllers = TurnController.class)
class TurnControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    TurnService turns;

    @MockitoBean
    Prefetch prefetch;

    @MockitoBean
    AccessTokens tokens;

    @org.junit.jupiter.api.BeforeEach
    void tokens() {
        org.mockito.BDDMockito.given(tokens.userId(org.mockito.ArgumentMatchers.anyString())).willAnswer(inv -> {
            String auth = inv.getArgument(0);
            if (auth == null || !auth.startsWith("Bearer t-")) {
                throw new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthorized", "토큰이 틀렸어요.");
            }
            return auth.substring("Bearer t-".length());
        });
        org.mockito.BDDMockito.given(tokens.userId(org.mockito.ArgumentMatchers.isNull())).willThrow(
                new ApiException(org.springframework.http.HttpStatus.UNAUTHORIZED, "unauthorized", "로그인 토큰이 없어요."));
    }

    final JsonMapper json = JsonMapper.builder().build();

    static TurnRequest utterance(String text) {
        return new TurnRequest(null, text, null, null, null, null, null, null, null, null, null, null, null);
    }

    static TurnRequest image(String base64) {
        return new TurnRequest("s-1", null, base64, "outfit", null, null, null, null, null, null, null, null, null);
    }

    ResultActions turn(String userId, TurnRequest body, String... headers) throws Exception {
        var req = post("/api/v1/agent/turn").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        if (userId != null) {
            req.header("Authorization", "Bearer t-" + userId);
        }
        for (int i = 0; i < headers.length; i += 2) {
            req.header(headers[i], headers[i + 1]);
        }
        return mvc.perform(req);
    }

    @Test
    @DisplayName("Given 로그인한 사용자 When 말을 보내면 Then 대화 처리 결과(sessionId·speech·next)를 그대로 받는다")
    void 말을_보내면_답을_받는다() throws Exception {
        // given
        given(turns.handle(eq("u-1"), any(), isNull())).willReturn(
                new TurnResponse("s-1", "오늘은 맑아요.", TurnResponse.Next.listen, TurnResponse.Result.of("outfit_advice")));

        // when
        ResultActions result = turn("u-1", utterance("오늘 뭐 입지"));

        // then
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value("s-1"))
                .andExpect(jsonPath("$.speech").value("오늘은 맑아요."))
                .andExpect(jsonPath("$.next").value("listen"))
                .andExpect(jsonPath("$.result.type").value("outfit_advice"));
        ArgumentCaptor<TurnRequest> sent = ArgumentCaptor.forClass(TurnRequest.class);
        then(turns).should().handle(eq("u-1"), sent.capture(), isNull());
        assertThat(sent.getValue().utterance()).isEqualTo("오늘 뭐 입지");
    }

    @Test
    @DisplayName("Given 로그인 토큰이 없음 When 말을 보내면 Then 401 unauthorized이고 대화 처리는 부르지 않는다")
    void 사용자_id가_없으면_401() throws Exception {
        // when
        ResultActions result = turn(null, utterance("오늘 뭐 입지"));

        // then
        result.andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error.code").value("unauthorized"));
        then(turns).should(never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("Given 말도 사진도 없음 When 보내면 Then 400 bad_request")
    void 말도_사진도_없으면_400() throws Exception {
        // when
        ResultActions result = turn("u-1", utterance(" "));

        // then
        result.andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("bad_request"));
        then(turns).should(never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("Given 6MB를 넘는 사진 When 보내면 Then 413 image_too_large")
    void 너무_큰_사진은_413() throws Exception {
        // when
        ResultActions result = turn("u-1", image("A".repeat(TurnController.MAX_IMAGE_CHARS + 1)));

        // then
        result.andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.error.code").value("image_too_large"));
        then(turns).should(never()).handle(any(), any(), any());
    }

    @Test
    @DisplayName("Given JSON이 깨짐 When 보내면 Then 400과 약속한 오류 모양")
    void 깨진_JSON은_400() throws Exception {
        // when
        ResultActions result = mvc.perform(post("/api/v1/agent/turn").header("Authorization", "Bearer t-u-1")
                .contentType(MediaType.APPLICATION_JSON).content("{"));

        // then
        result.andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("bad_request"))
                .andExpect(jsonPath("$.error.message").isNotEmpty());
    }

    @Test
    @DisplayName("Given 대화 처리에서 예상 못 한 오류 When 보내면 Then 500 internal(내부 내용은 숨김)")
    void 예상_못_한_오류는_500() throws Exception {
        // given
        given(turns.handle(any(), any(), any())).willThrow(new IllegalStateException("db password=..."));

        // when
        ResultActions result = turn("u-1", utterance("오늘 뭐 입지"));

        // then
        result.andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("internal"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("password"))));
    }

    @Test
    @DisplayName("Given 게이트웨이가 바디에 넣은 wearer When 말을 보내면 Then 있는 칸만 대화 처리에 넘기고 모르는 칸은 버린다")
    void 바디의_내_정보를_넘긴다() throws Exception {
        // given
        given(turns.handle(any(), any(), any())).willReturn(
                new TurnResponse("s-1", "네", TurnResponse.Next.listen, null));
        String body = """
                {"utterance":"오늘 뭐 입지","wearer":{"gender":"여자","job":"회사원","ageGroup":" ","name":"지원"}}""";

        // when
        mvc.perform(post("/api/v1/agent/turn").header("Authorization", "Bearer t-u-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        // then
        ArgumentCaptor<JsonNode> wearer = ArgumentCaptor.forClass(JsonNode.class);
        then(turns).should().handle(eq("u-1"), any(), wearer.capture());
        assertThat(wearer.getValue().propertyNames()).containsExactlyInAnyOrder("gender", "job");
    }

    @Test
    @DisplayName("Given 앱이 넣은 wearer 뒤에 게이트웨이가 wearer를 덧붙임 When 말을 보내면 Then 뒤에 온 게이트웨이 값을 쓴다")
    void 뒤에_붙은_내_정보가_이긴다() throws Exception {
        // given: 게이트웨이는 바디 맨 끝에 wearer를 덧붙여서 앱 값을 덮어씁니다
        given(turns.handle(any(), any(), any())).willReturn(
                new TurnResponse("s-1", "네", TurnResponse.Next.listen, null));
        String body = """
                {"utterance":"x","wearer":{"job":"가짜"},"wearer":{"job":"대학생","heightCm":175.5}}""";

        // when
        mvc.perform(post("/api/v1/agent/turn").header("Authorization", "Bearer t-u-1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());

        // then
        ArgumentCaptor<JsonNode> wearer = ArgumentCaptor.forClass(JsonNode.class);
        then(turns).should().handle(eq("u-1"), any(), wearer.capture());
        assertThat(wearer.getValue().path("job").asString()).isEqualTo("대학생");
        assertThat(wearer.getValue().path("heightCm").asDouble()).isEqualTo(175.5);
    }

    @Test
    @DisplayName("Given wearer가 null(저장된 정보 없음) When 말을 보내면 Then 정보 없이(null) 대화 처리에 넘긴다")
    void 내_정보가_없으면_null() throws Exception {
        // given
        given(turns.handle(any(), any(), any())).willReturn(
                new TurnResponse("s-1", "네", TurnResponse.Next.listen, null));

        // when
        mvc.perform(post("/api/v1/agent/turn").header("Authorization", "Bearer t-u-1")
                .contentType(MediaType.APPLICATION_JSON).content("{\"utterance\":\"x\",\"wearer\":null}"))
                .andExpect(status().isOk());

        // then
        then(turns).should().handle(eq("u-1"), any(), org.mockito.ArgumentMatchers.isNull());
    }

    @Test
    @DisplayName("Given 앱이 켜짐 When 위치와 함께 미리 준비를 보내면 Then 바로 202이고 그 위치로 미리 불러 둔다")
    void 미리_준비는_바로_202() throws Exception {
        // given
        Location here = new Location(37.5, 127.0, null);

        // when
        ResultActions result = mvc.perform(post("/api/v1/agent/warm").header("Authorization", "Bearer t-u-warm")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new WarmRequest(here))));

        // then
        result.andExpect(status().isAccepted());
        then(prefetch).should().start("u-warm", here);
    }

    @Test
    @DisplayName("Given 본문 없음 When 미리 준비를 보내면 Then 202이고 위치 없이(null) 불러 둔다")
    void 본문_없는_미리_준비도_202() throws Exception {
        // when
        ResultActions result = mvc.perform(post("/api/v1/agent/warm").header("Authorization", "Bearer t-u-warm"));

        // then
        result.andExpect(status().isAccepted());
        then(prefetch).should().start("u-warm", null);
    }

    @Test
    @DisplayName("Given 틀린 토큰 When 미리 준비를 보내면 Then 401")
    void 틀린_토큰의_미리_준비는_401() throws Exception {
        // when
        ResultActions result = mvc.perform(post("/api/v1/agent/warm").header("Authorization", "Bearer forged"));

        // then
        result.andExpect(status().isUnauthorized());
        then(prefetch).should(never()).start(any(), any());
    }
}
