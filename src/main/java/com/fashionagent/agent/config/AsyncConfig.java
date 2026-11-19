package com.fashionagent.agent.config;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AsyncConfig {

    /** 도구 동시 호출, GPT 시간 제한용. 가상 스레드라 개수 걱정 없음. */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
