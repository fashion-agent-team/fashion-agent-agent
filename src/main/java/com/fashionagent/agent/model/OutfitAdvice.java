package com.fashionagent.agent.model;

import java.util.List;

/** 1단계 말로 추천 결과 (openapi.yaml OutfitAdvice). */
public record OutfitAdvice(List<Suggestion> suggestions, List<ClosetPick> closetPicks) {

    public OutfitAdvice {
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        closetPicks = closetPicks == null ? List.of() : List.copyOf(closetPicks);
    }

    /** category: top | bottom | outer | dress | shoes | other */
    public record Suggestion(String category, String text, String reason) {
    }

    public record ClosetPick(String garmentId, String reason) {
    }
}
