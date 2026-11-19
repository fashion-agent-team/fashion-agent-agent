package com.fashionagent.agent.api;

public record Location(double lat, double lon, Boolean locationFallback) {

    /** 위치 권한이 없을 때 쓰는 서울 시청 (약속 1절). */
    public static final Location SEOUL = new Location(37.5665, 126.9780, true);
}
