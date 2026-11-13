package com.fashionagent.agent.config;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * agent 설정. 기본값은 "서버 없이 혼자 실행" (가짜 도구, 규칙 판단, 메모리 세션)입니다.
 *
 * @param tools        fake: 가짜 도구 / mcp: 실제 MCP 서버 호출
 * @param judge        rule: 규칙으로 판단 / openai: GPT로 판단 (실패하면 규칙으로)
 * @param session      memory: 서버 메모리 / redis: Redis (여러 대 띄울 때)
 * @param closetSearch 옷 추천 1단계에서 closet_search를 부를지 (옷장에 옷이 쌓인 뒤 켬)
 * @param judgeTimeout GPT 답을 기다리는 시간 (약속: 25초)
 * @param sessionTtl   대화 상태 보관 시간 (약속: 30분)
 * @param mcp          MCP 서버 이름 → 주소 (예: weather → http://localhost:8091/mcp)
 * @param gptWarm      GPT 연결을 데워 둘지(켜질 때 + 대화 뒤 30분 동안 4분마다 짧은 호출, 2026-11-13 응답 시간)
 */
@ConfigurationProperties("agent")
public record AgentProperties(
        @DefaultValue("fake") String tools,
        @DefaultValue("rule") String judge,
        @DefaultValue("memory") String session,
        @DefaultValue("false") boolean closetSearch,
        @DefaultValue("25s") Duration judgeTimeout,
        @DefaultValue("30m") Duration sessionTtl,
        Map<String, String> mcp,
        @DefaultValue("true") boolean gptWarm) {

    public AgentProperties {
        mcp = mcp == null ? Map.of() : Map.copyOf(mcp);
    }

    /** 판단·개별 기능을 LLM(GPT)으로 하는지. */
    public boolean usesLlm() {
        return "openai".equals(judge);
    }
}
