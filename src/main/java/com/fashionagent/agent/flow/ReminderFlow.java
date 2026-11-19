package com.fashionagent.agent.flow;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.api.TurnResponse.Result;
import com.fashionagent.agent.judge.Korean;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

/**
 * 일정을 넣은 뒤 폰 알림을 물어봅니다(2026-11-11 진행 님).
 *
 * <ol>
 * <li>schedule_add가 성공하면 GPT 답 뒤에 "일정 전에 알림을 드릴까요?"를 붙입니다.</li>
 * <li>"응"이면 "며칠 전에 미리 알려 드릴까요?", "아니"면 알림 없이 저장합니다.</li>
 * <li>"이틀 전"처럼 답하면 schedule_set_reminder로 저장합니다. 앱이 그 값으로 폰에 알림을 예약합니다.</li>
 * </ol>
 * 답을 못 알아들으면 한 번 더 묻고, 그래도 모르면 전날 저녁으로 정합니다. 다른 기능을 말하면 묻기를 그만두고 그 말을 처리합니다.
 */
@Component
public class ReminderFlow {

    /** schedule_add가 방금 성공해서 이번 답 뒤에 물어야 함. */
    public static final String PENDING = "pending";
    /** "일정 전에 알림을 드릴까요?"를 물음. */
    static final String WANT = "want";
    /** "며칠 전에 미리 알려 드릴까요?"를 물음. */
    static final String DAYS = "days";

    static final String ASK_WANT = "일정 전에 알림을 드릴까요?";
    static final String ASK_DAYS = "며칠 전에 미리 알려 드릴까요? 하루 전, 이틀 전처럼 말씀해 주세요. 당일 아침에만 알려 드릴 수도 있어요.";
    static final String ASK_DAYS_AGAIN = "미안해요, 잘 못 알아들었어요. 며칠 전에 알려 드릴지 \"이틀 전\"처럼 다시 말씀해 주실래요?";
    static final int MAX_DAYS = 14;

    private static final List<String> YES = List.of("네", "응", "좋아", "그래", "알려", "해줘", "부탁", "예", "웅", "넵");
    private static final List<String> NO = List.of("아니", "괜찮", "됐어", "필요없", "안해", "안알려", "싫", "없어도");

    private final AgentTools tools;
    private final SessionStore sessions;

    public ReminderFlow(AgentTools tools, SessionStore sessions) {
        this.tools = tools;
        this.sessions = sessions;
    }

    /** 방금 일정을 넣었으면 답 뒤에 알림을 물을 문장을 붙입니다. 아니면 그대로. */
    public String askIfAdded(AgentSession session, String speech) {
        if (!PENDING.equals(session.reminderStep) || session.reminderEventId == null) {
            return speech;
        }
        session.reminderStep = WANT;
        session.reminderAsked = 0;
        String said = speech == null ? "" : speech.strip();
        return said.isEmpty() ? ASK_WANT : said + " " + ASK_WANT;
    }

    /** 알림 질문에 대한 답. 답이 아니면(다른 기능 등) 묻기를 그만두고 null(원래 대화로). */
    public TurnResponse answer(AgentSession session, String utterance) {
        String step = session.reminderStep;
        if (!WANT.equals(step) && !DAYS.equals(step)) {
            return null;
        }
        String s = compact(utterance);
        if (s.isEmpty()) {
            clear(session);
            return null;
        }
        // "응, 이틀 전에"처럼 날을 바로 말하면 두 번째 질문 없이 저장합니다. "일정 이틀 전에 알려 줘"도 답으로 봅니다.
        Integer days = parseDays(s);
        if (days != null) {
            return save(session, true, days);
        }
        if (NO.stream().anyMatch(s::startsWith) || (WANT.equals(step) && NO.stream().anyMatch(s::contains))) {
            return save(session, false, null);
        }
        if (otherFeature(utterance)) {
            // "아니 그냥 날씨 어때?"처럼 다른 기능을 말하면 답이 아니라 새 요청입니다.
            clear(session);
            return null;
        }
        if (WANT.equals(step)) {
            if (YES.stream().anyMatch(s::startsWith) || s.contains("알려줘") || s.contains("알림")) {
                session.reminderStep = DAYS;
                session.reminderAsked = 0;
                return reply(session, ASK_DAYS, Next.listen);
            }
            clear(session);
            return null;
        }
        // 며칠 전인지 못 알아들음: 한 번 더 묻고, 그래도 모르면 전날 저녁으로.
        if (session.reminderAsked == 0) {
            session.reminderAsked = 1;
            return reply(session, ASK_DAYS_AGAIN, Next.listen);
        }
        return save(session, true, 1);
    }

