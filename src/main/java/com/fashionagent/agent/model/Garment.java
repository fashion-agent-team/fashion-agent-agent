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
        Double confidence) {

    public Garment {
        defects = defects == null ? List.of() : List.copyOf(defects);
    }

    /** 다른 방향(옆·뒤)에서 찾은 얼룩·찢어짐을 더한 옷. */
    public Garment withMoreDefects(List<Defect> more) {
        var all = new java.util.ArrayList<>(defects);
        all.addAll(more);
        return new Garment(id, category, name, colorName, colorFamily, tone, pattern, material, notes,
                formality, all, box, position, confidence);
    }

    public Garment withId(String newId) {
        return new Garment(newId, category, name, colorName, colorFamily, tone, pattern, material, notes,
                formality, defects, box, position, confidence);
    }

    /** "흰색 셔츠"처럼 읽어 줄 이름. */
    public String label() {
        String color = colorName == null || colorName.isBlank() ? "" : colorName + " ";
        return color + (name == null ? "옷" : name);
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Defect(String kind, String where, double confidence) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Box(double x, double y, double w, double h) {
    }
}
