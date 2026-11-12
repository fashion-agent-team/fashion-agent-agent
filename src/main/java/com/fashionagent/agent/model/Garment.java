package com.fashionagent.agent.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 옷 한 벌 (mcp-tools.md "공통 모양: Garment"). clothes_inspect가 만들고 closet이 저장합니다.
 * position과 defects.where는 모두 바라보는 사람(사진) 기준입니다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Garment(
        String id,
        String category,
        String name,
        String colorName,
        String colorFamily,
        String tone,
        String pattern,
        String material,
        String notes,
        String formality,
        List<Defect> defects,
        Box box,
        String position,
        Double confidence,
        /** 무늬 옷의 두 번째 색(옷 상태 서버 subColorName). "검정·베이지 체크 셔츠"처럼 읽으려고. 없으면 null. */
        String subColorName,
        String subColorFamily,
        /** pattern을 정한 쪽: model | gpt(모델이 몰라서 GPT가 봄). */
        String patternCheckedBy,
        /** 무늬를 확실히 모를 때(pattern=null) 모델 1등 무늬. "호피 무늬 같아요"처럼 약하게만 말함. */
        String patternGuess) {

    public Garment {
        defects = defects == null ? List.of() : List.copyOf(defects);
    }

    public Garment(String id, String category, String name, String colorName, String colorFamily, String tone,
            String pattern, String material, String notes, String formality, List<Defect> defects, Box box,
            String position, Double confidence) {
        this(id, category, name, colorName, colorFamily, tone, pattern, material, notes, formality, defects, box,
                position, confidence, null, null, null, null);
    }

    /** 다른 방향(뒤)에서 찾은 얼룩·찢어짐을 더한 옷. */
    public Garment withMoreDefects(List<Defect> more) {
        var all = new java.util.ArrayList<>(defects);
        all.addAll(more);
        return new Garment(id, category, name, colorName, colorFamily, tone, pattern, material, notes,
                formality, all, box, position, confidence, subColorName, subColorFamily, patternCheckedBy, patternGuess);
    }

    public Garment withId(String newId) {
        return new Garment(newId, category, name, colorName, colorFamily, tone, pattern, material, notes,
                formality, defects, box, position, confidence, subColorName, subColorFamily, patternCheckedBy, patternGuess);
    }

    /**
     * 읽어 줄 이름: 색(무늬 옷이면 두 색) + 무늬 + 종류. 예: "흰색 셔츠", "검정·베이지 체크 셔츠", "검정·회색 호피 무늬 셔츠".
     * 2026-11-12 실기 테스트: 체크 셔츠·호피 셔츠를 둘 다 "검정 셔츠"라고 해서 무늬와 두 번째 색을 붙임.
     */
    public String label() {
        String color = colorName == null || colorName.isBlank() ? ""
                : colorName + (subColorName == null || subColorName.isBlank() ? "" : "·" + subColorName);
        String kind = name == null ? "옷" : name;
        String pat = patternName(pattern);
        if ("other".equals(pattern)) {
            // "무늬 있는 검정 셔츠"
            return pat + " " + (color.isEmpty() ? "" : color + " ") + kind;
        }
        return (color.isEmpty() ? "" : color + " ") + (pat.isEmpty() ? "" : pat + " ") + kind;
    }

    /**
     * 무늬 코드 → 읽을 말. plain·null은 "" (무지는 말하지 않음). animal은 학습 데이터 대부분이 호피라 "호피 무늬"로 읽습니다.
     */
    public static String patternName(String pattern) {
        return switch (pattern == null ? "" : pattern) {
            case "stripe" -> "줄무늬";
            case "check" -> "체크";
            case "floral" -> "꽃무늬";
            case "dot" -> "물방울무늬";
            case "graphic" -> "그림 있는";
            case "animal" -> "호피 무늬";
            case "other" -> "무늬 있는";
            default -> "";
        };
    }

    /** 무지가 아닌 무늬로 확인된 옷(plain·모름은 아님). */
    public boolean patterned() {
        return pattern != null && !pattern.isBlank() && !"plain".equals(pattern);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    /** checkedBy: model(우리 모델이 확실) | gpt(애매해서 조각을 GPT로 확인, 옷 상태 서버 #10). 없으면 model. */
    public record Defect(String kind, String where, double confidence, String checkedBy) {
        public Defect(String kind, String where, double confidence) {
            this(kind, where, confidence, null);
        }

        public boolean byGpt() {
            return "gpt".equals(checkedBy);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Box(double x, double y, double w, double h) {
    }
}
