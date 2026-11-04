package com.fashionagent.agent.model;

import java.util.List;

/** 2단계 판단(확인 모드) 출력. Gemini 구조화 출력과 규칙 판단이 같은 모양을 돌려줍니다. */
public record CheckJudgement(
        List<OutfitCheck.Verdict> verdicts,
        OutfitCheck.Comparison comparison,
        List<OutfitCheck.Damage> damage,
        List<String> stillMissing,
        String speech) {

    public CheckJudgement {
        verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        damage = damage == null ? List.of() : List.copyOf(damage);
        stillMissing = stillMissing == null ? List.of() : List.copyOf(stillMissing);
    }
}
