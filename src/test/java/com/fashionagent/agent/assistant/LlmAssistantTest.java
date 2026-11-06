package com.fashionagent.agent.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.FakeAgentTools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 진짜 GPT 대신 "이 도구를 이 값으로 불러라"를 미리 정해 둔 가짜 모델로, 도구 연결을 확인합니다:
 * userId·사진은 agent가 넣는지, 결과가 GPT에게 돌아가는지, 시간 초과 때 이미 한 일을 말하는지.
 */
class LlmAssistantTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-04T07:00:00Z"), ZoneId.of("Asia/Seoul"));

    /** 부른 도구와 인자를 남기는 가짜 도구. */
    private final List<String> calls = new ArrayList<>();
    private final AgentTools tools = new AgentTools() {
        final FakeAgentTools fake = new FakeAgentTools(json);

        @Override
        public JsonNode call(String tool, Map<String, Object> args) {
            calls.add(tool + " " + new java.util.TreeMap<>(args));
            return fake.call(tool, args);
        }
    };

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    /** 도구를 순서대로 부르고(GPT 내부 실행처럼), 마지막에 결과를 보고 답을 만드는 가짜 모델. */
    static class ScriptedModel implements ChatModel {
        final List<String[]> steps;
        final Function<List<String>, String> answer;
        String systemSeen;
        final long delayMs;

        ScriptedModel(List<String[]> steps, Function<List<String>, String> answer, long delayMs) {
            this.steps = steps;
            this.answer = answer;
            this.delayMs = delayMs;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            systemSeen = prompt.getInstructions().stream().filter(m -> m instanceof SystemMessage)
                    .map(m -> m.getText()).findFirst().orElse("");
            var options = (ToolCallingChatOptions) prompt.getOptions();
            List<String> results = new ArrayList<>();
            for (String[] step : steps) {
                ToolCallback cb = options.getToolCallbacks().stream()
                        .filter(c -> c.getToolDefinition().name().equals(step[0])).findFirst().orElseThrow();
                results.add(cb.call(step[1]));
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer.apply(results)))));
        }

        @Override
        public ChatOptions getOptions() {
            return ToolCallingChatOptions.builder().build();
        }
    }

    private LlmAssistant assistant(ScriptedModel model, Duration timeout) {
        return new LlmAssistant(ChatClient.builder(model), tools, json, executor, timeout, clock);
    }

    @Test
    void 일정_등록_userId는_agent가_넣고_결과를_읽어준다() {
        var model = new ScriptedModel(List.<String[]>of(new String[] { "schedule_add",
                "{\"title\":\"친구 결혼식\",\"start\":\"2026-11-14T14:00:00+09:00\",\"place\":\"\"}" }),
                results -> json.readTree(results.get(0)).path("spokenSummary").asString(), 0);
        var session = new AgentSession("s1", "user-1");

        var reply = assistant(model, Duration.ofSeconds(5)).reply(session, "다음 주 토요일 두 시에 친구 결혼식 넣어 줘",
                null, false);

        assertThat(calls).containsExactly("schedule_add {start=2026-11-14T14:00:00+09:00, title=친구 결혼식, userId=user-1}");
        assertThat(reply.speech()).contains("친구 결혼식").contains("등록");
        assertThat(reply.next()).isEqualTo(Next.listen);
        assertThat(model.systemSeen).contains("2026-11-04 16:00 수요일");
        assertThat(session.assistantHistory).hasSize(2);
    }

    @Test
    void 키워드에_안_걸린_옷_이야기는_GPT가_옷_추천을_연다() {
        var model = new ScriptedModel(List.<String[]>of(new String[] { "open_feature", "{\"name\":\"outfit\"}" }),
                results -> "네, 볼게요.", 0);
        var session = new AgentSession("s9", "user-9");

        var reply = assistant(model, Duration.ofSeconds(5)).reply(session, "나 오늘 뭐 걸치고 나가면 좋을까", null, false);

        assertThat(reply.feature()).isEqualTo("outfit");
        assertThat(calls).isEmpty();
    }

    @Test
    void 모르는_기능_이름은_열지_않는다() {
        var model = new ScriptedModel(List.<String[]>of(new String[] { "open_feature", "{\"name\":\"music\"}" }),
                results -> "음악은 아직 틀 수 없어요.", 0);

        var reply = assistant(model, Duration.ofSeconds(5)).reply(new AgentSession("s10", "u"), "노래 틀어 줘", null,
                false);

        assertThat(reply.feature()).isNull();
        assertThat(reply.speech()).contains("음악");
    }

    @Test
    void 가족에게_묻기_사진은_agent가_넣는다() {
        var model = new ScriptedModel(List.of(
                new String[] { "helper_contacts", "{}" },
                new String[] { "helper_ask", "{\"contactId\":\"h_1\",\"question\":\"이 옷 괜찮아?\"}" }),
                results -> "엄마에게 사진과 질문을 보냈어요.", 0);
        var session = new AgentSession("s2", "user-2");
        session.keepPhoto("PHOTO_BASE64", clock);

        var reply = assistant(model, Duration.ofSeconds(5)).reply(session, "엄마한테 이 옷 괜찮은지 물어봐 줘",
                null, true);

        assertThat(calls).containsExactly("helper_contacts {userId=user-2}",
                "helper_ask {contactId=h_1, imageBase64=PHOTO_BASE64, question=이 옷 괜찮아?, userId=user-2}");
        assertThat(session.lastHelperRequestId).isEqualTo("r_fake");
        assertThat(reply.next()).isEqualTo(Next.camera_outfit);
    }

    @Test
    void 사진이_없으면_보내지_않고_카메라를_켠다() {
        var model = new ScriptedModel(List.<String[]>of(
                new String[] { "helper_ask", "{\"contactId\":\"h_1\",\"question\":\"이 옷 괜찮아?\"}" }),
                results -> results.get(0).contains("no_photo") ? "먼저 옷을 카메라에 보여 주세요." : "보냈어요.", 0);
        var session = new AgentSession("s3", "user-3");
        session.keepPhoto("OLD", Clock.offset(clock, Duration.ofMinutes(-11)));

        var reply = assistant(model, Duration.ofSeconds(5)).reply(session, "엄마한테 물어봐 줘", null, false);

        assertThat(calls).isEmpty();
        assertThat(reply.speech()).isEqualTo("먼저 옷을 카메라에 보여 주세요.");
        assertThat(reply.next()).isEqualTo(Next.camera_outfit);
    }

    @Test
    void 날씨는_앱이_보낸_위치로() {
        var model = new ScriptedModel(List.<String[]>of(new String[] { "weather_today", "{}" }),
                results -> "맑아요.", 0);

        assistant(model, Duration.ofSeconds(5)).reply(new AgentSession("s4", "u"), "우산 챙겨야 해?",
                new Location(35.1, 129.0, false), false);

        assertThat(calls).containsExactly("weather_today {lat=35.1, lon=129.0}");
    }

    @Test
    void 시간_안에_답이_없어도_등록한_일정은_말해준다() {
        var model = new ScriptedModel(List.<String[]>of(new String[] { "schedule_add",
                "{\"title\":\"면접\",\"start\":\"2026-11-05T10:00:00+09:00\"}" }), results -> "늦은 답", 2000);

        var reply = assistant(model, Duration.ofMillis(300)).reply(new AgentSession("s5", "u"), "내일 열 시 면접 넣어 줘",
                null, false);

        assertThat(reply.speech()).isEqualTo("일정 '면접'을 등록했어요.");
    }

    @Test
    void 지운_뒤_답이_늦어도_지운_일정을_말해준다() {
        var model = new ScriptedModel(List.of(
                new String[] { "schedule_list", "{\"date\":\"2026-11-06\"}" },
                new String[] { "schedule_delete", "{\"eventId\":\"e_fake\"}" }), results -> "늦은 답", 2000);

        var reply = assistant(model, Duration.ofMillis(300)).reply(new AgentSession("s6", "u"), "모레 면접 지워 줘",
                null, false);

        assertThat(reply.speech()).isEqualTo("일정 '면접'을 지웠어요.");
    }

    @Test
    void 보낸_뒤_답이_늦어도_보냈다고_말해준다() {
        var model = new ScriptedModel(List.of(
                new String[] { "helper_contacts", "{}" },
                new String[] { "helper_ask", "{\"contactId\":\"h_1\",\"question\":\"이 옷 괜찮아?\"}" }),
                results -> "늦은 답", 2000);
        var session = new AgentSession("s7", "u");
        session.keepPhoto("PHOTO", clock);

        var reply = assistant(model, Duration.ofMillis(300)).reply(session, "엄마한테 물어봐 줘", null, false);

        assertThat(reply.speech()).startsWith("엄마에게 사진과 질문을 보냈어요.");
    }

    @Test
    void 사용량_초과면_다시_말해달라고_하지_않는다() {
        ChatModel quota = new ChatModel() {
            @Override
            public ChatResponse call(Prompt prompt) {
                throw new RuntimeException("Failed to generate content",
                        new RuntimeException("429 . You exceeded your current quota"));
            }

            @Override
            public ChatOptions getOptions() {
                return ToolCallingChatOptions.builder().build();
            }
        };
        var assistant = new LlmAssistant(ChatClient.builder(quota), tools, json, executor, Duration.ofSeconds(5),
                clock);

        var reply = assistant.reply(new AgentSession("s8", "u"), "내일 일정 뭐야?", null, false);

        assertThat(reply.speech()).isEqualTo(LlmAssistant.QUOTA_SPEECH);
    }

    @Test
    void 보내_놓고_확인을_묻는_답이면_보냈다고_고쳐_말한다() {
        // 2026-11-04 실제 GPT: helper_ask를 부르고도 "보내고 싶으면 확인이라고 말해 주세요"라고 답함
        var model = new ScriptedModel(List.of(
                new String[] { "helper_contacts", "{}" },
                new String[] { "helper_ask", "{\"contactId\":\"h_1\",\"question\":\"이 옷 괜찮아?\"}" }),
                results -> "엄마한테 물어볼게요. 보내고 싶으면 확인이라고 말해 주세요.", 0);
        var session = new AgentSession("s9", "u");
        session.keepPhoto("PHOTO", clock);

        var reply = assistant(model, Duration.ofSeconds(5)).reply(session, "엄마한테 물어봐 줘", null, false);

        assertThat(reply.speech()).startsWith("엄마에게 사진과 질문을 보냈어요.").doesNotContain("확인이라고");
    }

    @Test
    void 여러_날은_날마다_불러_한_번에_돌려준다() {
        var model = new ScriptedModel(List.<String[]>of(
                new String[] { "schedule_list", "{\"date\":\"2026-11-09\",\"endDate\":\"2026-11-11\"}" }),
                results -> results.get(0), 0);

        var reply = assistant(model, Duration.ofSeconds(5)).reply(new AgentSession("s10", "u"), "다음 주 일정 알려 줘",
                null, false);

        assertThat(calls).containsExactly("schedule_list {date=2026-11-09, userId=u}",
                "schedule_list {date=2026-11-10, userId=u}", "schedule_list {date=2026-11-11, userId=u}");
        assertThat(json.readTree(reply.speech()).path("days")).hasSize(3);
    }

    @Test
    void 여러_날은_14일까지만() {
        var model = new ScriptedModel(List.<String[]>of(
                new String[] { "schedule_list", "{\"date\":\"2026-11-01\",\"endDate\":\"2026-12-31\"}" }),
                results -> "목록", 0);

        assistant(model, Duration.ofSeconds(5)).reply(new AgentSession("s11", "u"), "일정 다 알려 줘", null, false);

        assertThat(calls).hasSize(AssistantTools.MAX_LIST_DAYS);
    }

    @Test
    void 같은_답이_줄바꿈으로_두_번_오면_한_번만() {
        assertThat(LlmAssistant.tidy("일정이 없어요.\n일정이 없어요.")).isEqualTo("일정이 없어요.");
        assertThat(LlmAssistant.tidy("첫 문장이에요.\n\n둘째 문장이에요.")).isEqualTo("첫 문장이에요. 둘째 문장이에요.");
        assertThat(LlmAssistant.tidy("누구에게 물어볼까요요? 이름을 말해 주세요요")).isEqualTo("누구에게 물어볼까요? 이름을 말해 주세요");
    }

    @Test
    void 누구에게_보냈는지_빠지면_넣어_말한다() {
        var model = new ScriptedModel(List.of(
                new String[] { "helper_contacts", "{}" },
                new String[] { "helper_ask", "{\"contactId\":\"h_1\",\"question\":\"이 옷 괜찮아?\"}" }),
                results -> "보냈어요.", 0);
        var session = new AgentSession("s12", "u");
        session.keepPhoto("PHOTO", clock);

        var reply = assistant(model, Duration.ofSeconds(5)).reply(session, "엄마한테 물어봐 줘", null, false);

        assertThat(reply.speech()).startsWith("엄마에게 사진과 질문을 보냈어요.");
    }

    @Test
    void 한_일을_제대로_말하면_GPT_답을_그대로() {
        var model = new ScriptedModel(List.of(
                new String[] { "schedule_list", "{\"date\":\"2026-11-06\"}" },
                new String[] { "schedule_delete", "{\"eventId\":\"e_fake\"}" }),
                results -> "금요일 면접 일정을 삭제했어요.", 0);

        var reply = assistant(model, Duration.ofSeconds(5)).reply(new AgentSession("s13", "u"), "금요일 면접 지워 줘",
                null, false);

        assertThat(reply.speech()).isEqualTo("금요일 면접 일정을 삭제했어요.");
    }
}
