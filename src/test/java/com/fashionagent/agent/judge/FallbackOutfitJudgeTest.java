package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;

/** GPT 판단이 실패하거나 늦으면 규칙 판단으로 넘어가는지. 사용자는 항상 답을 들어야 한다. */
class FallbackOutfitJudgeTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AdviceJudgement gptAdvice = mock(AdviceJudgement.class);
    private final AdviceJudgement ruleAdvice = mock(AdviceJudgement.class);
    private final CheckJudgement gptCheck = mock(CheckJudgement.class);
    private final CheckJudgement ruleCheck = mock(CheckJudgement.class);
    private final AtomicInteger ruleCalls = new AtomicInteger();

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    /** 규칙 판단(가짜): 부른 횟수를 센다. */
    private final OutfitJudge rule = new OutfitJudge() {
        @Override
        public AdviceJudgement advise(AdviceInput input) {
            ruleCalls.incrementAndGet();
            return ruleAdvice;
        }

        @Override
        public CheckJudgement check(CheckInput input) {
            ruleCalls.incrementAndGet();
            return ruleCheck;
        }
    };

    private static OutfitJudge gpt(Runnable before, AdviceJudgement advice, CheckJudgement check) {
        return new OutfitJudge() {
            @Override
            public AdviceJudgement advise(AdviceInput input) {
                before.run();
                return advice;
            }

            @Override
            public CheckJudgement check(CheckInput input) {
                before.run();
                return check;
            }
        };
    }

    private FallbackOutfitJudge judge(OutfitJudge primary, Duration timeout) {
        return new FallbackOutfitJudge(primary, rule, executor, timeout);
    }

    @Test
    @DisplayName("Given GPT가 제때 답함 When 추천·확인 Then GPT 결과를 쓰고 규칙은 부르지 않는다")
    void usesGptWhenOk() {
        var j = judge(gpt(() -> { }, gptAdvice, gptCheck), Duration.ofSeconds(5));

        assertThat(j.advise(null)).isSameAs(gptAdvice);
        assertThat(j.check(null)).isSameAs(gptCheck);
        assertThat(ruleCalls).hasValue(0);
    }

    @Test
    @DisplayName("Given GPT가 예외(할당량 초과 등) When 추천·확인 Then 규칙 결과")
    void fallsBackOnError() {
        var j = judge(gpt(() -> {
            throw new IllegalStateException("429 insufficient_quota");
        }, gptAdvice, gptCheck), Duration.ofSeconds(5));

        assertThat(j.advise(null)).isSameAs(ruleAdvice);
        assertThat(j.check(null)).isSameAs(ruleCheck);
        assertThat(ruleCalls).hasValue(2);
    }

    @Test
    @DisplayName("Given GPT가 시간 제한보다 늦음 When 추천 Then 제한 시간 안에 규칙 결과를 주고 GPT 작업은 끊는다")
    void fallsBackOnTimeoutAndCancels() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        CountDownLatch finished = new CountDownLatch(1);
        var j = judge(gpt(() -> {
            started.countDown();
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            } finally {
                finished.countDown();
            }
        }, gptAdvice, gptCheck), Duration.ofMillis(100));

        long t = System.nanoTime();
        AdviceJudgement result = j.advise(null);
        long ms = (System.nanoTime() - t) / 1_000_000;

        assertThat(result).isSameAs(ruleAdvice);
        assertThat(ms).isLessThan(3_000);
        assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(interrupted).isTrue();
    }

    @Test
    @DisplayName("Given GPT가 null을 돌려줌 When 추천 Then null 그대로(실패로 보지 않음)")
    void nullIsNotFailure() {
        var j = judge(gpt(() -> { }, null, null), Duration.ofSeconds(5));

        assertThat(j.advise(null)).isNull();
        assertThat(ruleCalls).hasValue(0);
    }

    @Test
    @DisplayName("Given 기다리는 스레드가 interrupt됨 When 추천 Then 규칙 결과를 주고 interrupt 표시는 남긴다")
    void interruptedCaller() {
        var j = judge(gpt(() -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException ignored) {
                // 끊김
            }
        }, gptAdvice, gptCheck), Duration.ofSeconds(5));

        Thread.currentThread().interrupt();
        try {
            assertThat(j.advise(null)).isSameAs(ruleAdvice);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    @DisplayName("warmUp은 GPT 쪽만 데운다")
    void warmUpPrimaryOnly() {
        AtomicInteger warmed = new AtomicInteger();
        OutfitJudge primary = new OutfitJudge() {
            @Override
            public AdviceJudgement advise(AdviceInput input) {
                return gptAdvice;
            }

            @Override
            public CheckJudgement check(CheckInput input) {
                return gptCheck;
            }

            @Override
            public void warmUp() {
                warmed.incrementAndGet();
            }
        };

        judge(primary, Duration.ofSeconds(1)).warmUp();

        assertThat(warmed).hasValue(1);
        assertThat(ruleCalls).hasValue(0);
    }
}
