package com.fashionagent.agent.assistant;

import java.time.Clock;
import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fashionagent.agent.config.AgentProperties;
import com.fashionagent.agent.tools.AgentTools;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class AssistantConfig {

    private static final Logger log = LoggerFactory.getLogger(AssistantConfig.class);

    /** 판단을 Gemini로 할 때(agent.judge=gemini) 개별 기능도 Gemini가 도구를 고릅니다. */
    @Bean
    public Assistant assistant(AgentProperties props, ObjectProvider<ChatClient.Builder> chat, AgentTools tools,
            JsonMapper json, ExecutorService agentExecutor, Clock clock) {
        ChatClient.Builder builder = "gemini".equals(props.judge()) ? chat.getIfAvailable() : null;
        if (builder == null) {
            log.info("개별 기능: 키워드만 (Gemini 꺼짐)");
            return new NoAssistant();
        }
        log.info("개별 기능: Gemini 도구 선택");
        return new GeminiAssistant(builder, tools, json, agentExecutor, props.judgeTimeout(), clock);
    }
}
