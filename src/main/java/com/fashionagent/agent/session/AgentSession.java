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
    /** 얼굴 확인에서 "얼굴을 볼까요, 이를 확인할까요?"를 물은 횟수. 0이면 묻지 않은 상태. */
    public int faceChoiceAsked;
    /** 옷을 보여 주는 방식: laid(내려놓고 비추기) | worn(입고 한 바퀴). null이면 아직 모름. */
    public String outfitMode;
    /** "어떻게 보여 주실래요?"를 물은 횟수. 0이면 묻지 않은 상태. */
    public int modeAsked;
    /** 방식을 물을 때 받은 위치. 답을 들은 뒤 추천에 씁니다. */
    public com.fashionagent.agent.api.Location pendingLocation;
    /** 내려놓고 고른 뒤 "입은 모습도 확인해 보실래요?"를 물었는지. */
    public boolean wornOfferAsked;
    /** 이번 대화에서 고른 옷을 옷장에 기록했는지. 입은 모습까지 본 뒤 또 기록하지 않게. */
    public boolean recorded;
    /** 입고 한 바퀴에서 다음에 볼 방향: front | side | back. null이면 front. */
    public String wornStep;
    /** 입고 한 바퀴에서 이번 바퀴에 본 옷 (앞모습 옷이 기준, 옆·뒤 얼룩을 더함). */
    public List<Garment> wornGarments = new ArrayList<>();
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
