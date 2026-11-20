package com.fashionagent.agent.api;

/**
 * 옷 판단에 쓰는 내 정보(TurnRequest.wearer). 게이트웨이가 DB에서 읽어 바디에 넣고, 앱이 넣은 값은 덮어씁니다.
 * 2026-11-20 헤더(X-User-Profile)에서 바디로 옮김. 이름·호칭·생년월일 원본은 오지 않습니다. 없는 칸은 null.
 */
public record WearerProfile(String gender, String ageGroup, Double heightCm, Double weightKg, String job) {
}