    private TurnResponse save(AgentSession session, boolean remind, Integer days) {
        Map<String, Object> args = new HashMap<>();
        args.put("eventId", session.reminderEventId);
        args.put("remind", remind);
        if (days != null) {
            args.put("daysBefore", days);
        }
        String title = session.reminderTitle;
        clear(session);
        try {
            tools.callForUser(ToolNames.SCHEDULE_SET_REMINDER, session.userId, args);
        } catch (ToolException e) {
            return reply(session, "미안해요, 알림 설정을 저장하지 못했어요. 일정은 잘 등록돼 있어요.", Next.listen);
        }
        return reply(session, confirm(remind, days, title), Next.listen);
    }

    static String confirm(boolean remind, Integer days, String title) {
        String what = title == null || title.isBlank() ? "이 일정" : title;
        if (!remind) {
            return "알겠어요, " + Korean.josa(what, "은", "는") + " 알림 없이 둘게요.";
        }
        int d = days == null ? 1 : days;
        if (d == 0) {
            return "좋아요, " + what + " 당일 아침에 알려 드릴게요.";
        }
        String before = d == 1 ? "전날" : spokenDays(d) + " 전";
        return "좋아요, " + what + " " + before + " 저녁 8시와 당일 아침에 알려 드릴게요.";
    }

    /** 2 → 이틀, 7 → 일주일, 그 밖은 "5일". */
    static String spokenDays(int d) {
        return switch (d) {
            case 1 -> "하루";
            case 2 -> "이틀";
            case 3 -> "사흘";
            case 4 -> "나흘";
            case 7 -> "일주일";
            case 14 -> "2주";
            default -> d + "일";
        };
    }

    private static final Pattern DIGITS = Pattern.compile("(\\d{1,2})(일|주)");

    /** "이틀 전", "3일 전", "일주일 전", "전날", "당일만" → 날 수. 못 알아들으면 null. 0은 당일 아침만. */
    static Integer parseDays(String compact) {
        String s = compact;
        if (s.isEmpty()) {
            return null;
        }
        Matcher m = DIGITS.matcher(s);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            int d = "주".equals(m.group(2)) ? n * 7 : n;
            return d <= MAX_DAYS ? d : null;
        }
        if (s.contains("당일") || s.contains("그날") || s.contains("아침에만") || s.contains("당일만")) {
            return 0;
        }
        if (s.contains("열흘")) {
            return 10;
        }
        if (s.contains("이주") || s.contains("두주")) {
            return 14;
        }
        if (s.contains("일주") || s.contains("한주") || s.contains("일주일")) {
            return 7;
        }
        if (s.contains("엿새")) {
            return 6;
        }
        if (s.contains("닷새")) {
            return 5;
        }
        if (s.contains("나흘")) {
            return 4;
        }
        if (s.contains("사흘")) {
            return 3;
        }
        if (s.contains("이틀")) {
            return 2;
        }
        if (s.contains("하루") || s.contains("전날") || s.contains("전일")) {
            return 1;
        }
        return null;
    }

    private static String compact(String utterance) {
        return utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
    }

    private static boolean otherFeature(String utterance) {
        return switch (Intent.of(utterance, false)) {
            case WEATHER, SCHEDULE, FACE, HELP, OUTFIT, CHECK, COLOR -> true;
            default -> false;
        };
    }

    private static void clear(AgentSession session) {
        session.reminderStep = null;
        session.reminderEventId = null;
        session.reminderTitle = null;
        session.reminderAsked = 0;
    }

    private TurnResponse reply(AgentSession session, String speech, Next next) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, next, Result.of("schedule_reminder"));
    }
}
