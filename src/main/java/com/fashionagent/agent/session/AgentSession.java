package com.fashionagent.agent.session;

import java.util.ArrayList;
import java.util.List;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.model.OutfitCheck;

import tools.jackson.databind.JsonNode;

/**
 * 한 번의 옷 추천 대화 상태. 1단계 날씨·일정·추천과 2단계에서 본 옷을 들고 있습니다(약속: 30분).
 * 사진은 저장하지 않습니다.
 */
public class AgentSession {

    public String sessionId;
    public String userId;
    /** 1단계에서 받은 도구 결과 (없으면 null). */
    public JsonNode weather;
    public JsonNode events;
    public JsonNode closet;
    public AdviceJudgement advice;
    /** 2단계에서 본 옷과 판단 (fit: good | ok | bad). id는 사진마다 겹치지 않게 다시 붙인 값. */
    public List<Seen> seen = new ArrayList<>();
    /** 직전 판단의 얼룩·찢어짐. "어디야?"에 답할 때 씁니다. */
    public List<OutfitCheck.Damage> lastDamage = new ArrayList<>();
    /** 지금까지 받은 사진 수. 옷 id 앞에 붙입니다. */
    public int photoCount;
    /** 직전에 받은 사진 base64와 받은 시각(epoch ms). 가족에게 묻기에 10분만 씁니다(약속 5-2절). */
    public String lastPhoto;
    public long lastPhotoAt;
    /** 가족에게 마지막으로 보낸 질문 id ("엄마 답 왔어?"). */
    public String lastHelperRequestId;
    /** 개별 기능 대화의 최근 몇 마디. GPT가 되물은 뒤 이어 듣기 위해. */
    public List<Said> assistantHistory = new ArrayList<>();

    public AgentSession() {
    }

    public AgentSession(String sessionId, String userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    /** 10분 안에 받은 직전 사진, 없으면 null. */
    public String photo(java.time.Clock clock) {
        if (lastPhoto == null || clock.millis() - lastPhotoAt > PHOTO_TTL_MS) {
            return null;
        }
        return lastPhoto;
    }

    public void keepPhoto(String imageBase64, java.time.Clock clock) {
        lastPhoto = imageBase64;
        lastPhotoAt = clock.millis();
    }

    private static final long PHOTO_TTL_MS = 10 * 60 * 1000;

    public record Said(boolean user, String text) {
    }

    public record Seen(Garment garment, String fit) {
    }
}
