package com.fashionagent.agent.flow;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.judge.OutfitJudge;
import com.fashionagent.agent.tools.AgentTools;
import com.fashionagent.agent.tools.ToolNames;

/**
 * 앱이 켜질 때(또는 다시 앞으로 올 때) 보내는 POST /api/v1/agent/warm.
 * 첫 "오늘 뭐 입지?"에서 쓸 weather_today·schedule_list(그 사용자 오늘)를 미리 불러 {@code CachingAgentTools}에 넣고,
 * 식은 GPT 연결도 데웁니다. 답은 기다리지 않습니다(컨트롤러가 바로 202).
 *
 * <p>2026-11-15 측정: 그날 첫 추천 7.3초(날씨를 새로 받느라 6.1초), 두 번째 1.9초. 날씨가 기억돼 있으면 첫 추천도 두 번째처럼 빨라집니다.
 * 인자는 {@link OutfitFlow#weatherArgs}·{@link OutfitFlow#scheduleArgs}를 같이 써서 첫 차례와 캐시 키가 똑같습니다.
 */
@Component
public class Prefetch implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Prefetch.class);
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 미리 불러오기는 급하지 않아서 작은 풀에서 돕니다(대화 차례가 쓰는 agentExecutor와 따로). */
    static final int THREADS = 2;

    private final AgentTools tools;
    private final OutfitJudge judge;
    private final Clock clock;
    private final Executor executor;
    private final ExecutorService owned;
    /** 지금 불러오는 중인 (사용자, 동네, 날짜). 같은 것이 겹쳐 오면 한 번만 부릅니다. */
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    @Autowired
    public Prefetch(AgentTools tools, OutfitJudge judge, Clock clock) {
        this(tools, judge, clock, null);
    }

    /** executor가 null이면 작은 풀을 직접 만듭니다. 테스트는 Runnable::run을 넣어 바로 돌립니다. */
    Prefetch(AgentTools tools, OutfitJudge judge, Clock clock, Executor executor) {
        this.tools = tools;
        this.judge = judge;
        this.clock = clock;
        if (executor == null) {
            this.owned = Executors.newFixedThreadPool(THREADS, r -> {
                Thread t = new Thread(r, "prefetch");
                t.setDaemon(true);
                return t;
            });
            this.executor = owned;
        } else {
            this.owned = null;
            this.executor = executor;
        }
    }

    /** 미리 불러오기를 시작만 하고 바로 돌아옵니다. 이미 같은 것을 불러오는 중이면 false. */
    public boolean start(String userId, Location location) {
        Location at = location == null ? Location.SEOUL : location;
        LocalDate day = LocalDate.now(clock.withZone(SEOUL));
        String key = String.format(Locale.ROOT, "%s|%.2f|%.2f|%s", userId, at.lat(), at.lon(), day);
        if (!running.add(key)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    run(userId, at, day);
                } finally {
                    running.remove(key);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            running.remove(key);
            log.info("미리 불러오기 건너뜀(꺼지는 중): {}", e.toString());
            return false;
        }
    }

    private void run(String userId, Location at, LocalDate day) {
        long started = System.nanoTime();
        safe(ToolNames.WEATHER_TODAY, () -> tools.call(ToolNames.WEATHER_TODAY, OutfitFlow.weatherArgs(at)));
        safe(ToolNames.SCHEDULE_LIST, () -> tools.callForUser(ToolNames.SCHEDULE_LIST, userId, OutfitFlow.scheduleArgs(day)));
        safe("GPT 데우기", judge::warmUp);
        log.info("[시간] 미리 불러오기 {}ms", (System.nanoTime() - started) / 1_000_000);
    }

    private static void safe(String what, Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            log.info("미리 불러오기 {} 실패(괜찮음): {}", what, e.toString());
        }
    }

    @Override
    public void close() {
        if (owned != null) {
            owned.shutdownNow();
        }
    }
}
