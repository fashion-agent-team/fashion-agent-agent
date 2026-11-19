package com.fashionagent.agent.judge;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.MimeTypeUtils;

/** GPT에게 옷 사진 두 장을 보여 같은 옷인지 묻습니다. 실패·시간 초과·확신 부족이면 false(합치지 않음). */
public class LlmSameGarmentJudge implements SameGarmentJudge {

    private static final Logger log = LoggerFactory.getLogger(LlmSameGarmentJudge.class);

    /** 이 확신도 이상일 때만 합칩니다(잘못 합치면 옷 설명이 틀려져 중복보다 나쁨). */
    static final double MIN_CONFIDENCE = 0.7;

    static final String PROMPT = """
            두 사진은 같은 사람의 옷장에서 나온 옷 사진 조각입니다. 첫 번째는 방금 찍은 옷, 두 번째는 옷장에 저장된 옷입니다.
            저장된 옷 설명: %s
            조명·각도·접힘은 달라도 됩니다. 색, 무늬, 단추·지퍼·주머니·칼라·로고 같은 디테일을 비교해서 두 사진이 같은 옷(같은 물건)인지 판단하세요.
            비슷한 다른 옷일 가능성이 있으면 same=false로 하세요.
            JSON으로만 답하세요: {"same": true|false, "confidence": 0~1, "reason": "한 문장"}""";

    public record Verdict(boolean same, double confidence, String reason) {
    }

    private final ChatClient chat;
    private final ExecutorService executor;
    private final Duration timeout;

    public LlmSameGarmentJudge(ChatClient.Builder builder, ExecutorService executor, Duration timeout) {
        this.chat = builder.build();
        this.executor = executor;
        this.timeout = timeout;
    }

    @Override
    public boolean same(String now, String closet, String description) {
        if (now == null || closet == null) {
            return false;
        }
        try {
            byte[] a = Base64.getMimeDecoder().decode(now);
            byte[] b = Base64.getMimeDecoder().decode(closet);
            Verdict v = CompletableFuture.supplyAsync(() -> chat.prompt()
                            .user(u -> u.text(PROMPT.formatted(description == null ? "(없음)" : description))
                                    .media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(a))
                                    .media(MimeTypeUtils.IMAGE_JPEG, new ByteArrayResource(b)))
                            .call()
                            .entity(Verdict.class), executor)
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            boolean yes = v != null && v.same() && v.confidence() >= MIN_CONFIDENCE;
            log.info("같은 옷 GPT 확인: {} ({}, {})", yes, v == null ? null : v.confidence(), v == null ? null : v.reason());
            return yes;
        } catch (Exception e) {
            log.warn("같은 옷 GPT 확인 실패(합치지 않음): {}", e.toString());
            return false;
        }
    }
}
