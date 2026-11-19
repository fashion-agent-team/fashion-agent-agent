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
        List<Map<String, Object>> faceFrames,
        /** GPT 확인·학습 저장 동의. true면 애매한 얼룩을 GPT로 확인, false면 안 함, null이면 아직 묻지 않음(처음 애매한 얼룩에서 묻습니다). */
        Boolean allowGpt,
        /** 색 설명 방식. true(기본, null도 같음)면 감각·느낌까지, false면 이름만(빨강·초록은 짧은 비유). 2026-11-13 */
        Boolean colorDetail) {

    public boolean hasImage() {
        return image != null && !image.isBlank();
    }

    public boolean hasUtterance() {
        return utterance != null && !utterance.isBlank();
    }
}
