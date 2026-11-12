package com.fashionagent.agent.flow;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.fashionagent.agent.api.TurnResponse;
import com.fashionagent.agent.api.TurnResponse.Next;
import com.fashionagent.agent.api.TurnResponse.Result;
import com.fashionagent.agent.judge.Korean;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.SessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;

/**
 * 옷장을 말로 쓰는 일: 옷장 읽기("옷장 확인해 줘"), 입은 날 읽기("어제 뭐 입었지?"), 옷 지우기("옷장에서 검정 패딩 지워 줘").
 * 2026-11-12 시뮬: "어제 뭐 입었지?"와 "옷장에서 원피스 지워줘"가 옷 추천으로 넘어갔습니다.
 */
@Service
public class ClosetFlow {

    static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 지울 옷 후보를 이만큼까지만 읽고 고르게 합니다. 더 많으면 색이나 이름을 더 말해 달라고 합니다. */
    static final int MAX_CHOICES = 4;
    private static final List<String> YES = List.of("네", "응", "좋아", "그래", "지워", "삭제", "예", "웅", "넵", "맞아");
    private static final List<String> NO = List.of("아니", "괜찮", "됐어", "그만", "취소", "놔둬", "두자", "말자", "싫");
    private static final List<List<String>> ORDINALS = List.of(
            List.of("첫", "1번", "일번", "하나"), List.of("둘", "두번", "2번", "이번째"),
            List.of("셋", "세번", "3번", "삼번"), List.of("넷", "네번", "4번", "사번"));

    private final AgentTools tools;
    private final SessionStore sessions;
    private final Clock clock;

    public ClosetFlow(AgentTools tools, SessionStore sessions, Clock clock) {
        this.tools = tools;
        this.sessions = sessions;
        this.clock = clock;
    }

    // ── 옷장 읽기 ────────────────────────────────────────────────

    public TurnResponse list(AgentSession session, String utterance) {
        String category = ClosetView.category(utterance);
        var args = new LinkedHashMap<String, Object>();
        if (category != null) {
            args.put("category", category);
        }
        args.put("limit", 30);
        try {
            JsonNode list = tools.callForUser(ToolNames.CLOSET_LIST, session.userId, args);
            return say(session, ClosetView.speech(list, category), "closet_list");
        } catch (ToolException e) {
            return say(session, "미안해요, 지금은 옷장을 못 열었어요. 조금 있다가 다시 말씀해 주실래요?", null);
        }
    }

    // ── 입은 날 읽기 ─────────────────────────────────────────────

    public TurnResponse history(AgentSession session, String utterance) {
        LocalDate today = LocalDate.now(clock.withZone(SEOUL));
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        LocalDate from = null;
        LocalDate to = null;
        String when;
        if (s.contains("그제") || s.contains("그저께")) {
            from = to = today.minusDays(2);
            when = "그저께";
        } else if (s.contains("어제")) {
            from = to = today.minusDays(1);
            when = "어제";
        } else if (s.contains("오늘")) {
            from = to = today;
            when = "오늘";
        } else if (s.contains("지난주") || s.contains("저번주") || s.contains("일주일")) {
            from = today.minusDays(7);
            to = today.minusDays(1);
            when = "지난 일주일";
        } else {
            when = null;
        }
        var args = new LinkedHashMap<String, Object>();
        if (from != null) {
            args.put("from", from.toString());
            args.put("to", to.toString());
        }
        args.put("limit", 3);
        try {
            JsonNode out = tools.callForUser(ToolNames.CLOSET_OUTFITS, session.userId, args);
            return say(session, historySpeech(out.path("outfits"), when, today), "closet_outfits");
        } catch (ToolException e) {
            return say(session, "미안해요, 지금은 입은 옷 기록을 못 열었어요. 조금 있다가 다시 말씀해 주실래요?", null);
        }
    }

    static String historySpeech(JsonNode outfits, String when, LocalDate today) {
        if (outfits == null || !outfits.isArray() || outfits.isEmpty()) {
            return (when == null ? "아직 입은 옷 기록이 없어요. " : when + " 입은 옷 기록이 없어요. ")
                    + "옷 추천을 받고 \"이걸로 할게\"라고 하시면 그날 입은 옷으로 기록돼요.";
        }
        List<String> parts = new ArrayList<>();
        for (JsonNode o : outfits) {
            LocalDate day = LocalDate.parse(o.path("date").asString());
            List<String> names = new ArrayList<>();
            for (JsonNode g : o.path("garments")) {
                names.add(ClosetView.label(g));
            }
            String label = dayLabel(day, today);
            parts.add(label + (label.contains("월") ? "에는 " : "는 ") + Korean.josa(String.join(", ", names), "을", "를") + " 입으셨어요.");
        }
        return String.join(" ", parts);
    }

    static String dayLabel(LocalDate day, LocalDate today) {
        if (day.equals(today)) {
            return "오늘";
        }
        if (day.equals(today.minusDays(1))) {
            return "어제";
        }
        if (day.equals(today.minusDays(2))) {
            return "그저께";
        }
        return SpokenDate.label(day, today);
    }

    // ── 옷 지우기 ───────────────────────────────────────────────

