package com.fashionagent.agent.flow;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fashionagent.agent.api.Location;

/**
 * "부산 날씨 어때?"처럼 말한 도시. 2026-11-23 시뮬: 도시 이름을 무시하고 지금 있는 곳 날씨를 부산 날씨처럼 읽었습니다.
 * 자주 묻는 도시만 좌표를 둡니다(시청 근처). 없는 도시는 지금 위치로 답합니다.
 */
final class Cities {

    record City(String name, Location at) {
    }

    /** 긴 이름부터 봅니다("서귀포"가 "제주"보다 먼저, "제주도"가 "제주"보다 먼저). */
    private static final Map<String, City> BY_WORD = new LinkedHashMap<>();

    static {
        add("서귀포", "서귀포", 33.2541, 126.5601);
        add("제주도", "제주", 33.4996, 126.5312);
        add("제주", "제주", 33.4996, 126.5312);
        add("서울", "서울", 37.5665, 126.9780);
        add("부산", "부산", 35.1796, 129.0756);
        add("대구", "대구", 35.8714, 128.6014);
        add("인천", "인천", 37.4563, 126.7052);
        add("광주", "광주", 35.1595, 126.8526);
        add("대전", "대전", 36.3504, 127.3845);
        add("울산", "울산", 35.5384, 129.3114);
        add("세종", "세종", 36.4800, 127.2890);
        add("수원", "수원", 37.2636, 127.0286);
        add("춘천", "춘천", 37.8813, 127.7298);
        add("강릉", "강릉", 37.7519, 128.8761);
        add("청주", "청주", 36.6424, 127.4890);
        add("전주", "전주", 35.8242, 127.1480);
        add("포항", "포항", 36.0190, 129.3435);
        add("창원", "창원", 35.2280, 128.6811);
        add("여수", "여수", 34.7604, 127.6622);
        add("목포", "목포", 34.8118, 126.3922);
        add("안동", "안동", 36.5684, 128.7294);
    }

    private static void add(String word, String name, double lat, double lon) {
        BY_WORD.put(word, new City(name, new Location(lat, lon, false)));
    }

    private Cities() {
    }

    /** 말에 도시 이름이 있으면 그 도시, 없으면 null. */
    static City in(String utterance) {
        if (utterance == null) {
            return null;
        }
        String s = utterance.replaceAll("\\s+", "");
        for (var e : BY_WORD.entrySet()) {
            if (s.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }
}
