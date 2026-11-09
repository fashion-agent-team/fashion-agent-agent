package com.fashionagent.agent.judge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fashionagent.agent.model.AdviceJudgement;
import com.fashionagent.agent.model.CheckJudgement;
import com.fashionagent.agent.model.Garment;
import com.fashionagent.agent.model.OutfitAdvice.Suggestion;
import com.fashionagent.agent.model.OutfitCheck.Comparison;
import com.fashionagent.agent.model.OutfitCheck.Damage;
import com.fashionagent.agent.model.OutfitCheck.Verdict;

import tools.jackson.databind.JsonNode;

/**
 * GPT 없이 판단하는 규칙. 약속 5-1절 "판단 기준"을 코드로 옮긴 것입니다.
 * 키 없이 로컬에서 돌릴 때, 그리고 GPT가 실패하거나 25초 안에 답하지 않을 때 씁니다.
 */
public class RuleOutfitJudge implements OutfitJudge {

    /** 얼룩·찢어짐 확신도 기준 (약속: 0.7 이상은 할 일, 0.5~0.7은 check, 0.5 미만은 버림). */
    static final double SURE = 0.7;
    static final double MAYBE = 0.5;

    private record Look(String top, String bottom, String why) {
    }

    private static final Map<Occasion, Look> LOOKS = Map.of(
            Occasion.mourning, new Look("검은색이나 어두운 무채색 상의", "검은색 바지나 치마", "조문 자리라 어둡고 무늬 없는 옷이 좋아요."),
            Occasion.wedding, new Look("흰색이 아닌 단정한 상의", "단정한 슬랙스나 치마", "결혼식에는 흰색 상의를 피하는 게 예의예요."),
            Occasion.formal, new Look("셔츠나 단정한 니트", "어두운 색 슬랙스", "격식 있는 자리라 단정한 옷이 좋아요."),
            Occasion.business, new Look("셔츠나 니트", "슬랙스나 면바지", "일하는 자리라 운동복은 피하는 게 좋아요."),
            Occasion.active, new Look("편한 운동복 상의", "운동복 바지", "움직이기 편한 옷이 좋아요."),
            Occasion.casual, new Look("편한 상의", "편한 바지", "특별한 일정이 없어요."));

    @Override
    public AdviceJudgement advise(AdviceInput in) {
        Occasion occasion = Occasion.strictest(in.events());
        String title = Occasion.titleOf(in.events(), occasion);
        Look look = LOOKS.get(occasion);
        Weather w = Weather.of(in.weather());

        List<Suggestion> suggestions = new ArrayList<>();
        suggestions.add(new Suggestion("top", look.top(), look.why()));
        suggestions.add(new Suggestion("bottom", look.bottom(), look.why()));
        if (w.needsOuter()) {
            suggestions.add(new Suggestion("outer", "겉옷", w.outerReason()));
        }

        // 날짜·날씨·일정은 OutfitFlow가 앞에서 읽습니다(2026-11-09 발표 피드백: 정해진 순서로).
        // 여기서는 추천하는 옷과 까닭만 말합니다.
        StringBuilder speech = new StringBuilder();
        if (title != null && occasion != Occasion.casual) {
            speech.append(look.why()).append(" ");
        }
        speech.append(look.top()).append("에 ");
        speech.append(w.needsOuter() ? look.bottom() + ", 겉옷을 추천드려요. " + w.outerReason()
                : Korean.josa(look.bottom(), "을", "를") + " 추천드려요.");
        if (!w.known()) {
            speech.append(" 날씨를 몰라서 겉옷도 챙겨 두면 좋아요.");
        }
        if (w.needsUmbrella()) {
            speech.append(" 비 소식이 있으니 우산도 챙기세요.");
        }
        return new AdviceJudgement(suggestions, List.of(), speech.toString());
    }

