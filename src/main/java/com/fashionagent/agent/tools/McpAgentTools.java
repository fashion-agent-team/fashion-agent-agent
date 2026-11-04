package com.fashionagent.agent.tools;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 실제 MCP 서버(Streamable HTTP)를 부릅니다. 서버마다 클라이언트를 처음 쓸 때 만들고,
 * 연결이 실패하면 버려서 다음 호출 때 다시 붙습니다(서버를 나중에 켜도 agent를 다시 켤 필요 없음).
 */
public class McpAgentTools implements AgentTools, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(McpAgentTools.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final Map<String, String> serverUrls;
    private final JsonMapper json;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    public McpAgentTools(Map<String, String> serverUrls, JsonMapper json) {
        this.serverUrls = serverUrls;
        this.json = json;
    }

    @Override
    public JsonNode call(String tool, Map<String, Object> args) {
        String server = ToolNames.serverOf(tool);
        McpSyncClient client = client(tool, server);
        McpSchema.CallToolResult result;
        try {
            result = client.callTool(McpSchema.CallToolRequest.builder().name(tool).arguments(args).build());
        } catch (RuntimeException e) {
            drop(server);
            throw new ToolException(tool, "upstream_failed", "MCP 호출 실패: " + e.getMessage(), e);
        }
        JsonNode body = parse(tool, result);
        if (Boolean.TRUE.equals(result.isError()) || body.has("error")) {
            JsonNode error = body.path("error");
            throw new ToolException(tool, error.path("code").asString("tool_error"),
                    error.path("message").asString(body.toString()));
        }
        return body;
    }

    private JsonNode parse(String tool, McpSchema.CallToolResult result) {
        StringBuilder text = new StringBuilder();
        for (McpSchema.Content content : result.content()) {
            if (content instanceof McpSchema.TextContent t) {
                text.append(t.text());
            }
        }
        try {
            return json.readTree(text.toString());
        } catch (RuntimeException e) {
            throw new ToolException(tool, "bad_tool_result", "JSON이 아닌 결과: " + text, e);
        }
    }

    private McpSyncClient client(String tool, String server) {
        String url = serverUrls.get(server);
        if (url == null || url.isBlank()) {
            throw new ToolException(tool, "not_configured", "agent.mcp." + server + " 주소가 없어요.");
        }
        try {
            return clients.computeIfAbsent(server, s -> connect(url));
        } catch (RuntimeException e) {
            throw new ToolException(tool, "upstream_failed", server + " 연결 실패: " + e.getMessage(), e);
        }
    }

    private McpSyncClient connect(String url) {
        URI uri = URI.create(url);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = uri.getPath() == null || uri.getPath().isBlank() ? "/mcp" : uri.getPath();
        var transport = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint).build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation("fashion-agent-agent", "패션 에이전트 agent", "0.0.1"))
                .requestTimeout(REQUEST_TIMEOUT)
                .build();
        client.initialize();
        log.info("MCP 연결: {}", url);
        return client;
    }

    /** agent가 꺼질 때 연결을 닫습니다. */
    @Override
    public void close() {
        clients.keySet().forEach(this::drop);
    }

    private void drop(String server) {
        McpSyncClient old = clients.remove(server);
        if (old != null) {
            try {
                old.closeGracefully();
            } catch (RuntimeException ignored) {
                // 이미 끊긴 연결
            }
        }
    }
}
