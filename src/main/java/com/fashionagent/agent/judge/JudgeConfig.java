package com.fashionagent.agent.judge;

import java.util.concurrent.ExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fashionagent.agent.config.AgentProperties;

import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JudgeConfig {

    private static final Logger log = LoggerFactory.getLogger(JudgeConfig.class);

    @Bean
    public OutfitJudge outfitJudge(AgentProperties props, ObjectProvider<ChatClient.Builder> chat, JsonMapper json,
            ExecutorService agentExecutor) {
        RuleOutfitJudge rules = new RuleOutfitJudge();
        if (!"gemini".equals(props.judge())) {
            log.info("판단: 규칙 (agent.judge=rule)");
            return rules;
        }
        ChatClient.Builder builder = chat.getIfAvailable();
        if (builder == null) {
            log.warn("agent.judge=gemini인데 Gemini 설정이 없어 규칙으로 판단합니다. GEMINI_API_KEY를 확인하세요.");
            return rules;
        }
        log.info("판단: Gemini (실패하면 규칙)");
        return new FallbackOutfitJudge(new GeminiOutfitJudge(builder, json), rules, agentExecutor,
                props.judgeTimeout());
    }
}
