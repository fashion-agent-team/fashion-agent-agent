package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fashionagent.agent.api.Location;
import com.fashionagent.agent.judge.OutfitJudge;
import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
import com.fashionagent.agent.tools.CachingAgentTools;
import com.fashionagent.agent.tools.ToolException;
import com.fashionagent.agent.tools.ToolNames;

import tools.jackson.databind.json.JsonMapper;

class PrefetchTest {

    private final List<String> calls = new ArrayList<>();
    private final JsonMapper json = JsonMapper.builder().build();
    // 서울 2026-11-15 08:00
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-14T23:00:00Z"), ZoneId.of("UTC"));
    private final AtomicInteger warmed = new AtomicInteger();
    private final OutfitJudge judge = new OutfitJudge() {
        public AdviceJudgement advise(AdviceInput input) { throw new UnsupportedOperationException(); }
        public CheckJudgement check(CheckInput input) { throw new UnsupportedOperationException(); }
        public void warmUp() { warmed.incrementAndGet(); }
    };

    private CachingAgentTools caching(boolean failWeather) {
        return new CachingAgentTools((tool, args) -> {
            calls.add(tool);
            if (failWeather && tool.equals(ToolNames.WEATHER_TODAY)) {
                throw new ToolException(tool, "unavailable", "날씨 서버 꺼짐");
            }
            return json.readTree("{\"n\":" + calls.size() + "}");
        }, clock);
    }

    @Test
    @DisplayName("warm이 채운 날씨·오늘 일정을 첫 추천이 같은 키로 꺼내 써서 도구를 다시 부르지 않는다")
    void 미리_불러온_값을_첫_추천이_그대로_쓴다() {
        CachingAgentTools tools = caching(false);
        Prefetch prefetch = new Prefetch(tools, judge, clock, Runnable::run);
        Location here = new Location(37.56651, 126.97801, null);

        assertThat(prefetch.start("u1", here)).isTrue();
        assertThat(calls).containsExactly(ToolNames.WEATHER_TODAY, ToolNames.SCHEDULE_LIST);
        assertThat(warmed).hasValue(1);

        // OutfitFlow.advise가 오늘 부르는 것과 같은 인자(좌표는 약 1km 안에서 조금 달라도 같은 동네)
        tools.call(ToolNames.WEATHER_TODAY, OutfitFlow.weatherArgs(new Location(37.5668, 126.9783, false)));
        tools.callForUser(ToolNames.SCHEDULE_LIST, "u1", OutfitFlow.scheduleArgs(LocalDate.of(2026, 11, 15)));
        assertThat(calls).hasSize(2);

        // 다른 사용자의 일정은 따로
        tools.callForUser(ToolNames.SCHEDULE_LIST, "u2", OutfitFlow.scheduleArgs(LocalDate.of(2026, 11, 15)));
        assertThat(calls).hasSize(3);
    }

    @Test
    @DisplayName("위치가 없으면 첫 추천과 같이 서울로 미리 불러 둔다")
    void 위치_없으면_서울() {
        CachingAgentTools tools = caching(false);
        new Prefetch(tools, judge, clock, Runnable::run).start("u1", null);
        tools.call(ToolNames.WEATHER_TODAY, OutfitFlow.weatherArgs(Location.SEOUL));
        assertThat(calls).containsExactly(ToolNames.WEATHER_TODAY, ToolNames.SCHEDULE_LIST);
    }

    @Test
    @DisplayName("날씨가 실패해도 일정은 불러 두고, 예외를 밖으로 내지 않는다")
    void 실패는_로그만() {
        CachingAgentTools tools = caching(true);
        assertThat(new Prefetch(tools, judge, clock, Runnable::run).start("u1", null)).isTrue();
        assertThat(calls).containsExactly(ToolNames.WEATHER_TODAY, ToolNames.SCHEDULE_LIST);
        assertThat(warmed).hasValue(1);
    }

    @Test
    @DisplayName("같은 사용자·동네·날짜를 불러오는 중이면 겹친 요청은 합친다")
    void 겹친_요청은_한_번만() {
        CachingAgentTools tools = caching(false);
        List<Runnable> queued = new ArrayList<>();
        Prefetch prefetch = new Prefetch(tools, judge, clock, queued::add);
        Location here = new Location(37.5665, 126.978, null);

        assertThat(prefetch.start("u1", here)).isTrue();
        assertThat(prefetch.start("u1", new Location(37.5669, 126.9781, null))).isFalse();
        assertThat(prefetch.start("u2", here)).isTrue();
        assertThat(queued).hasSize(2);

        queued.get(0).run();
        assertThat(prefetch.start("u1", here)).isTrue();
    }
}
