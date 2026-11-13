package com.fashionagent.agent.judge;

import java.util.List;
import java.util.regex.Pattern;

import com.fashionagent.agent.model.OutfitCheck;

/**
 * 자리·날씨에 안 맞는 옷인데 GPT가 "잘 고르셨어요"로 말을 시작하면 그 말을 뺍니다.
 * 2026-11-13: 면접에 호피 무늬 자켓을 "잘 고르셨어요. 면접에는 너무 캐주얼해서 아쉬워요"라고 읽음(캐주얼도 쉬운 말로 바꾸게 함).
 * 앱은 speech의 첫 문장을 제목으로 크게 보여 주므로, 첫 문장이 칭찬이면 안 됩니다.
 */
final class PraiseGuard {

    /** 맨 앞 "잘 고르셨어요"(앞에 짧은 말이 붙어도 됨) + 문장부호 + "다만/그런데/하지만" 같은 잇는 말. */
    private static final Pattern LEADING_PRAISE = Pattern.compile(
            "^\\s*[^.!?。\\n]{0,30}?잘\\s*고르셨(?:어요|네요|습니다)[.!?~,。…\\s]*"
                    + "(?:(?:다만|그런데|근데|하지만|그래도|그렇지만)[,\\s]*)?");

    static String strip(String speech, List<OutfitCheck.Verdict> verdicts) {
        if (speech == null || verdicts == null || verdicts.isEmpty() || allGood(verdicts)) {
            return speech;
        }
        String rest = LEADING_PRAISE.matcher(speech).replaceFirst("");
        // 칭찬만 있고 다른 말이 없으면 그대로 둡니다(빈 말을 읽지 않게).
        return rest.isBlank() || rest.length() == speech.length() ? speech : rest;
    }

    private static boolean allGood(List<OutfitCheck.Verdict> verdicts) {
        return verdicts.stream().allMatch(v -> "good".equalsIgnoreCase(v.fit()));
    }

    private PraiseGuard() {
    }
}
