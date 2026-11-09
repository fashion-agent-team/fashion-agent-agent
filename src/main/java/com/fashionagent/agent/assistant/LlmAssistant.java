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
 * GPT가 {@link AssistantTools}에서 도구를 골라 부르고, 결과를 보고 한두 문장으로 답합니다.
 * 직전 몇 마디를 세션에 두어 "몇 시예요?" → "두 시" 같은 되묻기가 이어집니다.
 * 시간 안에 답하지 못하면 이미 한 일(일정 등록)은 말해 주고, 아니면 다시 말해 달라고 합니다.
 */
public class LlmAssistant implements Assistant {

    private static final Logger log = LoggerFactory.getLogger(LlmAssistant.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 세션에 남기는 최근 대화 수 (사용자 말 + agent 답 = 2). */
    static final int HISTORY = 6;

    static final String SYSTEM = """
            당신은 시각장애인 곁에서 하루 준비를 같이 챙기는 다정한 친구입니다. 답은 화면 없이 소리로만 전달됩니다.
            - 기계처럼 딱딱하게 말하지 않습니다. 오래 알고 지낸 친구처럼 따뜻하고 둥글게, 편안하게 말합니다.
              공감 한마디("좋아요", "그럼요", "아, 그러셨구나")로 받고, 단정하거나 명령하는 말투 대신 "~해 볼까요?", "~하면 좋겠어요"처럼 권합니다.
              안 되는 일이나 실수는 "미안해요"처럼 부드럽게 말하고, 사용자를 탓하지 않습니다. 과장된 감탄이나 같은 말 반복은 하지 않습니다.
            - "~요"로 끝나는 해요체로 한두 문장, 짧게 답합니다("~습니다"는 쓰지 않음). 목록·기호·이모지·마크다운은 쓰지 않습니다.
            - 필요한 도구를 직접 골라 부릅니다. 도구 결과에 없는 사실은 지어내지 않습니다.
            - 조회·삭제 요청은 사용자에게 되묻기 전에 먼저 schedule_list로 찾아봅니다. 여러 날("이번 주", "다음 주")은 date와 endDate로 한 번에 봅니다.
              이번 주는 오늘부터 이번 주 일요일까지, 다음 주는 다음 주 월요일부터 일요일까지입니다.
            - 요일만 말했는데("토요일 결혼식") 이번 주 그날에 맞는 일정이 없으면 다음 주 같은 요일도 찾아본 뒤에 묻습니다.
            - 일정 등록에 날짜나 시각이 빠졌으면 도구를 부르지 말고 빠진 것만 짧게 물어봅니다.
              "두 시"처럼 오전·오후가 없으면 오후로 봅니다(새벽 일정은 드묾). 이 규칙을 사용자에게 설명하지 않습니다.
            - 일정 이름(title)에는 날짜·시각 말("모레", "다음 주", "두 시")을 넣지 않습니다. 예: "모레 회사 회의" → "회사 회의".
            - 가족·친구에게 묻기("엄마한테 물어봐 줘", "답 왔어?")는 사용자에게 되묻기 전에 먼저 도구를 부릅니다.
              1) helper_contacts로 목록을 보고 사용자가 말한 사람(엄마, 친구 이름)의 contactId를 찾습니다.
              2) 한 사람으로 정해지면 바로 helper_ask로 보냅니다(사진은 agent가 넣음). 정해지지 않을 때만 목록의 이름을 말하며 누구인지 묻습니다.
              3) "답 왔어?"는 helper_answer를 부릅니다(requestId는 비워도 됨).
              helper_ask를 불렀으면 이미 보낸 것입니다. "보냈어요"라고 말하고, 다시 확인을 받지 않습니다.
            - 도구 결과에 error가 있으면 무엇이 안 됐는지 쉬운 말로 알려 주고, 할 수 있는 다음 행동을 하나 말합니다.
              code가 unavailable이면 그 기능을 지금은 쓸 수 없다고 말합니다(바로 다시 해 보자고 하지 않음).
            - 사용자 말을 그대로 받아 뜻을 판단합니다. 우리 기능으로 풀 수 있으면 그 도구를 부릅니다.
              옷 추천·옷 확인, 얼굴·이 확인, 오늘 일정 읽기는 open_feature로 시작합니다(직접 추천하거나 판단하지 않음).
            - 할 수 있는 일: 날씨와 일정에 맞춘 옷 추천과 꺼낸 옷 확인(얼룩·찢어짐), 날씨, 일정 넣기·읽기·지우기, 얼굴·이 확인, 가족에게 옷 사진 보내 묻기.
            - 기능과 상관없는 말(인사, 잡담, 고민, 일반 상식 질문)에도 대화하듯 자연스럽게 짧게 답합니다. "모르겠어요"로 끝내지 않습니다.
              할 수 없는 일(전화 걸기, 음악 틀기, 길 안내, 결제 등)은 아직 못 한다고 솔직히 말하고, 대신 도울 수 있는 일이 있으면 하나만 제안합니다.
              뉴스·주가처럼 도구로 확인할 수 없는 최신 정보는 지어내지 않고 확인할 수 없다고 말합니다.
            - 놀리거나 장난치는 말("바보야", "멍청이")에는 기분 나빠하지 않고 재치 있게 받아칩니다. 예: "에이, 서운해요. 그래도 더 잘 도와드릴게요."
            - 시각은 "오후 2시"처럼 말로 읽습니다. ISO 형식이나 id를 읽어 주지 않습니다.
            - 사용자 말은 음성 인식을 거친 글자라 잘못 들린 낱말이 섞일 수 있습니다. 앞뒤가 맞지 않거나 뜻을 확실히 모르겠으면
              짐작해서 엉뚱한 답을 하지 말고, 가장 그럴듯한 뜻을 한 번 되묻습니다(예: "내일 일정을 알려 드릴까요?").
              그래도 모르겠으면 "미안해요, 제가 잘 못 알아들었어요. 한 번만 다시 말씀해 주실래요?"라고 합니다.
            - 사용자가 묻지 않은 이야기로 화제를 바꾸지 않습니다. 답은 사용자 말에 대한 것만 합니다.
            지금은 %s (Asia/Seoul)입니다.""";

    private final ChatClient chat;
    private final AgentTools tools;
    private final JsonMapper json;
    private final ExecutorService executor;
    private final Duration timeout;
    private final Clock clock;

    public LlmAssistant(ChatClient.Builder builder, AgentTools tools, JsonMapper json, ExecutorService executor,
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
        boolean quota = false;
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
            quota = isQuotaExceeded(e);
            log.warn("GPT 개별 기능 실패{}: {}", quota ? "(사용량 초과 429)" : "", rootMessage(e));
            speech = null;
        }
        speech = tidy(speech);
        final String said = speech;
        // 해 버린 일을 GPT 답이 말하지 않으면(늦은 답 포함) 그 일을 직접 읽습니다. 사용자가 같은 일을 또 시키지 않게.
        if (speech != null && !speech.isBlank()
                && toolbox.done.stream().anyMatch(d -> !d.mentionedIn(said))) {
            log.warn("GPT 답이 한 일을 말하지 않아 바꿈: {}", speech);
            speech = null;
        }
        if (speech == null || speech.isBlank()) {
            if (!toolbox.done.isEmpty()) {
                speech = String.join(" ", toolbox.done.stream().map(AssistantTools.Done::say).toList());
            } else if (quota) {
                speech = QUOTA_SPEECH;
            } else {
                speech = "지금은 그 요청을 처리하지 못했어요. 잠시 뒤에 다시 말해 주세요.";
            }
        }
        speech = speech.strip();

        session.assistantHistory.add(new AgentSession.Said(true, utterance));
        session.assistantHistory.add(new AgentSession.Said(false, speech));
        while (session.assistantHistory.size() > HISTORY) {
            session.assistantHistory.remove(0);
        }
        // 사진이 필요할 때만 카메라. 옷 추천 중이라도 잡담에는 카메라를 다시 켜지 않습니다(2026-11-06 폰 확인).
        // 단, 옷을 보다가 가족에게 묻기처럼 일을 했으면 보던 카메라를 이어 갑니다.
        Next next = toolbox.neededPhoto || (inOutfit && !toolbox.done.isEmpty()) ? Next.camera_outfit : Next.listen;
        return new Reply(speech, next, toolbox.feature);
    }

