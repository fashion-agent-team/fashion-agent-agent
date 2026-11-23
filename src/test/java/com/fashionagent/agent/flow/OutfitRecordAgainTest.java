package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import com.fashionagent.agent.api.TestTokens;

/**
 * 2026-11-20 점검: 같은 대화(세션 30분)에서 두 번째로 고른 코디가 기록되지 않던 문제와
 * "내려놓고 볼게, 내일 뭐 입지?"가 새 추천이 아니라 방식 바꾸기로 가던 문제.
 * 가짜 도구(FakeAgentTools)를 감싸 closet_wear가 몇 번, 어느 날짜로 불렸는지 봅니다.
 */
@SpringBootTest(properties = TestTokens.PROPERTY)
@AutoConfigureMockMvc
class OutfitRecordAgainTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    Clock clock;

    @MockitoSpyBean
    AgentTools tools;

    @BeforeEach
    void reset() {
        clearInvocations(tools);
    }

    private JsonNode turn(String userId, String body) throws Exception {
        String out = mvc.perform(post("/api/v1/agent/turn").header("Authorization", TestTokens.bearer(userId))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(out);
    }

    private JsonNode say(String userId, String sid, String utterance) throws Exception {
        return turn(userId, """
                {"sessionId": "%s", "utterance": "%s"}""".formatted(sid, utterance));
    }

    private void showLaid(String userId, String sid) throws Exception {
        turn(userId, """
                {"sessionId": "%s", "image": "AAAA", "imageKind": "outfit"}""".formatted(sid));
    }

    private static Object dateOf(java.util.Map<String, Object> args) {
        return args.get("date");
    }

    @Test
    @DisplayName("Given 오늘 코디를 고르고 기록한 대화 When 같은 대화에서 '내려놓고 볼게, 내일 뭐 입지?'로 다시 고르면 "
            + "Then 내일 날짜로 closet_wear를 한 번 더 부른다")
    void recordsSecondOutfitForTomorrow() throws Exception {
        // given
        String user = "u_again";
        String today = LocalDate.now(clock.withZone(SEOUL)).toString();
        String tomorrow = LocalDate.now(clock.withZone(SEOUL)).plusDays(1).toString();
        String sid = turn(user, """
                {"utterance": "옷 내려놓고 볼게 오늘 뭐 입지"}""").path("sessionId").asString();
        showLaid(user, sid);
        assertThat(say(user, sid, "이걸로 할게").path("speech").asString()).contains("기록");
        say(user, sid, "아니요 괜찮아요");
        verify(tools, times(1)).callForUser(eq(ToolNames.CLOSET_WEAR), eq(TestTokens.id(user)),
                argThat(a -> today.equals(dateOf(a))));
        clearInvocations(tools);

        // when
        JsonNode advice = say(user, sid, "내려놓고 볼게, 내일 뭐 입지?");
        assertThat(advice.path("result").path("type").asString()).isEqualTo("outfit_advice");
        say(user, sid, "네 봐 줘");
        showLaid(user, sid);
        JsonNode picked = say(user, sid, "이걸로 할게");

        // then
        assertThat(picked.path("speech").asString()).contains("기록");
        verify(tools, times(1)).callForUser(eq(ToolNames.CLOSET_WEAR), any(), anyMap());
        verify(tools).callForUser(eq(ToolNames.CLOSET_WEAR), eq(TestTokens.id(user)), argThat(a -> tomorrow.equals(dateOf(a))));
    }

    @Test
    @DisplayName("Given 내려놓고 고른 옷을 기록한 대화 When 입은 모습도 보고 '이걸로 할게'라고 하면 "
            + "Then closet_wear는 처음 한 번만 부른다")
    void laidThenWornRecordsOnce() throws Exception {
        // given
        String user = "u_once";
        String sid = turn(user, """
                {"utterance": "옷 내려놓고 볼게 뭐 입지"}""").path("sessionId").asString();
        showLaid(user, sid);
        say(user, sid, "이걸로 할게");

        // when
        assertThat(say(user, sid, "네 볼게요").path("next").asString()).isEqualTo("camera_worn");
        for (int i = 0; i < 3; i++) {
            turn(user, """
                    {"sessionId": "%s", "image": "AAAA", "imageKind": "worn"}""".formatted(sid));
        }
        JsonNode done = say(user, sid, "이걸로 할게");

        // then
        assertThat(done.path("next").asString()).isEqualTo("done");
        assertThat(done.path("speech").asString()).doesNotContain("기록");
        verify(tools, times(1)).callForUser(eq(ToolNames.CLOSET_WEAR), any(), anyMap());
    }
}
