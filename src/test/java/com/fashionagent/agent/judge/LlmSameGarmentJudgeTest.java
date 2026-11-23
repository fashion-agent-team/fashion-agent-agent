package com.fashionagent.agent.judge;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

/** 같은 옷인지 GPT에게 묻는 판단. 진짜 GPT 대신 정해 둔 JSON을 돌려주는 가짜 모델로 본다. 틀리게 합치는 것보다 안 합치는 게 낫다. */
class LlmSameGarmentJudgeTest {

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private static final String A = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, 1});
    private static final String B = Base64.getEncoder().encodeToString(new byte[] {(byte) 0xFF, (byte) 0xD8, 2});

    @AfterEach
    void close() {
        executor.shutdownNow();
    }

    /** 정해 둔 답을 주고, 받은 프롬프트·사진 수를 남기는 가짜 모델. */
    static class Scripted implements ChatModel {
        final String answer;
        final long delayMs;
        final RuntimeException error;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> texts = new ArrayList<>();
        int media;

        Scripted(String answer, long delayMs, RuntimeException error) {
            this.answer = answer;
            this.delayMs = delayMs;
            this.error = error;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            prompt.getInstructions().stream().filter(m -> m instanceof UserMessage).map(m -> (UserMessage) m)
                    .forEach(u -> {
                        texts.add(u.getText());
                        media += u.getMedia().size();
                    });
            if (error != null) {
                throw error;
            }
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage(answer))));
        }

        @Override
        public ChatOptions getOptions() {
            return ChatOptions.builder().build();
        }
    }

    private LlmSameGarmentJudge judge(Scripted model, Duration timeout) {
        return new LlmSameGarmentJudge(ChatClient.builder(model), executor, timeout);
    }

    @Test
    @DisplayName("Given GPT가 same=true, 확신 0.9 When 비교 Then 합친다(true), 사진 두 장과 저장된 옷 설명을 보낸다")
    void sameConfident() {
        var model = new Scripted("{\"same\": true, \"confidence\": 0.9, \"reason\": \"단추와 칼라가 같음\"}", 0, null);

        assertThat(judge(model, Duration.ofSeconds(5)).same(A, B, "남색 줄무늬 셔츠")).isTrue();
        assertThat(model.media).isEqualTo(2);
        assertThat(model.texts.getFirst()).contains("저장된 옷 설명: 남색 줄무늬 셔츠");
    }

    @Test
    @DisplayName("Given 확신이 기준(0.7) 바로 위·아래 When 비교 Then 0.7 이상만 합친다")
    void confidenceThreshold() {
        var exactly = new Scripted("{\"same\": true, \"confidence\": 0.7, \"reason\": \"\"}", 0, null);
        var below = new Scripted("{\"same\": true, \"confidence\": 0.69, \"reason\": \"\"}", 0, null);

        assertThat(judge(exactly, Duration.ofSeconds(5)).same(A, B, "셔츠")).isTrue();
        assertThat(judge(below, Duration.ofSeconds(5)).same(A, B, "셔츠")).isFalse();
    }

    @Test
    @DisplayName("Given GPT가 same=false(확신 높음) When 비교 Then 합치지 않는다")
    void notSame() {
        var model = new Scripted("{\"same\": false, \"confidence\": 0.95, \"reason\": \"로고가 다름\"}", 0, null);

        assertThat(judge(model, Duration.ofSeconds(5)).same(A, B, null)).isFalse();
        assertThat(model.texts.getFirst()).contains("저장된 옷 설명: (없음)");
    }

    @Test
    @DisplayName("Given 사진 하나가 없음 When 비교 Then GPT를 부르지 않고 false")
    void missingPhoto() {
        var model = new Scripted("{\"same\": true, \"confidence\": 1}", 0, null);

        assertThat(judge(model, Duration.ofSeconds(5)).same(null, B, "셔츠")).isFalse();
        assertThat(judge(model, Duration.ofSeconds(5)).same(A, null, "셔츠")).isFalse();
        assertThat(model.calls).hasValue(0);
    }

    @Test
    @DisplayName("Given base64가 중간에 끊김 When 비교 Then 예외 없이 false, GPT를 부르지 않는다")
    void badBase64() {
        var model = new Scripted("{\"same\": true, \"confidence\": 1}", 0, null);

        assertThat(judge(model, Duration.ofSeconds(5)).same("QUJD\nR", B, "셔츠")).isFalse();
        assertThat(model.calls).hasValue(0);
    }

    @Test
    @DisplayName("Given GPT 오류·JSON 아닌 답·시간 초과 When 비교 Then 모두 false(합치지 않음)")
    void failuresAreFalse() {
        var error = new Scripted(null, 0, new IllegalStateException("429"));
        var garbage = new Scripted("같은 옷 같아요!", 0, null);
        var slow = new Scripted("{\"same\": true, \"confidence\": 1}", 2_000, null);

        assertThat(judge(error, Duration.ofSeconds(5)).same(A, B, "셔츠")).isFalse();
        assertThat(judge(garbage, Duration.ofSeconds(5)).same(A, B, "셔츠")).isFalse();
        long t = System.nanoTime();
        assertThat(judge(slow, Duration.ofMillis(100)).same(A, B, "셔츠")).isFalse();
        assertThat((System.nanoTime() - t) / 1_000_000).isLessThan(1_500);
    }
}