    @Override
    public CheckJudgement check(CheckInput in) {
        Occasion occasion = Occasion.strictest(in.events());
        String title = Occasion.titleOf(in.events(), occasion);
        String place = title != null ? title + "에" : "오늘";

        Weather w = Weather.of(in.weather());
        List<Verdict> verdicts = new ArrayList<>();
        for (Garment g : in.now()) {
            Fit fit = withWeather(fit(g, occasion), g, w);
            verdicts.add(new Verdict(g.id(), g.position(), fit.fit(), List.of(fit.note())));
        }
        List<Damage> damage = new ArrayList<>();
        for (Garment g : in.now()) {
            for (Garment.Defect d : g.defects()) {
                Damage dm = damage(g, d);
                if (dm != null) {
                    damage.add(dm);
                }
            }
        }

        Comparison comparison = null;
        if (in.now().size() >= 2) {
            Garment best = in.now().stream()
                    .max(Comparator.comparingInt((Garment g) -> rank(verdictOf(verdicts, g.id())))
                            .thenComparingInt(g -> -damageCount(damage, g.id())))
                    .orElseThrow();
            comparison = new Comparison(best.id(), Korean.josa(best.label(), "이", "가") + " " + place
                    + " 더 잘 맞아요.");
        }

        List<String> missing = stillMissing(in, verdicts);

        List<String> sentences = new ArrayList<>();
        if (comparison != null) {
            Garment best = byId(in.now(), comparison.betterInputId());
            Garment other = in.now().stream().filter(g -> g != best).findFirst().orElseThrow();
            sentences.add(Korean.josa(Korean.position(best.position()) + " " + best.label(), "이", "가") + " "
                    + Korean.position(other.position()) + " " + other.label() + "보다 " + place + " 더 잘 맞아요.");
        } else if (!in.now().isEmpty()) {
            Garment g = in.now().get(0);
            sentences.add(Korean.josa(g.label(), "은", "는") + " " + verdicts.get(0).notes().get(0));
        }
        damage.stream().filter(d -> !"ok".equals(d.action())).findFirst()
                .or(() -> damage.stream().findFirst())
                .ifPresent(d -> sentences.add(d.say()));
        if (!missing.isEmpty()) {
            sentences.add(String.join(", ", missing.stream().map(Korean::category).toList()) + "도 보여 주세요.");
        } else {
            sentences.add("마음에 들면 \"이걸로 할게\"라고 말해 주세요.");
        }
        return new CheckJudgement(verdicts, comparison, damage, missing, String.join(" ", sentences));
    }

    private record Fit(String fit, String note) {
    }

    private static Fit fit(Garment g, Occasion occasion) {
        String formality = g.formality() == null ? "unknown" : g.formality();
        boolean patterned = g.pattern() != null && !g.pattern().isBlank();
        boolean dark = Set.of("black", "gray", "navy").contains(g.colorFamily());
        return switch (occasion) {
            case mourning -> dark && !patterned
                    ? new Fit("good", "조문 자리에 알맞은 어두운 색이에요.")
                    : new Fit("bad", "조문 자리에는 어두운 무채색이 좋아요.");
            case wedding -> "top".equals(g.category()) && "white".equals(g.colorFamily())
                    ? new Fit("bad", "결혼식에는 흰색 상의를 피하는 게 좋아요.")
                    : "sporty".equals(formality) ? new Fit("bad", "결혼식에는 운동복이 가벼워 보여요.")
                    : Set.of("formal", "smart").contains(formality) ? new Fit("good", "결혼식에 단정해 보여요.")
                    : new Fit("ok", "결혼식에 무난해요.");
            case formal -> Set.of("formal", "smart").contains(formality) ? new Fit("good", "단정해 보여서 잘 맞아요.")
                    : "casual".equals(formality) && !patterned ? new Fit("ok", "조금 편해 보이지만 무난해요.")
                    : new Fit("bad", patterned ? "무늬가 있어서 조금 가벼워 보여요." : "격식 있는 자리에는 가벼워 보여요.");
            case business -> "sporty".equals(formality) ? new Fit("bad", "일하는 자리에는 운동복을 피하는 게 좋아요.")
                    : "casual".equals(formality) ? new Fit("ok", "무난해요.")
                    : new Fit("good", "깔끔해서 잘 맞아요.");
            case active -> "sporty".equals(formality) ? new Fit("good", "움직이기 편해서 잘 맞아요.")
                    : "casual".equals(formality) ? new Fit("ok", "괜찮지만 운동복이 더 편해요.")
                    : new Fit("bad", "운동하기엔 불편할 수 있어요.");
            case casual -> new Fit("good", "오늘 입기 좋아요.");
        };
    }

    /** 반바지·반팔처럼 얇은 옷 이름. */
    private static final List<String> LIGHT = List.of("반바지", "반팔", "민소매", "나시", "티셔츠");

