package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.session.AgentSession;
import com.fashionagent.agent.session.InMemorySessionStore;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ClosetFlowTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T03:00:00Z"), ZoneId.of("Asia/Seoul"));
    private final List<String> calls = new ArrayList<>();
    private final List<Map<String, Object>> callArgs = new ArrayList<>();

    private static final String CLOSET = """
            {"total": 4, "garments": [
              {"garmentId":"c_1","category":"outer","name":"패딩","colorName":"검정"},
              {"garmentId":"c_2","category":"outer","name":"코트","colorName":"베이지"},
              {"garmentId":"c_3","category":"bottom","name":"청바지","colorName":"청색"},
              {"garmentId":"c_4","category":"bottom","name":"슬랙스","colorName":"검정"}]}""";

    private ClosetFlow flow(String outfits) {
        AgentTools tools = (tool, args) -> {
            calls.add(tool);
            callArgs.add(args);
            return switch (tool) {
                case ToolNames.CLOSET_LIST -> {
                    JsonNode all = json.readTree(CLOSET);
                    Object cat = args.get("category");
                    if (cat == null) {
                        yield all;
                    }
                    var arr = json.createArrayNode();
                    all.path("garments").forEach(g -> {
                        if (cat.equals(g.path("category").asString())) {
                            arr.add(g);
                        }
                    });
                    yield json.createObjectNode().put("total", arr.size()).set("garments", arr);
                }
                case ToolNames.CLOSET_OUTFITS -> json.readTree(outfits);
                case ToolNames.CLOSET_REMOVE -> json.readTree("{\"removed\":1}");
                default -> throw new IllegalStateException(tool);
            };
        };
        return new ClosetFlow(tools, new InMemorySessionStore(java.time.Duration.ofMinutes(30), clock), clock);
    }

    private AgentSession session() {
        return new AgentSession("s_1", "00000000-0000-0000-0000-000000000001");
    }

    @Test
    @DisplayName("어제 뭐 입었지: 어제 하루만 closet_outfits로 묻고 읽는다")
    void yesterday() {
        var f = flow("{\"outfits\":[{\"date\":\"2026-11-11\",\"garments\":[{\"name\":\"패딩\",\"colorName\":\"검정\"},"
                + "{\"name\":\"청바지\",\"colorName\":\"청색\"}]}]}");
        var r = f.history(session(), "어제 뭐 입었지?");
        assertThat(callArgs.get(0)).containsEntry("from", "2026-11-11").containsEntry("to", "2026-11-11");
        assertThat(r.speech()).isEqualTo("어제는 검정 패딩, 청색 청바지를 입으셨어요.");
    }

    @Test
    @DisplayName("기록이 없으면 어떻게 기록되는지 알려 준다")
    void noHistory() {
        var r = flow("{\"outfits\":[]}").history(session(), "그저께 뭐 입었어");
        assertThat(r.speech()).startsWith("그저께 입은 옷 기록이 없어요.");
        assertThat(ClosetFlow.dayLabel(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 12))).isEqualTo("11월 5일 목요일");
    }

    @Test
    @DisplayName("한 벌만 맞으면 지울지 묻고, 네 하면 closet_remove")
    void removeOne() {
        var f = flow("{}");
        var s = session();
        var r = f.startRemove(s, "옷장에서 검정 패딩 지워 줘");
        assertThat(r.speech()).startsWith("옷장에서 검정 패딩을 지울까요?");
        assertThat(calls).doesNotContain(ToolNames.CLOSET_REMOVE);
        r = f.answerRemove(s, "응");
        assertThat(r.speech()).isEqualTo("검정 패딩을 옷장에서 지웠어요.");
        assertThat(callArgs.get(callArgs.size() - 1)).containsEntry("garmentIds", List.of("c_1"));
        assertThat(s.closetRemoveIds).isNull();
    }

    @Test
    @DisplayName("여러 벌이면 첫째·둘째로 읽고, 고른 뒤 한 번 더 묻는다. 아니요면 안 지운다")
    void removeChoose() {
        var f = flow("{}");
        var s = session();
        var r = f.startRemove(s, "옷장에서 겉옷 빼 줘");
        assertThat(r.speech()).isEqualTo("옷장에 비슷한 옷이 2벌 있어요. 첫째, 검정 패딩. 둘째, 베이지 코트. 몇째 옷을 지울까요?");
        r = f.answerRemove(s, "두 번째");
        assertThat(r.speech()).startsWith("옷장에서 베이지 코트를 지울까요?");
        r = f.answerRemove(s, "아니 그냥 둘래");
        assertThat(r.speech()).isEqualTo("알겠어요. 옷장은 그대로 둘게요.");
        assertThat(calls).doesNotContain(ToolNames.CLOSET_REMOVE);
    }

    @Test
    @DisplayName("색만 말해도 그 색 옷만, 없는 옷이면 못 찾았다고 한다")
    void match() {
        var all = json.readTree(CLOSET).path("garments");
        assertThat(ClosetFlow.matches(all, "옷장에서 검정 옷 지워", false)).extracting(g -> g.path("garmentId").asString())
                .containsExactly("c_1", "c_4");
        assertThat(ClosetFlow.matches(all, "옷장에서 빨간 니트 지워", false)).isEmpty();
        assertThat(flow("{}").startRemove(session(), "옷장에서 빨간 니트 지워 줘").speech()).startsWith("옷장에서 그 옷을 찾지 못했어요.");
    }

    @Test
    @DisplayName("지울 옷을 묻는 중에 상관없는 말을 하면 null(다른 흐름으로)")
    void unrelated() {
        var f = flow("{}");
        var s = session();
        f.startRemove(s, "옷장에서 겉옷 빼 줘");
        assertThat(f.answerRemove(s, "오늘 날씨 어때")).isNull();
        assertThat(s.closetRemoveIds).isNull();
    }
}
