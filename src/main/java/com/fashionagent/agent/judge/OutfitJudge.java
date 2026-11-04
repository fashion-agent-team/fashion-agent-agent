package com.fashionagent.agent.judge;

import java.util.List;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
import com.fashionagent.agent.model.Garment;

import tools.jackson.databind.JsonNode;

/**
 * 옷 추천 판단. MCP 서버가 준 데이터를 받아 판단·추천합니다(약속 5-1절).
 * 구현은 Gemini({@link GeminiOutfitJudge})와 규칙({@link RuleOutfitJudge}) 두 가지이고 출력 모양이 같습니다.
 */
public interface OutfitJudge {

    /** 1단계 추천 모드 (사진 없음). */
    AdviceJudgement advise(AdviceInput input);

    /** 2단계 확인 모드. now는 지금 사진의 옷, seenBefore는 앞서 본 옷. */
    CheckJudgement check(CheckInput input);

    record AdviceInput(JsonNode weather, JsonNode events, JsonNode closetCandidates, JsonNode styleNotes) {
    }

    record CheckInput(JsonNode weather, JsonNode events, AdviceJudgement advice, List<Garment> now,
            List<SeenBrief> seenBefore) {
    }

    record SeenBrief(String garmentId, String category, String name, String colorName, String verdict) {
    }
}
