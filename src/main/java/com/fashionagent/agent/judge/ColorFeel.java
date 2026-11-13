package com.fashionagent.agent.judge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fashionagent.agent.model.Garment;

/**
 * 색을 감각과 느낌으로 풀어 말합니다(2026-11-13 진행 님 결정).
 * 근거: 선천적 시각장애인도 색-감정 연결을 말로 배워 비장애인과 거의 같이 가집니다
 * (Kim·Elli·Bedny 2019 PNAS, Saysani 외 2021, Jonauskaite 외 2025 Acta Psychologica).
 * <ol>
 * <li>색 이름이 먼저(옷 이름 label에 이미 들어감), 설명은 그 뒤.</li>
 * <li>한 문장에 물건 비유 + 온도·밝기 + 느낌.</li>
 * <li>온도(따뜻한/시원한)와 밝기(밝은/어두운)를 기본 축으로.</li>
 * <li>빨강·초록은 시각장애인이 잘 구별하지 못한다는 결과가 있어 "이름만"이어도 비유를 붙입니다.</li>
 * <li>"색 설명 짧게/자세히"로 바꿉니다(앱이 기억해 colorDetail로 보냄).</li>
 * </ol>
 * 중요한 일정(조문·결혼식·면접·회의)이면 그 자리에 어울리는 색인지도 말합니다.
 */
public final class ColorFeel {

    /** simile: 물건 비유, quality: 온도·밝기, mood: 느낌. */
    record Feel(String simile, String quality, String mood, String shortSimile) {
    }

    static final Map<String, Feel> FEELS = Map.ofEntries(
            Map.entry("black", new Feel("깊은 밤", "어둡고 묵직한", "단정하고 차분한", null)),
            Map.entry("white", new Feel("갓 빨아 말린 이불", "밝고 가벼운", "깨끗하고 산뜻한", null)),
            Map.entry("gray", new Feel("비 오기 전 흐린 하늘", "따뜻하지도 차갑지도 않은 중간", "조용하고 무난한", null)),
            Map.entry("navy", new Feel("깊은 바닷물", "시원하고 어두운", "믿음직하고 단정한", null)),
            Map.entry("beige", new Feel("햇볕 받은 모래", "따뜻하고 밝은", "부드럽고 편안한", null)),
            Map.entry("brown", new Feel("나무나 갓 구운 빵 껍질", "따뜻하고 어두운", "포근하고 차분한", null)),
            Map.entry("khaki", new Feel("마른 풀과 흙", "살짝 따뜻하고 차분한", "편하고 자연스러운", null)),
            Map.entry("blue", new Feel("맑은 날 시원한 물", "시원하고 밝은", "상쾌하고 차분한", null)),
            Map.entry("red", new Feel("잘 익은 딸기나 따뜻한 불", "뜨겁고 강한", "힘 있고 눈에 띄는", "잘 익은 딸기")),
            Map.entry("pink", new Feel("복숭아 껍질", "따뜻하고 부드러운", "다정하고 사랑스러운", null)),
            Map.entry("orange", new Feel("새콤달콤한 귤", "따뜻하고 밝은", "활기차고 즐거운", null)),
            Map.entry("yellow", new Feel("봄 햇살", "따뜻하고 아주 밝은", "기분 좋고 경쾌한", null)),
            Map.entry("green", new Feel("막 깎은 풀 냄새가 나는 잔디", "시원하고 싱그러운", "편안하고 생기 있는", "싱그러운 풀")),
            Map.entry("purple", new Feel("라벤더 꽃", "서늘하고 깊은", "우아하고 신비로운", null)));

    /** 자리마다 잘 어울리는 색 계열과 그 까닭(색마다 한마디). */
    private static final Map<String, String> WHY = Map.of(
            "navy", "신뢰감을 줘요", "gray", "차분해 보여요", "black", "단정해 보여요", "white", "깔끔해 보여요",
            "beige", "부드럽고 단정해 보여요", "blue", "산뜻하고 깔끔해 보여요", "brown", "점잖아 보여요",
            "khaki", "차분해 보여요", "pink", "화사하면서 단정해 보여요", "purple", "우아해 보여요");

    private static final Map<Occasion, Set<String>> SUITS = Map.of(
            Occasion.mourning, Set.of("black", "navy", "gray"),
            Occasion.wedding, Set.of("navy", "beige", "gray", "blue", "pink", "purple", "brown", "khaki"),
            Occasion.formal, Set.of("navy", "black", "gray", "white", "beige"),
            Occasion.business, Set.of("navy", "gray", "black", "white", "beige", "blue", "khaki", "brown"));

    private static final Map<Occasion, String> PLACE = Map.of(
            Occasion.mourning, "조문 자리", Occasion.wedding, "결혼식",
            Occasion.formal, "격식 있는 자리", Occasion.business, "일하는 자리");

    /** 한 번에 풀어 말하는 옷 수. 길어지지 않게. */
    static final int MAX_PER_TURN = 2;

