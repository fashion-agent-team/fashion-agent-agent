package com.fashionagent.agent.flow;

import java.util.List;

/**
 * 말로 들어온 요청 고르기. 옷 추천 흐름은 GPT가 아니라 키워드로 정해서 발표 때 매번 같게 동작합니다(약속 5절).
 * 날씨·오늘 일정 읽기·얼굴 확인도 키워드로 바로 처리합니다(빠르고 GPT 없이도 됨).
 * 나머지({@link #OTHER}: 일정 등록·삭제, 다른 날 일정 등)는 GPT가 도구를 고릅니다.
 */
public enum Intent {
    OUTFIT, CHECK, COLOR, SHOWN, CLOSET, CLOSET_HISTORY, CLOSET_REMOVE, FINISH, WHERE, MODE, WEATHER, SCHEDULE, FACE, HELP, OTHER;

    private static final List<String> FINISH_WORDS = List.of("이걸로", "이거로", "이걸입", "이거입", "그걸로", "그거로", "그걸입", "됐어", "다됐", "그만",
            "끝낼", "마칠");
    /**
     * 옷을 다 보여 줬다는 말. 2026-11-13 실서버 시뮬: "다 보여 줬어"가 GPT로 가서 처음 추천을 다시 하고(17초) 본 옷을 잊었음.
     */
    private static final List<String> SHOWN_WORDS = List.of("다보여", "다보였", "다찍", "다봤", "더없어", "이게다", "이거다야",
            "이게전부", "이거전부");
    private static final List<String> OUTFIT_WORDS = List.of("뭐입", "뭘입", "옷추천", "추천해", "코디", "입을까", "입지",
            "옷골라", "옷좀골라", "골라줘", "골라줄래", "뭐신", "뭘신", "신을까", "추천다시", "다시추천", "추천또",
            // 2026-11-23 시뮬: "추천 좀"이 GPT로 갔음
            "추천좀", "추천부탁", "추천받");
    /**
     * "옷 검사해 줘"처럼 꺼낸 옷을 바로 봐 달라는 말. 추천을 길게 말하지 않고 바로 카메라로 갑니다.
     * 2026-11-09 리허설: "옷 검사"가 키워드에 없어 GPT로 갔고, 얼굴 확인으로 넘어갔습니다.
     * 2026-11-13 실서버 시뮬: "이 옷 어때"가 GPT로 가서 카메라 대신 오늘 추천을 길게 말했습니다.
     */
    private static final List<String> CHECK_WORDS = List.of("옷검사", "옷확인", "옷체크", "옷상태", "옷봐", "옷좀봐",
            "입은옷", "입은거", "입은모습", "옷차림", "검사해", "검사좀", "옷어때", "옷어떄", "옷괜찮", "옷어울", "이거어때", "이건어때", "이옷어때", "이거괜찮", "이건괜찮");
    private static final List<String> STAIN_WORDS = List.of("얼룩", "묻었", "묻은", "찢어", "찢어진", "구멍", "보풀", "올나간");

    /** "옷장에서 검정 패딩 지워 줘", "옷장에서 버린 바지 빼 줘". */
    private static final List<String> CLOSET_REMOVE_WORDS = List.of("지워", "지우", "비워", "비우", "삭제", "빼줘", "빼", "버렸", "없애");
    /** 일정 등록·삭제는 GPT로 보냅니다. 2026-11-12: "일정 없애 줘"가 오늘 일정 읽기로 갔습니다. */
    private static final List<String> SCHEDULE_CHANGE_WORDS = List.of("넣어", "등록", "추가", "잡아", "지워", "지우",
            "삭제", "취소", "빼", "없애", "없앨", "없에");
    /**
     * 여러 날 일정("이번 주 일정")은 GPT가 date·endDate로 한 번에 봅니다. 하루("내일", "다음 주 토요일", "11월 20일")는
     * 바로 읽습니다(2026-11-09 측정: GPT를 거치면 2~4.6초, 바로 읽으면 0.1초 안팎).
     */
    private static final List<String> MULTI_DAY_WORDS = List.of("이번주", "다음주", "담주", "주말", "이번달", "다음달");

