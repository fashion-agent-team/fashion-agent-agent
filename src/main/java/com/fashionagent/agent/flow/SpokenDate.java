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
        // "크리스마스에 뭐 입지?": 2026-11-23 시뮬에서 오늘로 추천했음.
        if (s.contains("크리스마스이브") || s.contains("성탄전야")) {
            return monthDay(12, 24, today);
        }
        if (s.contains("크리스마스") || s.contains("성탄절")) {
            return monthDay(12, 25, today);
        }
        if (s.contains("새해첫날") || s.contains("신정")) {
            return monthDay(1, 1, today);
        }
        // "100일 뒤 날씨", "2주 후": 2026-11-23 시뮬에서 오늘 날씨로 답했음.
        Matcher after = Pattern.compile("(\\d{1,3})(일|주)(뒤|후|있다가)").matcher(s);
        if (after.find()) {
            int n = Integer.parseInt(after.group(1));
            return today.plusDays("주".equals(after.group(2)) ? 7L * n : n);
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
        // "주말에 결혼식 가는데 뭐 입지?": 오늘로 추천했음(2026-11-23 시뮬). 주말은 토요일(주말이면 오늘)로 봅니다.
        if (s.contains("주말")) {
            if (s.contains("다음주말") || s.contains("담주말")) {
                return weekday("다음주", DayOfWeek.SATURDAY, today);
            }
            return today.getDayOfWeek() == DayOfWeek.SUNDAY ? today : today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY));
        }
        m = DAY_ONLY.matcher(s);
        if (m.find()) {
            return dayOfMonth(Integer.parseInt(m.group(1)), today);
        }
        return null;
    }

    /**
     * "2월 30일", "13월 5일"처럼 날짜를 말했는데 없는 날이면 true. 2026-11-23 시뮬: 없는 날을 물으면 조용히 오늘로 답했음.
     */
    public static boolean impossible(String utterance, LocalDate today) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        Matcher m = MONTH_DAY.matcher(s);
        if (!m.find()) {
            m = SLASH.matcher(s);
            if (!m.find()) {
                return false;
            }
        }
        return monthDay(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), today) == null;
    }

    static final String SAY_IMPOSSIBLE = "그런 날짜는 없어요. 몇 월 며칠인지 다시 말씀해 주실래요?";

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
