package com.fashionagent.agent.api;

import java.util.List;
import java.util.Map;

/** POST /api/v1/agent/turn 요청 (openapi.yaml TurnRequest). */
public record TurnRequest(
        String sessionId,
        String utterance,
        String image,
        String imageKind,
        Boolean autoCaptured,
        Map<String, Object> faceLandmarks,
        Map<String, Object> mouthBox,
        Location location,
        String outfitMode,
        /** imageKind=face: 얼굴이 잘 잡혀 멈춘 1초 동안 더 찍은 장면들 [{image, faceLandmarks}] (openapi.yaml faceFrames) */
        List<Map<String, Object>> faceFrames) {

    public boolean hasImage() {
        return image != null && !image.isBlank();
    }

    public boolean hasUtterance() {
        return utterance != null && !utterance.isBlank();
    }
}