    /**
     * "날씨"가 없어도 날씨로 바로 답할 말. 2026-11-12 실제 서버: "우산 챙겨야 해?"가 GPT로 가서 8초 걸렸습니다.
     * "눈"만으로는 얼굴(눈)과 헷갈려서 "눈 와", "눈 올" 같은 꼴만 봅니다.
     */
    private static final List<String> WEATHER_WORDS = List.of("우산", "비와", "비가", "비올", "비오", "눈와", "눈이와",
            "눈올", "눈이올", "눈오", "추워", "추운", "춥", "더워", "더운", "덥", "기온", "온도", "일교차", "쌀쌀", "겉옷챙",
            "입어야돼", "입어야해", "입어야하", "입어야될", "입어야할",
            "겉옷입어야", "외투챙", "몇도", "영하", "비온", "눈온", "장화", "우비", "레인부츠", "레인코트");
    /** "오늘 반팔 입어도 돼?": 2026-11-23 시뮬에서 GPT로 갔음. 날씨로 네/아니요를 바로 답합니다. */
    static final List<String> LIGHT_WORDS = List.of("반팔", "반바지", "민소매", "나시", "얇게", "얇은옷");
    static final List<String> MAY_WORDS = List.of("입어도", "입고가도", "입고나가도", "괜찮", "될까", "되나", "신어도",
            "신고가도");
    /** "목도리 해야 돼?", "장갑 챙길까?": 2026-11-23 시뮬에서 GPT로 갔음. 체감 온도로 네/아니요를 바로 답합니다. */
    static final List<String> WARM_ITEMS = List.of("목도리", "머플러", "장갑", "털모자", "귀마개", "핫팩", "내복", "히트텍");
    static final List<String> NEED_WORDS = List.of("해야", "챙길", "챙겨", "필요", "할까", "낄까", "껴야", "쓸까", "써야",
            "입을까", "입어야", "가져갈", "가져가", "안껴도", "안해도", "안써도", "안챙겨도", "안입어도");
    /** "바지 몇 벌 있어?", "자켓 있어?": 2026-11-23 시뮬에서 GPT로 갔음. 옷장의 그 종류 목록을 읽어 줍니다. */
    private static final java.util.regex.Pattern OWN_ASK = java.util.regex.Pattern.compile(
            "(몇벌|몇개|몇벌이야|몇개야|있어|있나|있니|있는지|있을까|가지고있|갖고있)[?？!.~]*$");

    /** "할 줄 아는 거 말해 봐", "기능 설명해 줘" 같은 말. 2026-11-06 진행 님 폰 확인에서 못 알아들었던 말. */
    private static final List<String> HELP_WORDS = List.of("기능", "할줄", "할수있", "뭐해줄", "뭘해줄", "뭐도와", "뭘도와",
            "무엇을도와", "뭐할수", "뭘할수", "무엇을할", "능력", "너소개", "자기소개", "사용법", "어떻게써",
            // 2026-11-23 시뮬: "도움말"이 GPT로 갔음
            "도움말", "어떻게사용", "쓰는법", "쓰는방법", "사용방법");

    private static final List<String> PAST_DAY_WORDS = List.of("어제", "그제", "그저께", "지난주", "저번주", "지난번",
            "저번에", "일주일");

    /** inOutfitSession: 옷 추천 대화 중인지 (마무리·"어디야?"는 그때만). */
    /** "치마 입을까?", "반팔 입을까?": 뭐/무슨이 없으면 고를 옷이 아니라 그 옷을 입어도 되는지 물은 말. */
    static boolean shall(String s) {
        return (s.contains("입을까") || s.contains("신을까")) && !s.matches(".*(뭐|뭘|무슨|어떤|어느|무엇|추천).*");
    }

    /** "반팔 입어도 돼?", "반팔 입을까?" */
    static boolean lightMay(String s) {
        return containsAny(s, LIGHT_WORDS) && (containsAny(s, MAY_WORDS) || shall(s));
    }

    /** "치마 입어도 돼?", "청바지 괜찮아?": 2026-11-23 시뮬에서 GPT로 갔음. 날씨로 네/아니요를 답합니다. */
    static boolean wearMay(String s) {
        // "치마 입을까?"도(뭐/무슨이 없으면 고를 옷을 물은 게 아니라 그 옷을 입어도 되는지 물은 말).
        // "청바지에 니트 어때?"(2026-11-23 시뮬). "이 니트 어때?"는 비춘 옷을 묻는 말이라 뺍니다.
        boolean how = s.contains("어때") && !s.startsWith("이") && !s.startsWith("요");
        return ClosetView.category(s) != null && (containsAny(s, MAY_WORDS) || shall(s) || how) && !containsAny(s, LIGHT_WORDS)
                && !s.contains("어울") && !s.contains("옷장");
    }

