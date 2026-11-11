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
        String look = switch (occasion == null ? Occasion.casual : occasion) {
            case mourning -> "black formal mourning attire";
            case wedding -> "elegant wedding guest outfit";
            case formal -> "formal suit or dress";
            case business -> "neat office attire, dress shirt and slacks";
            case active -> "workout sportswear";
            case casual -> "casual everyday clothes";
        };
        if (apparentTemperature == null) {
            return look;
        }
        double t = apparentTemperature;
        String weather = t < 5 ? "warm winter clothes, coat and knit sweater"
                : t < 12 ? "clothes for cool weather, jacket and long sleeves"
                : t < 20 ? "light layers for mild weather"
                : t < 25 ? "light clothes for warm weather"
                : "light summer clothes, short sleeves";
        return look + ", " + weather;
    }
}
