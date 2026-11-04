package com.fashionagent.agent.assistant;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import com.fashionagent.agent.api.Location;
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
    final List<String> done = new ArrayList<>();
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
            done.add(node.path("spokenSummary").asString());
        }
        return out;
    }

    @Tool(name = ToolNames.SCHEDULE_LIST, description = """
            그날의 일정 목록을 가져옵니다. "내일 일정 뭐야?"에 쓰고, 일정을 지우기 전에 eventId를 찾을 때도 씁니다.""")
    public String scheduleList(
            @ToolParam(description = "날짜 YYYY-MM-DD. 비우면 오늘", required = false) String date) {
        Map<String, Object> args = new HashMap<>();
        if (date != null && !date.isBlank()) {
            args.put("date", date);
        }
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
            done.add(title == null || title.isBlank() ? "일정을 지웠어요." : "일정 '" + title + "'을 지웠어요.");
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
            done.add((name == null || name.isBlank() ? "가족" : name) + "에게 사진과 질문을 보냈어요. 답이 왔는지는 \"답 왔어?\"라고 물어봐 주세요.");
        }
        return out;
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
