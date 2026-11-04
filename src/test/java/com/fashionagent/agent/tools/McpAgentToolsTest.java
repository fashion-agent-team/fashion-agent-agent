package com.fashionagent.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트 안에서 Spring AI MCP 서버(Streamable HTTP, /mcp)를 띄우고 McpAgentTools로 불러 봅니다.
 * 팀원이 만드는 mcp-* 서버와 같은 방식(@Tool)이라, 이게 통과하면 실제 서버에도 붙습니다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.ai.mcp.server.protocol=STREAMABLE",
        "spring.ai.mcp.server.name=mcp-weather-test",
        "server.shutdown=immediate" })
class McpAgentToolsTest {

    @LocalServerPort
    int port;

    @Autowired
    JsonMapper json;

    @TestConfiguration
    static class WeatherServer {

        static class WeatherTools {

            @Tool(name = "weather_today", description = "오늘 날씨")
            public Map<String, Object> weatherToday(@ToolParam(description = "위도") double lat,
                    @ToolParam(description = "경도") double lon) {
                return Map.of("condition", "맑음", "apparentTemperature", 9.5, "lat", lat);
            }

            @Tool(name = "schedule_list", description = "일정")
            public Map<String, Object> scheduleList(String userId, String date) {
                return Map.of("error", Map.of("code", "not_found", "message", "없음 " + userId));
            }
        }

        @Bean
        ToolCallbackProvider weatherTools() {
            return MethodToolCallbackProvider.builder().toolObjects(new WeatherTools()).build();
        }
    }

    @Test
    void 진짜_MCP_서버의_도구를_부른다() {
        String url = "http://localhost:" + port + "/mcp";
        var tools = new McpAgentTools(Map.of("weather", url, "schedule", url), json);

        JsonNode weather = tools.call(ToolNames.WEATHER_TODAY, Map.of("lat", 37.5, "lon", 127.0));
        assertThat(weather.path("condition").asString()).isEqualTo("맑음");
        assertThat(weather.path("lat").asDouble()).isEqualTo(37.5);

        // 도구가 {"error":...}를 돌려주면 ToolException, userId는 agent가 넣은 값
        assertThatThrownBy(() -> tools.callForUser(ToolNames.SCHEDULE_LIST, "u-real",
                Map.of("userId", "gemini-made-up", "date", "2026-11-04")))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not_found")
                .hasMessageContaining("u-real");
    }

    @Test
    void 주소가_없는_서버는_not_configured() {
        var tools = new McpAgentTools(Map.of(), json);
        assertThatThrownBy(() -> tools.call(ToolNames.CLOSET_SEARCH, Map.of()))
                .isInstanceOf(ToolException.class)
                .hasMessageContaining("not_configured");
    }
}
