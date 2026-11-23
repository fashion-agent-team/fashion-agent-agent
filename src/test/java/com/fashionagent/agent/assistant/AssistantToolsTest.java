package com.fashionagent.agent.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * GPT가 고르는 도구(AssistantTools)를 GPT 없이 바로 부르는 테스트. 일정 서버는 부른 값을 남기는 가짜로 바꿉니다.
 * GPT를 거쳐 끝까지 보는 건 LlmAssistantTest입니다.
 */
class AssistantToolsTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> calls = new ArrayList<>();
    private final Map<String, Object> listedEvent = new HashMap<>();

    private final AgentTools tools = new AgentTools() {
        @Override
        public JsonNode call(String tool, Map<String, Object> args) {
            calls.add(tool + " " + new java.util.TreeMap<>(args));
            Object out = switch (tool) {
                case ToolNames.SCHEDULE_LIST -> Map.of("events", List.of(listedEvent));
                case ToolNames.SCHEDULE_ADD -> Map.of("eventId", "e_new", "spokenSummary", "등록했어요.");
                case ToolNames.SCHEDULE_DELETE, ToolNames.SCHEDULE_SET_REMINDER -> Map.of("deleted", true);
                default -> Map.of();
            };
            return json.valueToTree(out);
        }
    };

    private AssistantTools assistantTools() {
        return new AssistantTools(tools, new AgentSession("s1", "u1"), null, json);
    }

    @Test
    @DisplayName("quoted Given 받침 없는 이름과 있는 이름 When 조사를 붙이면 Then '회의'를, '면접'을")
    void quotedJosa() {
        assertThat(AssistantTools.quoted("회의", "을", "를")).isEqualTo("'회의'를");
        assertThat(AssistantTools.quoted("면접", "을", "를")).isEqualTo("'면접'을");
        assertThat(AssistantTools.quoted("친구 결혼식", "을", "를")).isEqualTo("'친구 결혼식'을");
    }

    @Test
    @DisplayName("schedule_delete Given 받침 없는 일정 When 지우면 Then '회의'를 지웠어요")
    void deleteSpeechJosa() {
        // given
        listedEvent.putAll(Map.of("eventId", "e_1", "title", "회의", "start", "2026-11-24T10:00:00+09:00"));
        AssistantTools t = assistantTools();
        t.scheduleList("2026-11-24", null);

        // when
        t.scheduleDelete("e_1");

        // then
        assertThat(t.done).singleElement().satisfies(d -> assertThat(d.say()).isEqualTo("일정 '회의'를 지웠어요."));
    }

    @Test
    @DisplayName("schedule_change Given 2일 전 오후 4시 알림이 있는 일정 When 시각을 바꾸면 Then 새 일정에 같은 날 수와 시각을 옮긴다")
    void changeKeepsReminderTime() {
        // given
        listedEvent.putAll(Map.of("eventId", "e_1", "title", "친구 결혼식", "start", "2026-11-28T12:00:00+09:00",
                "place", "더채플앳청담", "remind", true, "remindDaysBefore", 2, "remindTime", "16:00"));
        AssistantTools t = assistantTools();
        t.scheduleList("2026-11-28", null);

        // when
        String out = t.scheduleChange("e_1", "2026-11-28T13:00:00+09:00");

        // then
        assertThat(json.readTree(out).path("changed").asBoolean()).isTrue();
        assertThat(calls).contains(
                "schedule_set_reminder {daysBefore=2, eventId=e_new, remind=true, time=16:00, userId=u1}");
        assertThat(calls).contains("schedule_delete {eventId=e_1, userId=u1}");
    }

    @Test
    @DisplayName("schedule_change Given 알림을 끈 일정 When 시각을 바꾸면 Then remind=false만 옮기고 시각은 넣지 않는다")
    void changeKeepsReminderOff() {
        // given
        listedEvent.putAll(Map.of("eventId", "e_1", "title", "회의", "start", "2026-11-24T10:00:00+09:00",
                "remind", false));
        AssistantTools t = assistantTools();
        t.scheduleList("2026-11-24", null);

        // when
        t.scheduleChange("e_1", "2026-11-24T11:30:00+09:00");

        // then
        assertThat(calls).contains("schedule_set_reminder {eventId=e_new, remind=false, userId=u1}");
        assertThat(t.done).singleElement()
                .satisfies(d -> assertThat(d.say()).isEqualTo("일정 '회의'를 11월 24일 화요일 오전 11시 30분으로 바꿨어요."));
    }

    @Test
    @DisplayName("schedule_change Given 기본 알림(전날 아침) When 시각을 바꾸면 Then 알림 설정은 다시 부르지 않는다")
    void changeDefaultReminderNotCopied() {
        // given
        listedEvent.putAll(Map.of("eventId", "e_1", "title", "면접", "start", "2026-11-24T10:00:00+09:00",
                "remind", true));
        AssistantTools t = assistantTools();
        t.scheduleList("2026-11-24", null);

        // when
        t.scheduleChange("e_1", "2026-11-24T14:00:00+09:00");

        // then
        assertThat(calls).noneMatch(c -> c.startsWith("schedule_set_reminder"));
        assertThat(t.done).singleElement()
                .satisfies(d -> assertThat(d.say()).isEqualTo("일정 '면접'을 11월 24일 화요일 오후 2시로 바꿨어요."));
    }
}
