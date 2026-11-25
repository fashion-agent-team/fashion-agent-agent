package com.fashionagent.agent.tools;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpTransportSessionClosedException;
import io.modelcontextprotocol.spec.McpTransportSessionNotFoundException;
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
    private final Duration timeout;
    private final Map<String, McpSyncClient> clients = new ConcurrentHashMap<>();
    /** MCP 서버마다 같은 MCP_SHARED_KEY를 X-Mcp-Key 헤더로 보냅니다(2026-11-25 보안 점검). 비우면 안 보냄. */
    private final String mcpKey;
    public static final String KEY_HEADER = "X-Mcp-Key";

    public McpAgentTools(Map<String, String> serverUrls, JsonMapper json) {
        this(serverUrls, json, REQUEST_TIMEOUT, "");
    }

    public McpAgentTools(Map<String, String> serverUrls, JsonMapper json, String mcpKey) {
        this(serverUrls, json, REQUEST_TIMEOUT, mcpKey);
    }

    /** 시험용: 기다리는 시간을 짧게. */
    McpAgentTools(Map<String, String> serverUrls, JsonMapper json, Duration timeout) {
        this(serverUrls, json, timeout, "");
    }

    McpAgentTools(Map<String, String> serverUrls, JsonMapper json, Duration timeout, String mcpKey) {
        this.serverUrls = serverUrls;
        this.json = json;
        this.timeout = timeout;
        this.mcpKey = mcpKey == null ? "" : mcpKey.strip();
    }

    @Override
    public JsonNode call(String tool, Map<String, Object> args) {
        String server = ToolNames.serverOf(tool);
        boolean cached = clients.containsKey(server);
        McpSchema.CallToolResult result;
        try {
            result = callOnce(tool, server, args);
        } catch (ToolException e) {
            throw e;
        } catch (RuntimeException e) {
            drop(server);
            if (isTimeout(e)) {
                // 서버가 이미 일을 하고 있을 수 있어서 다시 부르지 않는다(2026-11-20: 일정 등록이 두 번 들어갈 수 있었고,
                // 사진 검사는 20초 + 20초로 게이트웨이 30초를 넘겼음).
                throw new ToolException(tool, "upstream_timeout", "MCP 호출 시간 초과: " + e.getMessage(), e);
            }
            if (!cached || !retryable(tool, e)) {
                throw new ToolException(tool, "upstream_failed", "MCP 호출 실패: " + e.getMessage(), e);
            }
            // MCP 서버가 다시 켜지면 예전 세션은 죽어 있다(2026-11-09: 서버를 다시 켠 뒤 첫 요청만 실패).
            // 연결을 버리고 새로 붙어서 한 번만 다시 부른다.
            log.info("MCP 세션이 끊겨 다시 연결: {} ({})", server, e.getMessage());
            try {
                result = callOnce(tool, server, args);
            } catch (ToolException again) {
                throw again;
            } catch (RuntimeException again) {
                drop(server);
                throw new ToolException(tool, "upstream_failed", "MCP 호출 실패: " + again.getMessage(), again);
            }
        }
        JsonNode body = parse(tool, result);
        if (Boolean.TRUE.equals(result.isError()) || body.has("error")) {
            JsonNode error = body.path("error");
            throw new ToolException(tool, error.path("code").asString("tool_error"),
                    error.path("message").asString(body.toString()));
        }
        return body;
    }

    /** 저장·삭제처럼 두 번 하면 결과가 달라지는 도구. 서버가 일을 안 한 게 확실할 때만 다시 부릅니다. */
    static final Set<String> WRITES = Set.of(ToolNames.SCHEDULE_ADD, ToolNames.SCHEDULE_DELETE,
            ToolNames.SCHEDULE_SET_REMINDER, ToolNames.CLOSET_SAVE, ToolNames.CLOSET_WEAR, ToolNames.CLOSET_MERGE,
            ToolNames.CLOSET_REMOVE);

    /** 원인 중에 시간 초과가 있으면 true. 그때는 서버가 아직 일을 하고 있을 수 있습니다. */
    static boolean isTimeout(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof java.util.concurrent.TimeoutException || t instanceof java.net.http.HttpTimeoutException
                    || t instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /**
     * 끊긴 연결로 실패했을 때 다시 불러도 되는지. 읽기 도구는 늘 되고, 쓰기 도구는 서버가 요청을 받기 전에
     * 끊긴 게 확실할 때만(세션 없음·닫힘·연결 거부) 됩니다.
     */
    static boolean retryable(String tool, Throwable e) {
        if (!WRITES.contains(tool)) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof McpTransportSessionNotFoundException || t instanceof McpTransportSessionClosedException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }

    private McpSchema.CallToolResult callOnce(String tool, String server, Map<String, Object> args) {
        return client(tool, server).callTool(McpSchema.CallToolRequest.builder(tool).arguments(args).build());
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
            // 서버가 아직 없거나 꺼져 있음. GPT가 "다시 해 볼까요?"가 아니라 "지금은 그 기능을 쓸 수 없어요"라고 말하게 코드를 나눕니다.
            throw new ToolException(tool, "unavailable", server + " 서버에 연결할 수 없어요(아직 준비 안 됐거나 꺼짐): " + e.getMessage(), e);
        }
    }

    private McpSyncClient connect(String url) {
        URI uri = URI.create(url);
        String base = uri.getScheme() + "://" + uri.getAuthority();
        String endpoint = uri.getPath() == null || uri.getPath().isBlank() ? "/mcp" : uri.getPath();
        var builder = HttpClientStreamableHttpTransport.builder(base).endpoint(endpoint);
        if (!mcpKey.isEmpty()) {
            builder.customizeRequest(r -> r.header(KEY_HEADER, mcpKey));
        }
        var transport = builder.build();
        McpSyncClient client = McpClient.sync(transport)
                .clientInfo(McpSchema.Implementation.builder("fashion-agent-agent", "0.0.1").title("패션 에이전트 agent").build())
                .requestTimeout(timeout)
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
