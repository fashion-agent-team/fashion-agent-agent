package com.fashionagent.agent.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fashionagent.agent.config.AgentProperties;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class ToolsConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolsConfig.class);

    @Bean
    public AgentTools agentTools(AgentProperties props, JsonMapper json, java.time.Clock clock,
            @org.springframework.beans.factory.annotation.Value("${MCP_SHARED_KEY:}") String mcpKey) {
        if ("mcp".equals(props.tools())) {
            log.info("도구: MCP 서버 {} (날씨·일정은 잠깐 기억, 내부 호출 키 {})", props.mcp(),
                    mcpKey.isBlank() ? "없음" : "있음");
            return new CachingAgentTools(new McpAgentTools(props.mcp(), json, mcpKey), clock);
        }
        log.info("도구: 가짜 (agent.tools=fake)");
        return new FakeAgentTools(json);
    }
}
