package com.fashionagent.agent.flow;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 말 속의 날짜("11월 20일", "20일", "내일", "모레", "다음 주 토요일")를 날짜로 바꿉니다.
 * "11월 20일 일정에 어울릴 옷 추천해 줘"처럼 다른 날 옷을 물을 때 씁니다(2026-11-09 진행 님 요청).
 * 날짜를 말하지 않았으면 null(= 오늘).
 */
public final class SpokenDate {

    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})월(\\d{1,2})일");
    private static final Pattern SLASH = Pattern.compile("(?<!\\d)(\\d{1,2})/(\\d{1,2})(?!\\d)");
    private static final Pattern DAY_ONLY = Pattern.compile("(?<!\\d)(\\d{1,2})일");
    private static final Pattern WEEKDAY = Pattern.compile("(이번주|이번|다음주|담주|다다음주)?(월|화|수|목|금|토|일)요일");
    private static final Map<String, DayOfWeek> DAYS = Map.of("월", DayOfWeek.MONDAY, "화", DayOfWeek.TUESDAY,
            "수", DayOfWeek.WEDNESDAY, "목", DayOfWeek.THURSDAY, "금", DayOfWeek.FRIDAY, "토", DayOfWeek.SATURDAY,
            "일", DayOfWeek.SUNDAY);

    private SpokenDate() {
    }

    public static LocalDate from(String utterance, LocalDate today) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        Matcher m = MONTH_DAY.matcher(s);
        if (m.find()) {
            return monthDay(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), today);
        }
        m = SLASH.matcher(s);
        if (m.find()) {
            return monthDay(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), today);
        }
        if (s.contains("글피")) {
            return today.plusDays(3);
        }
        if (s.contains("모레")) {
            return today.plusDays(2);
        }
        if (s.contains("내일")) {
            return today.plusDays(1);
        }
        m = WEEKDAY.matcher(s);
        if (m.find()) {
            return weekday(m.group(1), DAYS.get(m.group(2)), today);
        }
        m = DAY_ONLY.matcher(s);
        if (m.find()) {
            return dayOfMonth(Integer.parseInt(m.group(1)), today);
        }
        return null;
    }

    /** 지난 날이면 내년. 없는 날(2월 30일 등)이면 null. */
    private static LocalDate monthDay(int month, int day, LocalDate today) {
        try {
            LocalDate d = LocalDate.of(today.getYear(), month, day);
            return d.isBefore(today) ? d.plusYears(1) : d;
        } catch (DateTimeException e) {
            return null;
        }
    }

    /** "20일": 이번 달, 지났으면 다음 달. */
    private static LocalDate dayOfMonth(int day, LocalDate today) {
        for (int i = 0; i < 3; i++) {
            LocalDate month = today.withDayOfMonth(1).plusMonths(i);
            if (day >= 1 && day <= month.lengthOfMonth()) {
                LocalDate d = month.withDayOfMonth(day);
                if (!d.isBefore(today)) {
                    return d;
                }
            }
        }
        return null;
    }

    /** 주는 월요일부터. 앞말 없이 "토요일"이면 오늘부터 가장 가까운 토요일. */
    private static LocalDate weekday(String which, DayOfWeek day, LocalDate today) {
        if (which == null) {
            return today.with(TemporalAdjusters.nextOrSame(day));
        }
        LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        int weeks = switch (which) {
            case "다음주", "담주" -> 1;
            case "다다음주" -> 2;
            default -> 0;
        };
        return monday.plusWeeks(weeks).with(TemporalAdjusters.nextOrSame(day));
    }

    /**
     * 사용자가 요일로 물었으면 그 요일 그대로("토요일", "다음 주 토요일"), 아니면 {@link #label}.
     * 2026-11-12 실제 서버: 목요일에 "토요일 날씨"를 물었는데 "모레는"으로 답해 헷갈렸습니다.
     */
    public static String labelAsAsked(String utterance, LocalDate date, LocalDate today) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        Matcher m = WEEKDAY.matcher(s);
        if (date != null && !date.equals(today) && m.find() && !s.contains("내일") && !s.contains("모레")
                && !s.contains("글피")) {
            String day = m.group(2) + "요일";
            String which = m.group(1);
            if (which == null) {
                return day;
            }
            return switch (which) {
                case "다음주", "담주" -> "다음 주 " + day;
                case "다다음주" -> "다다음 주 " + day;
                default -> "이번 주 " + day;
            };
        }
        return label(date, today);
    }

    /** 말로 읽는 날짜: 오늘·내일·모레, 그 밖은 "11월 20일 금요일". */
    public static String label(LocalDate date, LocalDate today) {
        if (date == null || date.equals(today)) {
            return "오늘";
        }
        if (date.equals(today.plusDays(1))) {
            return "내일";
        }
        if (date.equals(today.plusDays(2))) {
            return "모레";
        }
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일 "
                + date.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.KOREAN);
    }
}
