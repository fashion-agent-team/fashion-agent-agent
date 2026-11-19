package com.fashionagent.agent.tools;

import java.util.Map;

import tools.jackson.databind.JsonNode;

/**
 * MCP 도구를 부르는 곳. 구현은 둘입니다: 실제 MCP 서버를 부르는 {@link McpAgentTools},
 * 서버 없이 개발할 때 쓰는 {@link FakeAgentTools}.
 *
 * <p>userId는 반드시 {@link #callForUser}로 넣습니다. GPT나 앱이 준 값이 아니라 게이트웨이가 확인한
 * X-User-Id로 덮어써서 남의 옷장·일정을 읽지 못하게 합니다(약속 3절).
 */
public interface AgentTools {

    /** 도구 결과 JSON. 실패하면 {@link ToolException}. */
    JsonNode call(String tool, Map<String, Object> args);

    default JsonNode callForUser(String tool, String userId, Map<String, Object> args) {
        var scoped = new java.util.HashMap<>(args);
        scoped.put("userId", userId);
        return call(tool, scoped);
    }
}