    public static Intent of(String utterance, boolean inOutfitSession) {
        String s = utterance == null ? "" : utterance.replaceAll("\\s+", "");
        if (inOutfitSession && containsAny(s, FINISH_WORDS)) {
            return FINISH;
        }
        if (inOutfitSession && containsAny(s, SHOWN_WORDS)) {
            return SHOWN;
        }
        if (inOutfitSession && s.contains("어디")) {
            return WHERE;
        }
        // 옷 추천 중 "입고 볼게", "내려놓고 볼게". "내려놓고 볼게, 내일 뭐 입지?"는 새 추천이라 아래 OUTFIT으로.
        if (inOutfitSession && OutfitFlow.switchFrom(s) != null && !containsAny(s, OUTFIT_WORDS)) {
            return MODE;
        }
        // "옷장 확인해 줘", "옷장에 뭐 있어?": 옷장 목록. "옷장에서 옷 추천해 줘"는 추천으로.
        // 2026-11-12 박창근 님 폰: 키워드가 없어 GPT로 갔고 옷 추천으로 넘어갔습니다.
        if (s.contains("옷장") && containsAny(s, CLOSET_REMOVE_WORDS)) {
            return CLOSET_REMOVE;
        }
        // "어제 뭐 입었지?", "지난주에 뭐 입었어?": 입은 날 기록 (2026-11-12 시뮬: '뭐입'에 걸려 추천으로 갔음)
        if (s.contains("입었") && containsAny(s, List.of("뭐", "뭘", "무슨", "어떤", "무엇"))) {
            return CLOSET_HISTORY;
        }
        // "제일 많이 입은 옷 뭐야?": '입은옷'(옷 검사)에 걸려 카메라 방식을 물었음(2026-11-23 시뮬).
        // 최근 기록 3개로는 답이 안 맞아서 GPT가 옷장 도구로 답합니다.
        if (s.matches(".*(많이|자주|제일|가장)입(은|었).*")) {
            return OTHER;
        }
        // "어제 입은 옷 뭐야?"는 '입은옷'(옷 검사)에 걸려 카메라가 켜졌습니다(2026-11-23 점검). 지난날 + 입은 = 기록.
        if (s.contains("입은") && containsAny(s, PAST_DAY_WORDS) && !containsAny(s, OUTFIT_WORDS)) {
            return CLOSET_HISTORY;
        }
        if (s.contains("옷장") && !containsAny(s, OUTFIT_WORDS) && !containsAny(s, SCHEDULE_CHANGE_WORDS)) {
            return CLOSET;
        }
        // "코트 빼줘": 옷장을 말하지 않아도 옷 종류를 빼 달라면 옷장에서 빼기(2026-11-23 시뮬에서 GPT로 갔음).
        if (!inOutfitSession && ClosetView.category(s) != null
                && s.matches(".*(빼줘|빼주세요|삭제해|지워줘|버렸어|없애줘)[?？!.~]*")) {
            return CLOSET_REMOVE;
        }
        // "패딩만 보여줘", "셔츠 목록 알려줘": 옷장에서 그 종류만(2026-11-23 시뮬에서 GPT로 갔음).
        if (!inOutfitSession && ClosetView.category(s) != null
                && s.matches(".*(만|들|목록|전부|다)(보여줘|알려줘|말해줘|읽어줘)[?？!.~]*")) {
            return CLOSET;
        }
        if (!inOutfitSession && ClosetView.category(s) != null && OWN_ASK.matcher(s).find()
                && !s.contains("있어야") && !containsAny(s, STAIN_WORDS) && !containsAny(s, OUTFIT_WORDS)) {
            return CLOSET;
        }
        // "이거 무슨 색이야?": 색만 바로 읽어 줌(2026-11-13). "내일 무슨 색 입을까?"는 추천으로.
        if (ColorFlow.isColorQuestion(s) && !containsAny(s, OUTFIT_WORDS)) {
            return COLOR;
        }
        if (containsAny(s, CHECK_WORDS) && !s.contains("얼굴") && !FaceFlow.isTeeth(s)) {
            return CHECK;
        }
        // "얼룩 있는지 봐 줘", "셔츠에 뭐 묻었는지 봐 줘": 2026-11-23 시뮬에서 키워드에 안 걸렸음(GPT는 옷 검사를 고를 수 없음).
        if (containsAny(s, STAIN_WORDS) && !FaceFlow.isTeeth(s) && !FaceFlow.isMakeup(s)) {
            return CHECK;
        }
        // 추천 없이 처음부터 "입고 볼게", "내려놓고 보여줄게": 2026-11-23 시뮬에서 "할 수 없는 일이에요"로 답했음.
        // 옷 검사처럼 그 방식으로 바로 카메라를 켭니다.
        if (!inOutfitSession && OutfitFlow.switchFrom(s) != null && !containsAny(s, OUTFIT_WORDS)
                && !s.contains("얼굴") && !FaceFlow.isTeeth(s)) {
            return CHECK;
        }
        // 추천 없이 "따뜻하게 입고 싶어": 그 느낌으로 추천(2026-11-23 시뮬에서 GPT로 갔음).
        if (s.matches(".*(따뜻|시원|편하|편한|가볍|가벼|단정|깔끔|캐주얼|포멀|격식)\\S*(입고싶|입을래|입고갈래|입고가고싶).*")) {
            return OUTFIT;
        }
        // "장화 신을까?": "신을까"가 추천으로 갔음(2026-11-23 시뮬). 비 오는지로 답합니다.
        if (containsAny(s, OUTFIT_WORDS) && !wearMay(s) && !lightMay(s) && !s.matches(".*(장화|우비|레인부츠|레인코트).*")) {
            return OUTFIT;
        }
        if (s.contains("얼굴") || s.contains("화장") || FaceFlow.isTeeth(s) || FaceFlow.isMakeup(s)) {
            return FACE;
        }
        if (s.contains("날씨") || containsAny(s.replace("준비", ""), WEATHER_WORDS)
                || lightMay(s)
                || (containsAny(s, WARM_ITEMS) && (containsAny(s, NEED_WORDS) || s.replaceAll("[?.!~]", "").length() <= 5))
                || wearMay(s)
                // "토요일 비", "내일 눈": 날 + 비/눈만 말한 것(2026-11-23 시뮬)
                || s.matches("(오늘|내일|모레|글피|이번주|다음주)?[월화수목금토일]?(요일)?(에)?(비|눈)[?？]*")
                        && s.matches(".*(오늘|내일|모레|글피|요일).*")                // "오늘 겉옷 필요 없지?"(2026-11-23 시뮬)
                || (s.matches(".*(겉옷|외투|패딩|코트|점퍼|바람막이).*") && s.matches(".*(필요|없지|안입어도|안챙겨도|챙겨야|챙길까|가져갈까).*"))) {
            // "이번 주 날씨"처럼 여러 날은 GPT가 날짜를 하나씩 봅니다. 하루("내일", "토요일")는 바로 답합니다.
            if (containsAny(s, MULTI_DAY_WORDS) && !s.contains("요일")) {
                return OTHER;
            }
            // "오늘이랑 내일 날씨 비교해 줘": 두 날을 같이 물으면 GPT가 하나씩 봅니다(2026-11-23 시뮬: 내일만 답했음).
            if (s.matches(".*(오늘|내일|모레)(이랑|하고|과|와|랑).*(오늘|내일|모레|글피).*")) {
                return OTHER;
            }
            return WEATHER;
        }
        // "오늘 약속 있어?" 2026-11-23 시뮬
        // "내일 몇 시에 나가야 돼?": 그날 일정을 읽어 줍니다(2026-11-23 시뮬).
        if ((s.contains("일정") || s.contains("스케줄") || s.contains("약속")
                || s.matches(".*몇시에(나가|나서|출발|가야|일어나).*")
                // "12월 25일에 뭐 있어?", "어제 뭐 있었지?"(2026-11-23 시뮬)
                || (s.matches(".*(오늘|내일|모레|글피|어제|그제|그저께|요일|\\d{1,2}월\\d{1,2}일).*")
                        && s.matches(".*(뭐있|무슨일있|뭐가있|무슨일정).*")))
                && !containsAny(s, SCHEDULE_CHANGE_WORDS)
                && !(containsAny(s, MULTI_DAY_WORDS) && !s.contains("요일"))) {
            return SCHEDULE;
        }
        if (containsAny(s, HELP_WORDS)) {
            return HELP;
        }
        return OTHER;
    }

    private static boolean containsAny(String s, List<String> words) {
        return words.stream().anyMatch(s::contains);
    }
}
