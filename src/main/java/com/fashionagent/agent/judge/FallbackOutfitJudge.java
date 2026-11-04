package com.fashionagent.agent.judge;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;

/** GPT가 실패하거나 시간 안에 답하지 않으면 규칙으로 판단합니다. 사용자는 항상 답을 듣습니다. */
public class FallbackOutfitJudge implements OutfitJudge {

    private static final Logger log = LoggerFactory.getLogger(FallbackOutfitJudge.class);

    private final OutfitJudge primary;
    private final OutfitJudge fallback;
    private final ExecutorService executor;
    private final Duration timeout;

    public FallbackOutfitJudge(OutfitJudge primary, OutfitJudge fallback, ExecutorService executor,
            Duration timeout) {
        this.primary = primary;
        this.fallback = fallback;
        this.executor = executor;
        this.timeout = timeout;
    }

    @Override
    public AdviceJudgement advise(AdviceInput input) {
        return withFallback("추천", () -> primary.advise(input), () -> fallback.advise(input));
    }

    @Override
    public CheckJudgement check(CheckInput input) {
        return withFallback("확인", () -> primary.check(input), () -> fallback.check(input));
    }

    private <T> T withFallback(String mode, Supplier<T> first, Supplier<T> second) {
        var future = executor.submit(first::get);
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            return second.get();
        } catch (Exception e) {
            future.cancel(true);
            log.warn("GPT {} 모드 실패, 규칙으로 판단: {}", mode, e.toString());
            return second.get();
        }
    }
}
