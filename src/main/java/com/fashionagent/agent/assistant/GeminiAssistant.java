package com.fashionagent.agent.assistant;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.tools.AgentTools;

import tools.jackson.databind.json.JsonMapper;

/**
 * Gemini가 {@link AssistantTools}에서 도구를 골라 부르고, 결과를 보고 한두 문장으로 답합니다.
 * 직전 몇 마디를 세션에 두어 "몇 시예요?" → "두 시" 같은 되묻기가 이어집니다.
 * 시간 안에 답하지 못하면 이미 한 일(일정 등록)은 말해 주고, 아니면 다시 말해 달라고 합니다.
 */
public class GeminiAssistant implements Assistant {

    private static final Logger log = LoggerFactory.getLogger(GeminiAssistant.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 세션에 남기는 최근 대화 수 (사용자 말 + agent 답 = 2). */
    static final int HISTORY = 6;

    static final String SYSTEM = """
            당신은 시각장애인을 돕는 음성 비서입니다. 답은 화면 없이 소리로만 전달됩니다.
            - 한국어 존댓말로 한두 문장, 짧게 답합니다. 목록·기호·이모지·마크다운은 쓰지 않습니다.
            - 필요한 도구를 직접 골라 부릅니다. 도구 결과에 없는 사실은 지어내지 않습니다.
            - 일정 등록에 날짜나 시각이 빠졌으면 도구를 부르지 말고 그것만 물어봅니다. "두 시"처럼 오전·오후가 없으면 오후로 봅니다(새벽 일정은 드묾).
            - 가족·친구에게 묻기는 사용자가 분명히 원할 때만 합니다. 누구에게 보낼지 모르면 helper_contacts로 목록을 보고 물어봅니다.
            - 도구 결과에 error가 있으면 무엇이 안 됐는지 쉬운 말로 알려 주고, 할 수 있는 다음 행동을 하나 말합니다.
            - 옷 추천("오늘 뭐 입지?")과 얼굴 확인은 다른 흐름이 처리합니다. 그런 요청이면 그렇게 말해 보라고 안내합니다.
            - 시각은 "오후 2시"처럼 말로 읽습니다. ISO 형식이나 id를 읽어 주지 않습니다.
            지금은 %s (Asia/Seoul)입니다.""";

    private final ChatClient chat;
    private final AgentTools tools;
    private final JsonMapper json;
    private final ExecutorService executor;
    private final Duration timeout;
    private final Clock clock;

    public GeminiAssistant(ChatClient.Builder builder, AgentTools tools, JsonMapper json, ExecutorService executor,
            Duration timeout, Clock clock) {
        this.chat = builder.build();
        this.tools = tools;
        this.json = json;
        this.executor = executor;
        this.timeout = timeout;
        this.clock = clock;
    }

    @Override
    public Reply reply(AgentSession session, String utterance, Location location, boolean inOutfit) {
        AssistantTools toolbox = new AssistantTools(tools, session, location, session.photo(clock), json);
        List<Message> messages = new ArrayList<>();
        for (AgentSession.Said said : session.assistantHistory) {
            messages.add(said.user() ? new UserMessage(said.text()) : new AssistantMessage(said.text()));
        }
        messages.add(new UserMessage(utterance));

        String speech;
        var future = executor.submit(() -> chat.prompt()
                .system(SYSTEM.formatted(now()))
                .messages(messages)
                .tools(toolbox)
                .call()
                .content());
        try {
            speech = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            speech = null;
        } catch (Exception e) {
            future.cancel(true);
            log.warn("Gemini 개별 기능 실패: {}", e.toString());
            speech = null;
        }
        if (speech == null || speech.isBlank()) {
            speech = toolbox.addedSummary != null ? toolbox.addedSummary
                    : "지금은 그 요청을 처리하지 못했어요. 잠시 뒤에 다시 말해 주세요.";
        }
        speech = speech.strip();

        session.assistantHistory.add(new AgentSession.Said(true, utterance));
        session.assistantHistory.add(new AgentSession.Said(false, speech));
        while (session.assistantHistory.size() > HISTORY) {
            session.assistantHistory.remove(0);
        }
        Next next = toolbox.neededPhoto || inOutfit ? Next.camera_outfit : Next.listen;
        return new Reply(speech, next);
    }

    private String now() {
        ZonedDateTime t = ZonedDateTime.now(clock).withZoneSameInstant(SEOUL);
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " "
                + t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
    }
}
