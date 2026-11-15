package com.fashionagent.agent.flow;

import com.fashionagent.agent.judge.Occasion;

/**
 * closet_search의 imageQuery. 옷장 서버가 옷 사진 벡터와 비교하는 짧은 영어 문장입니다(FashionSigLIP은 영어만 압니다).
 * 일정 자리와 체감 온도로 정해진 문장을 만들어 GPT 호출 없이 채웁니다.
 */
final class ClosetQuery {

    private ClosetQuery() {
    }

    static String english(Occasion occasion, Double apparentTemperature) {
        // 옷장 서버가 쉼표로 나눠 옷 이름마다 사진과 견줍니다. 막연한 문장("workout sportswear")보다 옷 이름 목록이 잘 찾습니다
        // (2026-11-15 측정: 상황 질문 위 5개 정답 55% → 63%).
        Occasion o = occasion == null ? Occasion.casual : occasion;
        String look = switch (o) {
            case mourning -> "black suit, black dress, black blouse, black slacks";
            case wedding -> "elegant dress, blazer, dress shirt, blouse, slacks";
            case formal -> "suit jacket, blazer, dress shirt, dress, slacks";
            case business -> "blazer, dress shirt, blouse, slacks, suit jacket";
            case active -> "leggings, athletic shorts, hoodie, sweatshirt, track pants, sports t-shirt";
            case casual -> null;
        };
        String weather = apparentTemperature == null ? null : weather(apparentTemperature);
        if (look == null) {
            return weather != null ? weather : "t-shirt, jeans, hoodie, casual pants, sneakers";
        }
        // 자리가 정해지면 자리 옷이 먼저고, 아주 춥거나 더울 때만 날씨 옷을 덧붙입니다.
        boolean extreme = apparentTemperature != null && (apparentTemperature < 5 || apparentTemperature >= 25);
        return extreme && o != Occasion.mourning ? look + ", " + weather : look;
    }

    static String weather(double t) {
        return t < 5 ? "coat, padded jacket, knit sweater, cardigan, hoodie"
                : t < 12 ? "jacket, sweater, cardigan, long sleeve shirt, hoodie"
                : t < 20 ? "light jacket, cardigan, long sleeve top, shirt"
                : t < 25 ? "t-shirt, shirt, blouse, light pants"
                : "t-shirt, tank top, shorts, sleeveless top, short sleeve shirt";
    }
}
