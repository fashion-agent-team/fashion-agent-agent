package com.fashionagent.agent.assistant;

import java.time.Clock;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.flow.SmallTalk;
import com.fashionagent.agent.session.AgentSession;

/**
 * GPT가 꺼져 있을 때 (agent.judge=rule). 키워드로 정해진 기능과 인사·시간 같은 짧은 말만 됩니다.
 * 그 밖의 말은 못 한다고 말하고 할 수 있는 일을 알려 줍니다.
 */
public class NoAssistant implements Assistant {

    private final Clock clock;

    public NoAssistant() {
        this(Clock.systemDefaultZone());
    }

    public NoAssistant(Clock clock) {
        this.clock = clock;
    }

    @Override
    public Reply reply(AgentSession session, String utterance, Location location, boolean inOutfit) {
        String talk = SmallTalk.reply(utterance, clock);
        // 옷과 상관없는 말에는 카메라를 다시 켜지 않습니다(2026-11-06 폰 확인).
        return new Reply(talk != null ? talk : SmallTalk.UNKNOWN, Next.listen);
    }
}
