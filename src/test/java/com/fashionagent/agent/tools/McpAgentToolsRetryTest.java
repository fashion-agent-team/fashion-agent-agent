package com.fashionagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;

/** 끊긴 MCP 호출을 다시 부를지(2026-11-20: 시간 초과 뒤 다시 불러 일정이 두 번 들어갈 수 있었음). */
class McpAgentToolsRetryTest {

    @Test
    void 시간_초과는_원인_어디에_있어도_시간_초과로_본다() {
        var wrapped = new RuntimeException("block", new IllegalStateException("x", new TimeoutException("20s")));
        assertThat(McpAgentTools.isTimeout(wrapped)).isTrue();
        assertThat(McpAgentTools.isTimeout(new RuntimeException(new java.net.http.HttpTimeoutException("t")))).isTrue();
        assertThat(McpAgentTools.isTimeout(new RuntimeException("connection reset"))).isFalse();
    }

    @Test
    void 읽기_도구는_끊기면_다시_부른다() {
        var reset = new RuntimeException(new java.io.IOException("connection reset"));
        assertThat(McpAgentTools.retryable(ToolNames.SCHEDULE_LIST, reset)).isTrue();
        assertThat(McpAgentTools.retryable(ToolNames.CLOTHES_INSPECT, reset)).isTrue();
        assertThat(McpAgentTools.retryable(ToolNames.WEATHER_TODAY, reset)).isTrue();
    }

    @Test
    void 쓰기_도구는_서버가_요청을_못_받은_게_확실할_때만_다시_부른다() {
        var reset = new RuntimeException(new java.io.IOException("connection reset"));
        assertThat(McpAgentTools.retryable(ToolNames.SCHEDULE_ADD, reset)).isFalse();
        assertThat(McpAgentTools.retryable(ToolNames.CLOSET_SAVE, reset)).isFalse();
        var stale = new RuntimeException(new McpTransportSessionNotFoundException("session gone"));
        assertThat(McpAgentTools.retryable(ToolNames.SCHEDULE_ADD, stale)).isTrue();
        var refused = new RuntimeException(new java.net.ConnectException("refused"));
        assertThat(McpAgentTools.retryable(ToolNames.SCHEDULE_DELETE, refused)).isTrue();
    }
}
