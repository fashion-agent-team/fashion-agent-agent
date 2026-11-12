package com.fashionagent.agent.assistant;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.session.AgentSession;

/**
 * 개별 기능(일정 등록·삭제, 다른 날 일정 등). 옷 추천 흐름과 섞지 않습니다(약속 5-2절).
 * GPT가 도구를 고릅니다. GPT가 없으면 {@link NoAssistant}가 안내만 합니다.
 */
public interface Assistant {

    Reply reply(AgentSession session, String utterance, Location location, boolean inOutfit);

    /** feature: GPT가 연 기능(outfit, face, teeth, schedule_today). 있으면 agent가 그 흐름을 시작하고 speech는 쓰지 않습니다. */
    record Reply(String speech, Next next, String feature) {
        public Reply(String speech, Next next) {
            this(speech, next, null);
        }
    }
}
