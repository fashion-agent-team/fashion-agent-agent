package com.fashionagent.agent.api;

/** POST /api/v1/agent/warm 본문. 위치는 없어도 됩니다(없으면 서울, 첫 차례와 같은 규칙). */
public record WarmRequest(Location location) {
}