    /**
     * 옷 한 벌의 색 설명. detailed=false(이름만)면 빨강·초록만 짧은 비유. 색을 모르면 "".
     * 예: "베이지는 햇볕 받은 모래처럼 따뜻하고 밝은 색이라, 부드럽고 편안한 느낌이에요."
     */
    public static String describe(Garment g, boolean detailed) {
        Feel f = g.colorFamily() == null ? null : FEELS.get(g.colorFamily());
        String name = colorName(g);
        if (f == null || name == null) {
            return "";
        }
        if (!detailed) {
            return f.shortSimile() == null ? ""
                    : Korean.josa(name, "은", "는") + " " + f.shortSimile() + " 같은 색이에요.";
        }
        return Korean.josa(name, "은", "는") + " " + f.simile() + "처럼 " + f.quality() + " 색이라, " + f.mood()
                + " 느낌이에요.";
    }

    /**
     * 중요한 일정이면 그 자리에 이 색이 잘 어울리는지. 어울리지 않거나 평범한 날이면 "".
     * 어울리지 않는 경우는 판단(fit)이 이미 말합니다(예: 결혼식 흰 상의).
     * 예: "남색은 면접에 잘 어울리는 색이에요. 믿음직해 보여요."
     */
    public static String forEvent(Garment g, Occasion occasion, String title) {
        return forEvent(g, occasion, title, true);
    }

    /** withName=false면 바로 앞에서 그 색을 말했으니 색 이름 없이 "면접에 잘 어울리는 색이에요."로. */
    static String forEvent(Garment g, Occasion occasion, String title, boolean withName) {
        Set<String> suits = SUITS.get(occasion);
        String name = colorName(g);
        if (suits == null || name == null || g.colorFamily() == null || !suits.contains(g.colorFamily())) {
            return "";
        }
        String place = title == null || title.isBlank() ? PLACE.get(occasion) : title;
        return (withName ? Korean.josa(name, "은", "는") + " " : "") + place + "에 잘 어울리는 색이에요. " + WHY.get(g.colorFamily()) + ".";
    }

    /**
     * 이번 사진의 옷들에 붙일 색 문장. 이번 대화에서 이미 말한 색 계열(told)은 건너뛰고, told에 더합니다.
     * 일정 문장은 이미 말한 색이어도 처음 한 번은 말합니다(toldEvent).
     */
    public static List<String> sentences(List<Garment> garments, boolean detailed, Occasion occasion, String title,
            List<String> told, List<String> toldEvent) {
        List<String> out = new ArrayList<>();
        int described = 0;
        for (Garment g : garments) {
            if (described >= MAX_PER_TURN) {
                break;
            }
            String family = g.colorFamily();
            if (family == null || !FEELS.containsKey(family) || colorName(g) == null) {
                continue;
            }
            boolean any = false;
            if (!told.contains(family)) {
                String d = describe(g, detailed);
                if (!d.isEmpty()) {
                    out.add(d);
                    any = true;
                }
                told.add(family);
            }
            if (!toldEvent.contains(family)) {
                String e = forEvent(g, occasion, title, !any);
                if (!e.isEmpty()) {
                    out.add(e);
                    any = true;
                }
                toldEvent.add(family);
            }
            if (any) {
                described++;
            }
        }
        return out;
    }

    /** 판단 문장의 첫 문장(어느 옷인지·맞는지) 뒤에 색 문장을 넣습니다. 끝 안내("이걸로 할게" 등)는 맨 뒤에 남습니다. */
    public static String insertAfterFirst(String speech, List<String> extra) {
        if (extra.isEmpty()) {
            return speech;
        }
        String s = speech == null ? "" : speech.strip();
        String add = String.join(" ", extra);
        int end = firstSentenceEnd(s);
        if (end < 0) {
            return s.isEmpty() ? add : s + " " + add;
        }
        return s.substring(0, end) + " " + add + " " + s.substring(end).strip();
    }

    private static int firstSentenceEnd(String s) {
        for (int i = 0; i < s.length() - 1; i++) {
            char c = s.charAt(i);
            if ((c == '.' || c == '?' || c == '!') && s.charAt(i + 1) == ' ') {
                return i + 1;
            }
        }
        return -1;
    }

    private static final Pattern SHORTER = Pattern.compile("색.*(짧게|간단|이름만|빼|그만|안 해|하지 ?마)");
    private static final Pattern LONGER = Pattern.compile("색.*(자세히|길게|느낌|풀어)");
    private static final Pattern ABOUT = Pattern.compile("색.*(설명|이름만|느낌)");

    /** "색 설명 짧게 해 줘" → false, "색 설명 자세히 해 줘" → true, 다른 말이면 null. */
    public static Boolean detailCommand(String utterance) {
        if (utterance == null) {
            return null;
        }
        String u = utterance.strip();
        if (!ABOUT.matcher(u).find()) {
            return null;
        }
        if (SHORTER.matcher(u).find()) {
            return false;
        }
        if (LONGER.matcher(u).find()) {
            return true;
        }
        return null;
    }

    public static final String SAY_SHORT = "알겠어요. 이제 색은 이름만 말씀드릴게요. 빨강과 초록은 헷갈리기 쉬워서 짧은 비유만 붙일게요.";
    public static final String SAY_DETAILED = "좋아요. 이제 색을 말할 때 어떤 느낌인지도 같이 말씀드릴게요.";

    /** 읽을 색 이름. 무늬 옷의 두 번째 색은 설명하지 않고 첫 색만. */
    private static String colorName(Garment g) {
        return g.colorName() == null || g.colorName().isBlank() ? null : g.colorName();
    }

    private ColorFeel() {
    }
}