    /**
     * 날씨를 더해 판단을 고칩니다(2026-11-09 리허설: 추운 날 반바지를 괜찮다고 함).
     * 옷 종류를 모르면(other) 날씨에 맞다고 단정하지 않고, 겉옷이 필요한 날이면 확인을 부탁합니다.
     */
    static Fit withWeather(Fit fit, Garment g, Weather w) {
        if (!w.known()) {
            return fit;
        }
        String name = g.name() == null ? "" : g.name();
        boolean unknownKind = g.category() == null || "other".equals(g.category()) || g.name() == null;
        if (!unknownKind && w.apparent() < 12 && LIGHT.stream().anyMatch(name::contains)) {
            return new Fit("bad", "오늘 체감 " + Math.round(w.apparent()) + "도라 " + Korean.josa(name, "은", "는")
                    + " 추울 수 있어요.");
        }
        if ("outer".equals(g.category()) && w.needsOuter()) {
            return "bad".equals(fit.fit()) ? fit : new Fit(fit.fit(), "오늘 쌀쌀한데 겉옷까지 잘 챙기셨어요.");
        }
        if (unknownKind) {
            String fitValue = "good".equals(fit.fit()) ? "ok" : fit.fit();
            return w.needsOuter()
                    ? new Fit(fitValue, "종류는 확실하지 않아요. 오늘 체감 " + Math.round(w.apparent())
                            + "도라 추우니 겉옷을 입으셨는지 확인해 주세요.")
                    : new Fit(fitValue, fit.note());
        }
        return fit;
    }

    static Damage damage(Garment g, Garment.Defect d) {
        if (d.confidence() < MAYBE) {
            return null;
        }
        String label = g.label();
        if (d.confidence() < SURE || "other".equals(d.kind())) {
            return new Damage(g.id(), d.kind(), "check", d.where(),
                    label + "에 얼룩일 수도 있는 게 보여요. 중요한 자리면 다른 옷이 안전해요.");
        }
        return switch (d.kind()) {
            case "stain" -> new Damage(g.id(), "stain", "wash", d.where(),
                    label + "에 얼룩이 있어요. 오늘은 다른 옷으로 바꾸고, 이건 빨래하는 게 좋겠어요.");
            case "tear" -> new Damage(g.id(), "tear", "repair", d.where(),
                    label + "에 찢어진 곳이 있어요. 오늘은 다른 옷으로 바꾸고, 이건 수선하는 게 좋겠어요.");
            default -> new Damage(g.id(), d.kind(), "ok", d.where(),
                    label + "에 보풀이 조금 있지만 입어도 괜찮아요.");
        };
    }

    private static List<String> stillMissing(CheckInput in, List<Verdict> verdicts) {
        if (in.advice() == null) {
            return List.of();
        }
        // 옷 종류를 모르면 무엇이 빠졌는지도 모릅니다. "겉옷도 보여 주세요"라고 하지 않습니다.
        if (in.now().stream().anyMatch(g -> g.category() == null || "other".equals(g.category()))) {
            return List.of();
        }
        Set<String> covered = new LinkedHashSet<>();
        for (SeenBrief s : in.seenBefore()) {
            if (!"bad".equals(s.verdict())) {
                covered.add(s.category());
            }
        }
        for (Garment g : in.now()) {
            if (!"bad".equals(verdictOf(verdicts, g.id()))) {
                covered.add(g.category());
            }
        }
        if (covered.contains("dress")) {
            covered.add("top");
            covered.add("bottom");
        }
        return in.advice().suggestions().stream().map(Suggestion::category).distinct()
                .filter(c -> !covered.contains(c)).toList();
    }

    private static String verdictOf(List<Verdict> verdicts, String id) {
        return verdicts.stream().filter(v -> v.inputId().equals(id)).map(Verdict::fit).findFirst().orElse("ok");
    }

    private static int rank(String fit) {
        return switch (fit) {
            case "good" -> 2;
            case "ok" -> 1;
            default -> 0;
        };
    }

    private static int damageCount(List<Damage> damage, String id) {
        return (int) damage.stream().filter(d -> d.inputId().equals(id) && !"ok".equals(d.action())).count();
    }

    private static Garment byId(List<Garment> garments, String id) {
        return garments.stream().filter(g -> g.id().equals(id)).findFirst().orElseThrow();
    }

    /** weather_today 결과에서 판단에 쓰는 값만. */
    record Weather(boolean known, double apparent, double range, int rain) {

        static Weather of(JsonNode node) {
            if (node == null || node.isMissingNode() || node.isNull()) {
                return new Weather(false, 20, 0, 0);
            }
            return new Weather(true, node.path("apparentTemperature").asDouble(20),
                    node.path("dailyRange").asDouble(0), node.path("precipitationProbability").asInt(0));
        }

        boolean needsOuter() {
            return known && (apparent < 20 || range >= 10);
        }

        boolean needsUmbrella() {
            return known && rain >= 50;
        }

        String outerReason() {
            return range >= 10 ? "일교차가 " + Math.round(range) + "도라 겉옷이 필요해요."
                    : "체감 " + Math.round(apparent) + "도라 쌀쌀해요.";
        }
    }
}