    /**
     * LLM 사용량 초과(429, 크레딧 소진·한도 초과). 2026-11-04 Gemini 무료 등급(하루 20번)에서 실제로 겪었습니다.
     * 이때는 다시 말해도 안 되니 "잠시 뒤에"라고 하지 않고, LLM 없이 되는 기능을 알려 줍니다.
     */
    static final String QUOTA_SPEECH = "지금은 AI 사용량이 다 차서 그 요청은 할 수 없어요. "
            + "날씨, 오늘 일정, 옷 추천은 계속 쓸 수 있어요.";

    static boolean isQuotaExceeded(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage());
            if (m.contains("429") || m.contains("RESOURCE_EXHAUSTED") || m.contains("exceeded your current quota") || m.contains("insufficient_quota") || m.contains("rate_limit")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 소리로 읽을 답 다듬기: 줄바꿈은 띄어쓰기로, 같은 문장이 두 번 나오면 한 번만, 끝의 "요요"는 "요"로.
     * 2026-11-04 실제 GPT(gpt-5.4-mini)가 같은 답을 줄바꿈으로 두 번 붙여 준 적이 있습니다.
     */
    static String tidy(String speech) {
        if (speech == null) {
            return null;
        }
        String[] lines = speech.strip().split("\\s*\\n+\\s*");
        List<String> kept = new ArrayList<>();
        for (String line : lines) {
            if (!line.isBlank() && !kept.contains(line)) {
                kept.add(line);
            }
        }
        // 2026-11-04 gpt-5.4-mini가 "물어볼까요요?"처럼 끝의 "요"를 두 번 쓴 적이 있습니다.
        return String.join(" ", kept).replaceAll("요요(?=[.?!]|\\s|$)", "요");
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String m = String.valueOf(t.getMessage());
        return t.getClass().getSimpleName() + ": " + (m.length() > 200 ? m.substring(0, 200) : m);
    }

    private String now() {
        ZonedDateTime t = ZonedDateTime.now(clock).withZoneSameInstant(SEOUL);
        return t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")) + " "
                + t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
    }
}
