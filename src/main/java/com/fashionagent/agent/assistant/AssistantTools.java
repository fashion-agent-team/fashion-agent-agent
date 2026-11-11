package com.fashionagent.agent.assistant;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.flow.ReminderFlow;
import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 개별 기능에서 GPT가 고르는 도구. 대화 한 번마다 새로 만듭니다.
 *
 * <p>GPT는 userId, 위치, 사진을 채우지 않습니다. userId는 X-User-Id, 위치는 앱이 보낸 값,
 * 사진은 세션에 10분 들고 있는 직전 사진을 이 클래스가 넣습니다(약속 3절, 5-2절, 5-3절).
 * 도구가 실패하면 예외 대신 {"error": {...}} 를 GPT에 돌려줘서 사용자에게 말로 안내하게 합니다.
 */
public class AssistantTools {

    private final AgentTools tools;
    private final AgentSession session;
    private final Location location;
    private final String photo;
    private final JsonMapper json;

    /**
     * 이미 해 버린 일(일정 등록·삭제, 가족에게 보내기)의 안내 문장. GPT가 도구를 부른 뒤 시간 초과나
     * 사용량 초과로 마지막 답을 못 만들어도, 사용자가 "처리하지 못했어요"만 듣고 같은 일을 또 시키지 않게 이건 말해 줍니다.
     */
    final List<Done> done = new ArrayList<>();

    /**
     * 해 버린 일 하나. GPT 답에 {@code mention} 중 하나도 없으면(예: 보내 놓고 "보내려면 확인이라고 말해 주세요")
     * 답을 믿지 않고 {@code say}를 읽습니다. 2026-11-04 실제 GPT(gpt-5-mini)에서 이런 답이 나왔습니다.
     */
    record Done(String say, List<String> mention) {
        /** mention의 말이 모두 들어 있어야 합니다. "지웠|삭제"처럼 |로 나눈 것은 그중 하나. */
        boolean mentionedIn(String speech) {
            return mention.stream().filter(m -> m != null && !m.isBlank())
                    .allMatch(m -> java.util.Arrays.stream(m.split("\\|")).anyMatch(speech::contains));
        }
    }

    /** 한 번에 볼 수 있는 날 수. "이번 주랑 다음 주"(최대 14일)까지. */
    static final int MAX_LIST_DAYS = 14;
    /** schedule_list에서 본 일정 이름(eventId → title). 지운 뒤 "면접을 지웠어요"라고 말하려고. */
    private final Map<String, String> eventTitles = new HashMap<>();
    /** helper_contacts에서 본 이름(contactId → name). */
    private final Map<String, String> contactNames = new HashMap<>();
    /** 가족에게 물으려 했는데 사진이 없었는지. 그러면 카메라를 켭니다. */
    boolean neededPhoto;

    public AssistantTools(AgentTools tools, AgentSession session, Location location, String photo,
            JsonMapper json) {
        this.tools = tools;
        this.session = session;
        this.location = location == null ? Location.SEOUL : location;
        this.photo = photo;
        this.json = json;
    }

    @Tool(name = ToolNames.WEATHER_TODAY, description = """
            사용자가 있는 곳의 오늘 날씨(상태, 기온, 체감 온도, 최저·최고, 강수 확률, 옷차림 힌트)를 가져옵니다. \
            "오늘 날씨 어때?", "우산 가져가야 해?" 같은 말에 씁니다. 오늘만 알 수 있습니다.""")
    public String weatherToday() {
        return call(ToolNames.WEATHER_TODAY, Map.of("lat", location.lat(), "lon", location.lon()), false);
    }

    @Tool(name = ToolNames.SCHEDULE_ADD, description = """
            일정을 등록합니다. "다음 주 토요일 두 시에 친구 결혼식 넣어 줘" 같은 말에 씁니다. \
            날짜나 시각을 모르면 부르지 말고 사용자에게 먼저 물어보세요. 결과의 spokenSummary를 그대로 읽어 주면 됩니다.""")
    public String scheduleAdd(
            @ToolParam(description = "일정 이름. 사용자가 말한 그대로. 예: 친구 결혼식, 면접") String title,
            @ToolParam(description = "시작 시각 ISO-8601, +09:00 포함. 예: 2026-11-14T14:00:00+09:00") String start,
            @ToolParam(description = "장소 이름. 말하지 않았으면 빈 문자열", required = false) String place) {
        Map<String, Object> args = new HashMap<>();
        args.put("title", title);
        args.put("start", start);
        if (place != null && !place.isBlank()) {
            args.put("place", place);
        }
        String out = call(ToolNames.SCHEDULE_ADD, args, true);
        JsonNode node = json.readTree(out);
        if (node.has("spokenSummary")) {
            done.add(new Done(node.path("spokenSummary").asString(), List.of("등록|넣었")));
        }
        String eventId = node.path("eventId").asString("");
        if (!eventId.isBlank()) {
            // 답 뒤에 "일정 전에 알림을 드릴까요?"를 붙입니다(ReminderFlow). GPT는 알림을 묻지 않습니다.
            session.reminderStep = ReminderFlow.PENDING;
            session.reminderEventId = eventId;
            session.reminderTitle = title == null ? null : title.strip();
        }
        return out;
    }

