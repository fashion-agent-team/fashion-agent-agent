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
    public AgentTools agentTools(AgentProperties props, JsonMapper json, java.time.Clock clock) {
        if ("mcp".equals(props.tools())) {
            log.info("도구: MCP 서버 {} (날씨·일정은 잠깐 기억)", props.mcp());
            return new CachingAgentTools(new McpAgentTools(props.mcp(), json), clock);
        }
        log.info("도구: 가짜 (agent.tools=fake)");
        return new FakeAgentTools(json);
    }
}