    public TurnResponse startRemove(AgentSession session, String utterance) {
        String category = ClosetView.category(utterance);
        var args = new LinkedHashMap<String, Object>();
        if (category != null) {
            args.put("category", category);
        }
        args.put("limit", 50);
        JsonNode list;
        try {
            list = tools.callForUser(ToolNames.CLOSET_LIST, session.userId, args);
        } catch (ToolException e) {
            return say(session, "미안해요, 지금은 옷장을 못 열었어요. 조금 있다가 다시 말씀해 주실래요?", null);
        }
        List<JsonNode> found = matches(list.path("garments"), utterance, category != null);
        if (found.isEmpty()) {
            return say(session, "옷장에서 그 옷을 찾지 못했어요. \"옷장 확인해 줘\"라고 하시면 있는 옷을 읽어 드려요.", null);
        }
        if (found.size() == 1) {
            return askConfirm(session, found.get(0));
        }
        if (found.size() > MAX_CHOICES) {
            return say(session, "옷장에 비슷한 옷이 " + found.size() + "벌 있어요. 색이나 이름을 더 말씀해 주실래요? "
                    + "예를 들어 \"옷장에서 검정 패딩 지워 줘\"처럼요.", null);
        }
        session.closetRemoveIds = new ArrayList<>(found.stream().map(g -> g.path("garmentId").asString()).toList());
        session.closetRemoveNames = new ArrayList<>(found.stream().map(ClosetView::label).toList());
        session.closetRemoveConfirm = false;
        StringBuilder sb = new StringBuilder("옷장에 비슷한 옷이 ").append(found.size()).append("벌 있어요.");
        String[] nth = {"첫째", "둘째", "셋째", "넷째"};
        for (int i = 0; i < found.size(); i++) {
            sb.append(' ').append(nth[i]).append(", ").append(session.closetRemoveNames.get(i)).append('.');
        }
        sb.append(" 몇째 옷을 지울까요?");
        return say(session, sb.toString(), null);
    }

    /** 지울 옷을 묻는 중이면 그 답을 처리하고, 상관없는 말이면 null(다른 흐름으로). */
    public TurnResponse answerRemove(AgentSession session, String utterance) {
        String s = utterance == null ? "" : utterance.replaceAll("[\\s?.!,~]+", "");
        if (NO.stream().anyMatch(s::contains)) {
            clear(session);
            return say(session, "알겠어요. 옷장은 그대로 둘게요.", null);
        }
        if (session.closetRemoveConfirm) {
            if (YES.stream().anyMatch(s::startsWith)) {
                String id = session.closetRemoveIds.get(0);
                String name = session.closetRemoveNames.get(0);
                clear(session);
                try {
                    tools.callForUser(ToolNames.CLOSET_REMOVE, session.userId, Map.of("garmentIds", List.of(id)));
                    return say(session, Korean.josa(name, "을", "를") + " 옷장에서 지웠어요.", "closet_remove");
                } catch (ToolException e) {
                    return say(session, "미안해요, 지금은 지우지 못했어요. 조금 있다가 다시 말씀해 주실래요?", null);
                }
            }
            clear(session);
            return null;
        }
        for (int i = 0; i < Math.min(ORDINALS.size(), session.closetRemoveIds.size()); i++) {
            if (ORDINALS.get(i).stream().anyMatch(s::contains)) {
                String id = session.closetRemoveIds.get(i);
                String name = session.closetRemoveNames.get(i);
                session.closetRemoveIds = new ArrayList<>(List.of(id));
                session.closetRemoveNames = new ArrayList<>(List.of(name));
                session.closetRemoveConfirm = true;
                return say(session, confirmText(name), null);
            }
        }
        clear(session);
        return null;
    }

    private TurnResponse askConfirm(AgentSession session, JsonNode g) {
        session.closetRemoveIds = new ArrayList<>(List.of(g.path("garmentId").asString()));
        session.closetRemoveNames = new ArrayList<>(List.of(ClosetView.label(g)));
        session.closetRemoveConfirm = true;
        return say(session, confirmText(session.closetRemoveNames.get(0)), null);
    }

    static String confirmText(String name) {
        return "옷장에서 " + Korean.josa(name, "을", "를") + " 지울까요? 입은 날 기록도 함께 지워져요. 네 또는 아니요로 말씀해 주세요.";
    }

    /**
     * 말에 나온 색·이름과 맞는 옷. 색이나 이름이 하나도 안 맞으면, 종류(바지 등)를 말했을 때만 그 종류 전부.
     */
    static List<JsonNode> matches(JsonNode garments, String utterance, boolean saidCategory) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        List<JsonNode> all = new ArrayList<>();
        garments.forEach(all::add);
        int best = 0;
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode g : all) {
            int score = 0;
            String color = g.path("colorName").asString("").replaceAll("\\s+", "");
            String name = g.path("name").asString("").replaceAll("\\s+", "");
            if (!color.isBlank() && s.contains(color)) {
                score++;
            }
            if (!name.isBlank() && s.contains(name)) {
                score++;
            }
            if (score > best) {
                best = score;
                out.clear();
            }
            if (score == best && score > 0) {
                out.add(g);
            }
        }
        if (best == 0) {
            return saidCategory ? all : List.of();
        }
        return out;
    }

    private static void clear(AgentSession session) {
        session.closetRemoveIds = null;
        session.closetRemoveNames = null;
        session.closetRemoveConfirm = false;
    }

    private TurnResponse say(AgentSession session, String speech, String type) {
        sessions.save(session);
        return new TurnResponse(session.sessionId, speech, Next.listen, type == null ? null : Result.of(type));
    }
}
