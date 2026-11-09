package com.fashionagent.agent.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class SpokenDateTest {

    /** 2026-11-09 월요일. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 9);

    private static LocalDate d(String s) {
        return SpokenDate.from(s, TODAY);
    }

    @Test
    void 월_일을_읽는다() {
        assertThat(d("11월 20일 일정에 어울릴만한 패션 추천해줘")).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(d("12월25일 뭐 입지")).isEqualTo(LocalDate.of(2026, 12, 25));
        assertThat(d("11/20 옷 추천해 줘")).isEqualTo(LocalDate.of(2026, 11, 20));
        // 지난 날이면 내년
        assertThat(d("3월 1일 옷 추천")).isEqualTo(LocalDate.of(2027, 3, 1));
        // 없는 날
        assertThat(d("2월 30일 옷 추천")).isNull();
    }

    @Test
    void 일만_말하면_이번_달_지났으면_다음_달() {
        assertThat(d("20일에 뭐 입지")).isEqualTo(LocalDate.of(2026, 11, 20));
        assertThat(d("5일 옷 추천해 줘")).isEqualTo(LocalDate.of(2026, 12, 5));
    }

    @Test
    void 내일_모레_글피() {
        assertThat(d("내일 뭐 입지")).isEqualTo(LocalDate.of(2026, 11, 10));
        assertThat(d("모레 옷 추천해 줘")).isEqualTo(LocalDate.of(2026, 11, 11));
        assertThat(d("글피 코디")).isEqualTo(LocalDate.of(2026, 11, 12));
    }

    @Test
    void 요일() {
        assertThat(d("토요일 뭐 입지")).isEqualTo(LocalDate.of(2026, 11, 14));
        assertThat(d("이번 주 금요일 옷 추천")).isEqualTo(LocalDate.of(2026, 11, 13));
        assertThat(d("다음 주 토요일 결혼식 옷 추천해 줘")).isEqualTo(LocalDate.of(2026, 11, 21));
        assertThat(d("월요일 뭐 입지")).isEqualTo(TODAY);
        assertThat(d("일요일 코디")).isEqualTo(LocalDate.of(2026, 11, 15));
    }

    @Test
    void 날짜가_없으면_null() {
        assertThat(d("오늘 뭐 입지?")).isNull();
        assertThat(d("일정에 맞는 옷 추천해 줘")).isNull();
        assertThat(d(null)).isNull();
    }

    @Test
    void 말로_읽는_날짜() {
        assertThat(SpokenDate.label(TODAY, TODAY)).isEqualTo("오늘");
        assertThat(SpokenDate.label(TODAY.plusDays(1), TODAY)).isEqualTo("내일");
        assertThat(SpokenDate.label(TODAY.plusDays(2), TODAY)).isEqualTo("모레");
        assertThat(SpokenDate.label(LocalDate.of(2026, 11, 20), TODAY)).isEqualTo("11월 20일 금요일");
    }
}
