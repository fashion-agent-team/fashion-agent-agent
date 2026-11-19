package com.fashionagent.agent.model;

import java.util.List;

/** 1단계 판단(추천 모드) 출력. GPT 구조화 출력과 규칙 판단이 같은 모양을 돌려줍니다. */
public record AdviceJudgement(
        List<OutfitAdvice.Suggestion> suggestions,
        List<OutfitAdvice.ClosetPick> closetPicks,
        String speech) {

    public AdviceJudgement {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        closetPicks = closetPicks == null ? List.of() : List.copyOf(closetPicks);
    }

    public OutfitAdvice toAdvice() {
        return new OutfitAdvice(suggestions, closetPicks);
    }
}
