package com.fashionagent.agent.tools;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.databind.JsonNode;

/**
 * 자주 읽는 도구 결과를 잠깐 기억합니다(2026-11-09 발표 피드백: 딜레이 줄이기, 날씨·일정은 캐싱).
 * 걸린 시간도 도구마다 로그로 남깁니다(응답 시간 확인용).
 *
 * <ul>
 * <li>weather_today: 같은 동네(위도·경도 소수 둘째 자리, 약 1km)면 10분</li>
 * <li>weather_forecast: 같은 동네·날짜면 30분</li>
 * <li>schedule_list: 같은 사용자·날짜면 2분. 그 사용자가 일정을 넣거나 지우면 바로 비웁니다.</li>
 * </ul>
 * 사진 검사·옷장은 기억하지 않습니다.
 */
public class CachingAgentTools implements AgentTools, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(CachingAgentTools.class);

    static final Map<String, Duration> TTL = Map.of(
            ToolNames.WEATHER_TODAY, Duration.ofMinutes(10),
            ToolNames.WEATHER_FORECAST, Duration.ofMinutes(30),
            ToolNames.SCHEDULE_LIST, Duration.ofMinutes(2));
    static final int MAX_ENTRIES = 2_000;

    private record Entry(JsonNode value, long expiresAt) {
    }

    private final AgentTools delegate;
    private final Clock clock;
    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    public CachingAgentTools(AgentTools delegate, Clock clock) {
        this.delegate = delegate;
        this.clock = clock;
    }

    @Override
    public JsonNode call(String tool, Map<String, Object> args) {
        if (ToolNames.SCHEDULE_ADD.equals(tool) || ToolNames.SCHEDULE_DELETE.equals(tool)
                || ToolNames.SCHEDULE_SET_REMINDER.equals(tool)) {
            forgetSchedules(args.get("userId"));
        }
        Duration ttl = TTL.get(tool);
        if (ttl == null) {
            return timed(tool, args, false);
        }
        String key = key(tool, args);
        long now = clock.millis();
        Entry hit = cache.get(key);
        if (hit != null && hit.expiresAt() > now) {
            log.info("[시간] 도구 {} 0ms (기억해 둔 값)", tool);
            return hit.value();
        }
        JsonNode value = timed(tool, args, true);
        if (cache.size() >= MAX_ENTRIES) {
            cache.values().removeIf(e -> e.expiresAt() <= now);
            if (cache.size() >= MAX_ENTRIES) {
                cache.clear();
            }
        }
        cache.put(key, new Entry(value, now + ttl.toMillis()));
        return value;
    }

    /** agent가 꺼질 때 안쪽 MCP 연결을 닫습니다. */
    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable c) {
            c.close();
        }
    }

    private JsonNode timed(String tool, Map<String, Object> args, boolean cacheable) {
        long started = System.nanoTime();
        try {
            return delegate.call(tool, args);
        } finally {
            log.info("[시간] 도구 {} {}ms{}", tool, (System.nanoTime() - started) / 1_000_000,
                    cacheable ? " (새로 받음)" : "");
        }
    }

    private void forgetSchedules(Object userId) {
        String prefix = ToolNames.SCHEDULE_LIST + "|userId=" + userId + "|";
        cache.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 도구 이름 + 인자(이름순). 위도·경도는 소수 둘째 자리까지만 봅니다. userId를 앞에 둬서 사용자별로 지웁니다. */
    static String key(String tool, Map<String, Object> args) {
        StringBuilder sb = new StringBuilder(tool);
        if (args.containsKey("userId")) {
            sb.append("|userId=").append(args.get("userId"));
        }
        for (var e : new TreeMap<>(args).entrySet()) {
            if (e.getKey().equals("userId")) {
                continue;
            }
            Object v = e.getValue();
            if ((e.getKey().equals("lat") || e.getKey().equals("lon")) && v instanceof Number n) {
                v = String.format(java.util.Locale.ROOT, "%.2f", n.doubleValue());
            }
            sb.append('|').append(e.getKey()).append('=').append(v);
        }
        return sb.toString();
    }
}
