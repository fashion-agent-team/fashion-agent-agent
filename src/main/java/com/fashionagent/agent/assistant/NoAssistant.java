package com.fashionagent.agent.assistant;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.session.AgentSession;

/** Gemini가 꺼져 있을 때 (agent.judge=rule). 키워드로 정해진 기능만 됩니다. */
public class NoAssistant implements Assistant {

    @Override
    public Reply reply(AgentSession session, String utterance, Location location, boolean inOutfit) {
        return new Reply("아직 그 말은 잘 모르겠어요. \"오늘 뭐 입지?\"라고 말해 보세요.",
                inOutfit ? Next.camera_outfit : Next.listen);
    }
}
