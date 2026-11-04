package com.fashionagent.agent.api;

import java.util.Map;

/** POST /api/v1/agent/turn 요청 (openapi.yaml TurnRequest). */
public record TurnRequest(
        String sessionId,
        String utterance,
        String image,
        String imageKind,
        Boolean autoCaptured,
        Map<String, Object> faceLandmarks,
        Location location) {

    public boolean hasImage() {
        return image != null && !image.isBlank();
    }

    public boolean hasUtterance() {
        return utterance != null && !utterance.isBlank();
    }
}
