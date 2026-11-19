package com.fashionagent.agent.session;

import java.util.Optional;

/** 대화 상태 저장소. 키에 userId를 넣어서 남의 세션을 이어 쓰지 못하게 합니다. */
public interface SessionStore {

    Optional<AgentSession> find(String userId, String sessionId);

    void save(AgentSession session);

    static String key(String userId, String sessionId) {
        return "agent:session:" + userId + ":" + sessionId;
    }
}