    @Tool(name = ToolNames.SCHEDULE_LIST, description = """
            일정 목록을 가져옵니다. "내일 일정 뭐야?"는 date만, "이번 주 일정"·"다음 주 일정"처럼 여러 날은 date와 endDate를 \
            함께 넣어 한 번에 부르세요(최대 14일). 일정을 지우기 전에 eventId를 찾을 때도 씁니다. \
            여러 날을 보면 결과는 days(날짜별 events)입니다.""")
    public String scheduleList(
            @ToolParam(description = "날짜 YYYY-MM-DD. 비우면 오늘", required = false) String date,
            @ToolParam(description = "여러 날을 볼 때 마지막 날짜 YYYY-MM-DD(포함). 하루만 보면 비움", required = false)
            String endDate) {
        LocalDate from;
        LocalDate to;
        try {
            from = date == null || date.isBlank() ? null : LocalDate.parse(date.strip());
            to = endDate == null || endDate.isBlank() ? from : LocalDate.parse(endDate.strip());
        } catch (DateTimeParseException e) {
            return error("bad_date", "날짜는 YYYY-MM-DD 형식이어야 해요.");
        }
        if (from == null) {
            // 오늘: 날짜는 서버가 정합니다(Asia/Seoul).
            return listOne(new HashMap<>());
        }
        if (to.isBefore(from)) {
            LocalDate t = from;
            from = to;
            to = t;
        }
        if (from.plusDays(MAX_LIST_DAYS - 1).isBefore(to)) {
            to = from.plusDays(MAX_LIST_DAYS - 1);
        }
        if (from.equals(to)) {
            return listOne(new HashMap<>(Map.of("date", from.toString())));
        }
        List<Object> days = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            JsonNode day = json.readTree(listOne(new HashMap<>(Map.of("date", d.toString()))));
            if (day.has("error")) {
                return json.writeValueAsString(day);
            }
            days.add(day);
        }
        return json.writeValueAsString(Map.of("from", from.toString(), "to", to.toString(), "days", days));
    }

    private String listOne(Map<String, Object> args) {
        String out = call(ToolNames.SCHEDULE_LIST, args, true);
        for (JsonNode e : json.readTree(out).path("events")) {
            eventTitles.put(e.path("eventId").asString(""), e.path("title").asString(""));
        }
        return out;
    }

    @Tool(name = ToolNames.SCHEDULE_DELETE, description = """
            일정을 지웁니다. 먼저 schedule_list로 eventId를 찾고, 어느 일정인지 분명할 때만 부르세요.""")
    public String scheduleDelete(@ToolParam(description = "schedule_list 결과의 eventId") String eventId) {
        String out = call(ToolNames.SCHEDULE_DELETE, Map.of("eventId", eventId), true);
        if (json.readTree(out).path("deleted").asBoolean(false)) {
            String title = eventTitles.get(eventId);
            boolean known = title != null && !title.isBlank();
            // "지웠어요."만 들으면 무엇을 지웠는지 알 수 없어서, 일정 이름이 답에 없으면 이름을 넣어 읽습니다.
            done.add(new Done(known ? "일정 '" + title + "'을 지웠어요." : "일정을 지웠어요.",
                    known ? List.of("지웠|삭제", title) : List.of("지웠|삭제")));
        }
        return out;
    }

    @Tool(name = ToolNames.HELPER_CONTACTS, description = """
            사용자가 등록한 가족·친구 목록(contactId, name, relation)을 가져옵니다. \
            "엄마한테 물어봐 줘"처럼 사람을 부를 때 contactId를 찾으려고 먼저 부릅니다.""")
    public String helperContacts() {
        String out = call(ToolNames.HELPER_CONTACTS, Map.of(), true);
        for (JsonNode c : json.readTree(out).path("contacts")) {
            contactNames.put(c.path("contactId").asString(""), c.path("name").asString(""));
        }
        return out;
    }

    @Tool(name = ToolNames.HELPER_ASK, description = """
            직전에 카메라로 본 사진과 질문을 가족·친구에게 문자로 보냅니다. 사용자가 원할 때만 부르세요. \
            사진은 agent가 넣으니 넘기지 않습니다. 질문은 받는 사람이 읽기 좋게 한 문장으로 다듬어 주세요.""")
    public String helperAsk(
            @ToolParam(description = "helper_contacts 결과의 contactId") String contactId,
            @ToolParam(description = "받는 사람에게 보낼 질문. 예: 이 옷 오늘 결혼식에 괜찮은지 봐 줄래?") String question) {
        if (photo == null) {
            neededPhoto = true;
            return error("no_photo", "보낼 사진이 없어요. 사용자에게 먼저 옷이나 얼굴을 카메라에 보여 달라고 하세요.");
        }
        String out = call(ToolNames.HELPER_ASK,
                Map.of("contactId", contactId, "question", question, "imageBase64", photo), true);
        JsonNode node = json.readTree(out);
        if (node.has("requestId")) {
            session.lastHelperRequestId = node.path("requestId").asString();
            String name = node.path("sentTo").asString(contactNames.get(contactId));
            String who = name == null || name.isBlank() ? "가족" : name;
            // 누구에게 보냈는지도 답에 있어야 합니다("보냈어요."만 오면 바꿈).
            done.add(new Done(who + "에게 사진과 질문을 보냈어요. 답이 왔는지는 \"답 왔어?\"라고 물어봐 주세요.",
                    List.of("보냈", who)));
        }
        return out;
    }

    /** 기능 열기에서 고를 수 있는 이름. */
    static final java.util.Set<String> FEATURES = java.util.Set.of("outfit", "face", "teeth", "schedule_today");

    /**
     * GPT가 연 기능. 2026-11-06 진행 님 요청: 키워드에 안 걸린 말도 AI가 보고 우리 기능으로 풀 수 있으면 풀기.
     * 옷 추천·얼굴 확인은 정해진 흐름(카메라 순서 등)이 있어 GPT가 직접 하지 않고, agent가 이 값을 보고 그 흐름을 시작합니다.
     */
    String feature;

    @Tool(name = "open_feature", description = """
            우리 앱의 정해진 기능을 시작합니다. 사용자가 기능 이름을 정확히 말하지 않아도 뜻이 맞으면 부릅니다. \
            outfit: 옷 추천·옷 고르기·꺼낸 옷 확인("나 뭐 걸치고 나가지?", "이 옷 괜찮아?"), \
            face: 얼굴·화장·머리 확인, teeth: 이·치아에 뭐 꼈는지, schedule_today: 오늘 일정 읽기. \
            부른 뒤에는 "네, 볼게요."처럼 한마디만 답합니다(이어지는 안내는 agent가 합니다).""")
    public String openFeature(
            @ToolParam(description = "outfit, face, teeth, schedule_today 중 하나") String name) {
        String n = name == null ? "" : name.strip().toLowerCase(java.util.Locale.ROOT);
        if (!FEATURES.contains(n)) {
            return error("unknown_feature", "outfit, face, teeth, schedule_today 중에서 고르세요.");
        }
        feature = n;
        return "{\"opened\":\"" + n + "\"}";
    }

    @Tool(name = ToolNames.HELPER_ANSWER, description = """
            가족·친구에게 보낸 질문에 답이 왔는지 확인합니다. "엄마 답 왔어?"에 씁니다. \
            status가 pending이면 아직, answered면 answer를 읽어 주고, expired면 시간이 지났다고 말합니다.""")
    public String helperAnswer(
            @ToolParam(description = "helper_ask 결과의 requestId. 모르면 빈 문자열(가장 최근 질문)", required = false)
            String requestId) {
        String id = requestId == null || requestId.isBlank() ? session.lastHelperRequestId : requestId;
        if (id == null) {
            return error("no_request", "이 대화에서 보낸 질문이 없어요.");
        }
        return call(ToolNames.HELPER_ANSWER, Map.of("requestId", id), true);
    }

    private String call(String tool, Map<String, Object> args, boolean forUser) {
        try {
            JsonNode out = forUser ? tools.callForUser(tool, session.userId, args) : tools.call(tool, args);
            return json.writeValueAsString(out);
        } catch (ToolException e) {
            return error(e.code(), e.getMessage());
        }
    }

    private String error(String code, String message) {
        return json.writeValueAsString(Map.of("error", Map.of("code", code, "message", message)));
    }
}
