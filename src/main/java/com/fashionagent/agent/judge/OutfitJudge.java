package com.fashionagent.agent.judge;

import java.util.List;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
import com.fashionagent.agent.model.Garment;

import tools.jackson.databind.JsonNode;

/**
 * 옷 추천 판단. MCP 서버가 준 데이터를 받아 판단·추천합니다(약속 5-1절).
 * 구현은 GPT({@link LlmOutfitJudge})와 규칙({@link RuleOutfitJudge}) 두 가지이고 출력 모양이 같습니다.
 */
public interface OutfitJudge {

    /** 1단계 추천 모드 (사진 없음). */
    AdviceJudgement advise(AdviceInput input);

    /** 2단계 확인 모드. now는 지금 사진의 옷, seenBefore는 앞서 본 옷. */
    CheckJudgement check(CheckInput input);

    /**
     * day: 옷을 입을 날을 말로 읽은 것("오늘", "내일", "11월 20일 금요일").
     * weatherNote: weather가 null인 까닭을 사용자에게 말할 문장(예보가 아직 없는 날 등). 없으면 null.
     */
    record AdviceInput(JsonNode weather, JsonNode events, JsonNode closetCandidates, JsonNode styleNotes,
            String day, String weatherNote) {

        public AdviceInput(JsonNode weather, JsonNode events, JsonNode closetCandidates, JsonNode styleNotes) {
            this(weather, events, closetCandidates, styleNotes, "오늘", null);
        }

        public boolean today() {
            return day == null || "오늘".equals(day);
        }
    }

    /** @param day 이 옷을 입을 날(오늘·내일·11월 20일 금요일). 내일 일정인데 "오늘"이라고 하지 않게(2026-11-12 시뮬). */
    record CheckInput(JsonNode weather, JsonNode events, AdviceJudgement advice, List<Garment> now,
            List<SeenBrief> seenBefore, String day) {

        public CheckInput {
            day = day == null || day.isBlank() ? "오늘" : day;
        }

        public CheckInput(JsonNode weather, JsonNode events, AdviceJudgement advice, List<Garment> now,
                List<SeenBrief> seenBefore) {
            this(weather, events, advice, now, seenBefore, "오늘");
        }
    }

    record SeenBrief(String garmentId, String category, String name, String colorName, String verdict) {
    }
}
