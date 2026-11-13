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
    /** 옷을 입을 날 YYYY-MM-DD. null이면 오늘. "11월 20일 일정에 맞는 옷 추천해 줘"에서 정해집니다. */
    public String targetDate;
    /** 2단계에서 본 옷과 판단 (fit: good | ok | bad). id는 사진마다 겹치지 않게 다시 붙인 값. */
    public List<Seen> seen = new ArrayList<>();
    /** 직전 판단의 얼룩·찢어짐. "어디야?"에 답할 때 씁니다. */
    public List<OutfitCheck.Damage> lastDamage = new ArrayList<>();
    /** 지금까지 받은 사진 수. 옷 id 앞에 붙입니다. */
    public int photoCount;
    /** 얼굴 확인에서 "얼굴을 볼까요, 이를 확인할까요?"를 물은 횟수. 0이면 묻지 않은 상태. */
    public int faceChoiceAsked;
    /**
     * GPT 확인·학습 저장 동의(앱이 요청마다 allowGpt로 보냄). true면 애매한 얼룩을 GPT로 확인합니다.
     * null이면 아직 묻지 않았고, 처음 애매한 얼룩이 나오면 묻습니다(2026-11-10 진행 님).
     */
    public Boolean gptConsent;
    /** "더 정확한 AI에게 물어봐도 될까요?"를 물었는지. 답을 기다리는 동안 true. */
    public boolean gptAsked;
    /** 이번 대화에서 GPT 확인 동의에 답했는지(앱이 다음 요청부터 기억한 답을 보냅니다). */
    public boolean gptDecided;
    /** 물을 때 본 옷 사진(내려놓고 비추기). 동의하면 이 사진을 GPT 확인으로 다시 봅니다. */
    public String gptAskImage;
    /** 옷을 보여 주는 방식: laid(내려놓고 비추기) | worn(입고 한 바퀴). null이면 아직 모름. */
    public String outfitMode;
    /** "어떻게 보여 주실래요?"를 물은 횟수. 0이면 묻지 않은 상태. */
    public int modeAsked;
    /** 방식을 물을 때 받은 위치. 답을 들은 뒤 추천에 씁니다. */
    public com.fashionagent.agent.api.Location pendingLocation;
    /** 말로 추천한 뒤 "봐 드릴까요?"를 물었는지. */
    public boolean checkOffered;
    /** 내려놓고 고른 뒤 "입은 모습도 확인해 보실래요?"를 물었는지. */
    public boolean wornOfferAsked;
    /** 이번 대화에서 고른 옷을 옷장에 기록했는지. 입은 모습까지 본 뒤 또 기록하지 않게. */
    public boolean recorded;
    /** 입고 한 바퀴에서 다음에 볼 방향: front | back. null이면 front. */
    public String wornStep;
    /** 입고 한 바퀴에서 이번 바퀴에 본 옷 (앞모습 옷이 기준, 뒤 얼룩을 더함). */
    public List<Garment> wornGarments = new ArrayList<>();
    /** 입고 한 바퀴(앞·뒤) 중 옷 상태 서버가 애매한 얼룩 후보를 돌려줬는지. 다 본 뒤 GPT 확인을 묻는 데 씁니다. */
    public boolean wornUnsure;
    /**
     * 입고 한 바퀴(앞·뒤)에서 옷 상태 서버가 본 옷 실수(뒤집어 입음·앞뒤 바뀜·단추 어긋남·택). 같은 옷 종류·실수는 한 번만.
     * 앞모습에서 비우고, 뒤까지 본 뒤 판단 말 뒤에 붙여 읽습니다(2026-11-13 정지원 님).
     */
    public List<WearNote> wornWearNotes = new ArrayList<>();
    /** 입고 한 바퀴에서 마지막으로 다 본(앞·뒤) 옷의 id. "이걸로 할게"는 이 옷만 고릅니다. */
    public List<String> lastWornIds;
    /** 개별 기능 대화의 최근 몇 마디. GPT가 되물은 뒤 이어 듣기 위해. */
    public List<Said> assistantHistory = new ArrayList<>();
    /**
     * clothes_inspect가 준 옷 사진 벡터(garments[].embedding), 키는 다시 붙인 옷 id. closet_save에만 넘기고
     * GPT·앱 응답에는 넣지 않으려고 Garment와 따로 둡니다. 옷을 고르고 나면 비웁니다.
     */
    public java.util.Map<String, List<Double>> embeddings = new java.util.HashMap<>();
    /** 옷 id → 옷 부분 작은 JPEG(base64). closet_save에만 넘김(같은 옷이 애매할 때 GPT 비교용). GPT 판단 입력·앱 응답에는 안 넣음. */
    public java.util.Map<String, String> thumbnails = new java.util.HashMap<>();
    /** clothes_inspect 응답의 embeddingModel (예: marqo-fashionSigLIP). */
    public String embeddingModel;
    /**
     * 일정을 넣은 뒤 알림 묻기(ReminderFlow): pending(방금 넣음) → want("일정 전에 알림을 드릴까요?") →
     * days("며칠 전에?"). null이면 묻지 않는 중.
     */
    public String reminderStep;
    /** 알림을 정할 일정(방금 넣은 일정)의 eventId와 이름. */
    public String reminderEventId;
    public String reminderTitle;
    /** "며칠 전?"을 다시 물은 횟수. */
    public int reminderAsked;

    /** 옷장에서 지울 옷을 묻는 중: 후보 id·이름(같은 순서). null이면 묻지 않는 중. */
    public List<String> closetRemoveIds;
    public List<String> closetRemoveNames;
    /** true면 한 벌을 골라 "지울까요?"를 물은 상태. false면 "몇째 옷을 지울까요?"를 물은 상태. */
    public boolean closetRemoveConfirm;
    /**
     * 지난 턴에 앱에 보낸 next. 카메라를 보던 중인지 알려고 씁니다.
     * 2026-11-12 진행 님 폰: 옷 추천을 들은 뒤(카메라는 안 켬) 일정을 지웠더니 카메라가 켜졌습니다.
     */
    public com.fashionagent.agent.api.TurnResponse.Next lastNext;

    public AgentSession() {
    }

    public AgentSession(String sessionId, String userId) {
        this.sessionId = sessionId;
        this.userId = userId;
    }

    public record Said(boolean user, String text) {
    }

    public record Seen(Garment garment, String fit) {
    }

    /** kind: inside_out | backwards | buttons_misaligned | tag_out. label은 읽어 줄 옷 이름(예: 흰색 셔츠). */
    public record WearNote(String kind, String category, String label, String view) {
    }
}
