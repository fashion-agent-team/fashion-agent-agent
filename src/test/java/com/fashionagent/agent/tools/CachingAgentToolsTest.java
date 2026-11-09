package com.fashionagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class CachingAgentToolsTest {

    private final List<String> calls = new ArrayList<>();
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-09T00:00:00Z"));
    private final Clock clock = new Clock() {
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now.get(); }
    };
    private final JsonMapper json = JsonMapper.builder().build();
    private final CachingAgentTools tools = new CachingAgentTools((tool, args) -> {
        calls.add(tool);
        JsonNode node = json.readTree("{\"n\":" + calls.size() + "}");
        return node;
    }, clock);

    @Test
    void 날씨는_같은_동네면_10분_동안_다시_부르지_않는다() {
        tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", 37.5012, "lon", 127.0001));
        tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", 37.5049, "lon", 127.0039));
        assertThat(calls).hasSize(1);

        now.set(now.get().plusSeconds(11 * 60));
        tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", 37.5012, "lon", 127.0001));
        assertThat(calls).hasSize(2);
    }

    @Test
    void 일정은_사용자별로_기억하고_넣거나_지우면_다시_부른다() {
        tools.callForUser(ToolNames.SCHEDULE_LIST, "u1", Map.of("date", "2026-11-10"));
        tools.callForUser(ToolNames.SCHEDULE_LIST, "u1", Map.of("date", "2026-11-10"));
        tools.callForUser(ToolNames.SCHEDULE_LIST, "u2", Map.of("date", "2026-11-10"));
        assertThat(calls).hasSize(2);

        tools.callForUser(ToolNames.SCHEDULE_ADD, "u1", Map.of("title", "회의"));
        tools.callForUser(ToolNames.SCHEDULE_LIST, "u1", Map.of("date", "2026-11-10"));
        tools.callForUser(ToolNames.SCHEDULE_LIST, "u2", Map.of("date", "2026-11-10"));
        assertThat(calls).containsExactly(ToolNames.SCHEDULE_LIST, ToolNames.SCHEDULE_LIST, ToolNames.SCHEDULE_ADD,
                ToolNames.SCHEDULE_LIST);
    }

    @Test
    void 사진_검사는_기억하지_않는다() {
        tools.call(ToolNames.CLOTHES_INSPECT, Map.of("imageBase64", "AAAA"));
        tools.call(ToolNames.CLOTHES_INSPECT, Map.of("imageBase64", "AAAA"));
        assertThat(calls).hasSize(2);
    }
}
