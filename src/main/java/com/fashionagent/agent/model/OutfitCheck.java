package com.fashionagent.agent.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 2단계 자동 전송된 한 장에 대한 판단 (openapi.yaml OutfitCheck). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OutfitCheck(
        List<Garment> garments,
        List<Verdict> verdicts,
        Comparison comparison,
        List<Damage> damage,
        List<String> stillMissing,
        String retake,
        String source) {

    /** fit: good | ok | bad */
    public record Verdict(String inputId, String position, String fit, List<String> notes) {
    }

    public record Comparison(String betterInputId, String reason) {
    }

    /**
     * kind: stain | tear | lint | other, action: wash | repair | change | ok | check.
     * where는 사용자가 "어디야?"라고 물을 때만 읽어 줍니다.
     */
    public record Damage(String inputId, String kind, String action, String where, String say) {
    }
}
