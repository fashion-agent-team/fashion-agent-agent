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
        if (!props.usesLlm()) {
            log.info("판단: 규칙 (agent.judge=rule)");
            return rules;
        }
        ChatClient.Builder builder = chat.getIfAvailable();
        if (builder == null) {
            log.warn("agent.judge=openai인데 GPT 설정이 없어 규칙으로 판단합니다. OPENAI_API_KEY를 확인하세요.");
            return rules;
        }
        log.info("판단: GPT (실패하면 규칙)");
        LlmOutfitJudge llm = new LlmOutfitJudge(builder, json);
        if (props.gptWarm()) {
            llm.startWarming();
        }
        return new FallbackOutfitJudge(llm, rules, agentExecutor,
                props.judgeTimeout());
    }

    /** 옷장 서버가 애매하다고 한 같은 옷을 사진으로 확인. GPT가 없으면 확인하지 않음(합치지 않음). */
    @Bean
    public SameGarmentJudge sameGarmentJudge(AgentProperties props, ObjectProvider<ChatClient.Builder> chat,
            ExecutorService agentExecutor) {
        ChatClient.Builder builder = props.usesLlm() ? chat.getIfAvailable() : null;
        return builder == null ? SameGarmentJudge.NONE
                : new LlmSameGarmentJudge(builder, agentExecutor, props.judgeTimeout());
    }
}
