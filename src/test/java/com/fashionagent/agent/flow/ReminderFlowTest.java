package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.InMemorySessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.FakeAgentTools;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** 일정을 넣은 뒤 "일정 전에 알림을 드릴까요?" → "며칠 전에?" → schedule_set_reminder. */
class ReminderFlowTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final List<String> calls = new ArrayList<>();
    private final AgentTools tools = new AgentTools() {
        final FakeAgentTools fake = new FakeAgentTools(json);

        @Override
        public JsonNode call(String tool, Map<String, Object> args) {
            calls.add(tool + " " + new TreeMap<>(args));
            return fake.call(tool, args);
        }
    };
    private final ReminderFlow flow = new ReminderFlow(tools,
            new InMemorySessionStore(Duration.ofMinutes(30), Clock.systemUTC()));

    private AgentSession justAdded() {
        AgentSession s = new AgentSession("s1", "u1");
        s.reminderStep = ReminderFlow.PENDING;
        s.reminderEventId = "e_1";
        s.reminderTitle = "친구 결혼식";
        return s;
    }

    @Test
    void 등록_답_뒤에_알림을_묻는다() {
        AgentSession s = justAdded();
        String said = flow.askIfAdded(s, "11월 14일 토요일 오후 2시, 친구 결혼식을 등록했어요.");
        assertThat(said).isEqualTo("11월 14일 토요일 오후 2시, 친구 결혼식을 등록했어요. 일정 전에 알림을 드릴까요?");
        assertThat(s.reminderStep).isEqualTo("want");

        // 일정을 넣지 않았으면 그대로
        AgentSession other = new AgentSession("s2", "u1");
        assertThat(flow.askIfAdded(other, "몇 시예요?")).isEqualTo("몇 시예요?");
    }

    @Test
    void 응_그리고_이틀_전() {
        AgentSession s = justAdded();
        flow.askIfAdded(s, "등록했어요.");

        TurnResponse ask = flow.answer(s, "응");
        assertThat(ask.speech()).startsWith("며칠 전에 미리 알려 드릴까요?");
        assertThat(ask.next()).isEqualTo(Next.listen);
        assertThat(calls).isEmpty();

        TurnResponse done = flow.answer(s, "이틀 전에 알려 줘");
        assertThat(done.speech()).isEqualTo("좋아요, 친구 결혼식 이틀 전 저녁 8시와 당일 아침에 알려 드릴게요.");
        assertThat(done.result().type()).isEqualTo("schedule_reminder");
        assertThat(calls).containsExactly("schedule_set_reminder {daysBefore=2, eventId=e_1, remind=true, userId=u1}");
        assertThat(s.reminderStep).isNull();
        // 끝난 뒤의 말은 원래 대화로
        assertThat(flow.answer(s, "고마워")).isNull();
    }

    @Test
    void 아니면_알림_없이_저장() {
        AgentSession s = justAdded();
        flow.askIfAdded(s, "등록했어요.");
        TurnResponse r = flow.answer(s, "아니 괜찮아");
        assertThat(r.speech()).isEqualTo("알겠어요, 친구 결혼식은 알림 없이 둘게요.");
        assertThat(calls).containsExactly("schedule_set_reminder {eventId=e_1, remind=false, userId=u1}");
    }

    @Test
    void 한_번에_날까지_말하면_바로_저장() {
        AgentSession s = justAdded();
        flow.askIfAdded(s, "등록했어요.");
        TurnResponse r = flow.answer(s, "응 일정 3일 전에 알려줘");
        assertThat(r.speech()).contains("사흘 전 저녁 8시");
        assertThat(calls).containsExactly("schedule_set_reminder {daysBefore=3, eventId=e_1, remind=true, userId=u1}");
    }

    @Test
    void 며칠인지_못_알아들으면_한_번_더_묻고_그다음엔_전날() {
        AgentSession s = justAdded();
        flow.askIfAdded(s, "등록했어요.");
        flow.answer(s, "네");
        TurnResponse again = flow.answer(s, "음 글쎄");
        assertThat(again.speech()).contains("다시 말씀해 주실래요?");
        assertThat(calls).isEmpty();
        TurnResponse r = flow.answer(s, "음 글쎄");
        assertThat(r.speech()).contains("전날 저녁 8시");
        assertThat(calls).containsExactly("schedule_set_reminder {daysBefore=1, eventId=e_1, remind=true, userId=u1}");
    }

    @Test
    void 다른_기능을_말하면_묻기를_그만둔다() {
        AgentSession s = justAdded();
        flow.askIfAdded(s, "등록했어요.");
        assertThat(flow.answer(s, "오늘 날씨 어때?")).isNull();
        assertThat(s.reminderStep).isNull();
        assertThat(calls).isEmpty();
    }

    @Test
    void 날_수_읽기() {
        assertThat(ReminderFlow.parseDays("당일아침에만")).isZero();
        assertThat(ReminderFlow.parseDays("전날")).isEqualTo(1);
        assertThat(ReminderFlow.parseDays("하루전에")).isEqualTo(1);
        assertThat(ReminderFlow.parseDays("이틀전")).isEqualTo(2);
        assertThat(ReminderFlow.parseDays("5일전")).isEqualTo(5);
        assertThat(ReminderFlow.parseDays("일주일전에")).isEqualTo(7);
        assertThat(ReminderFlow.parseDays("2주전")).isEqualTo(14);
        assertThat(ReminderFlow.parseDays("30일전")).isNull();
        assertThat(ReminderFlow.parseDays("글쎄")).isNull();
        assertThat(ReminderFlow.confirm(true, 0, "면접")).isEqualTo("좋아요, 면접 당일 아침에 알려 드릴게요.");
        assertThat(ReminderFlow.confirm(true, 7, "면접")).contains("일주일 전 저녁 8시");
    }
}
