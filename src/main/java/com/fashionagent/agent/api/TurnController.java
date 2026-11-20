package com.fashionagent.agent.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fashionagent.agent.flow.Prefetch;
import com.fashionagent.agent.flow.TurnService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 앱과 대화하는 곳은 이 경로 하나입니다. 게이트웨이가 JWT를 확인하고 X-User-Id를 붙여서 넘깁니다.
 * 개발 중에는 게이트웨이 없이 X-User-Id 헤더를 직접 넣어 부르면 됩니다.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class TurnController {

    /** base64 약 6MB 사진 ≈ 8M 글자 (약속 1절). */
    static final int MAX_IMAGE_CHARS = 8_200_000;

    private final TurnService turns;
    private final Prefetch prefetch;

    public TurnController(TurnService turns, Prefetch prefetch) {
        this.turns = turns;
        this.prefetch = prefetch;
    }

    /**
     * 앱이 켜지거나 다시 앞으로 올 때 보냅니다. 첫 "오늘 뭐 입지?"에 쓸 날씨·오늘 일정을 미리 불러 두고 바로 202.
     * 본문은 없어도 됩니다({"location": {...}} 선택). 불러오기 결과는 돌려주지 않습니다.
     */
    @PostMapping("/warm")
    public ResponseEntity<Void> warm(@RequestHeader("X-User-Id") String userId,
            @RequestBody(required = false) WarmRequest request) {
        if (userId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "X-User-Id가 비었어요.");
        }
        prefetch.start(userId, request == null ? null : request.location());
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/turn")
    public TurnResponse turn(@RequestHeader("X-User-Id") String userId,
            @RequestBody TurnRequest request) {
        if (userId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "X-User-Id가 비었어요.");
        }
        if (!request.hasImage() && !request.hasUtterance()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "utterance나 image 중 하나는 있어야 해요.");
        }
        if (request.hasImage() && request.image().length() > MAX_IMAGE_CHARS) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "image_too_large", "사진이 6MB를 넘어요.");
        }
        long started = System.nanoTime();
        TurnResponse response = turns.handle(userId, request, wearer(request.wearer()));
        // 응답 시간 확인용(2026-11-13). 말·사진 내용은 남기지 않고 길이와 다음 동작만.
        log.info("[시간] 차례 {}ms ({}, next={})", (System.nanoTime() - started) / 1_000_000,
                request.hasImage() ? "사진 " + request.image().length() * 3 / 4 / 1024 + "KB" : "말",
                response == null ? null : response.next());
        return response;
    }

    private static final Logger log = LoggerFactory.getLogger(TurnController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** 바디의 wearer → 있는 칸만 남긴 JSON(GPT에 넘김). 비었으면 null(정보 없이 대화). */
    static JsonNode wearer(WearerProfile w) {
        if (w == null) {
            return null;
        }
        ObjectNode out = JSON.createObjectNode();
        putText(out, "gender", w.gender());
        putText(out, "ageGroup", w.ageGroup());
        if (w.heightCm() != null) {
            out.put("heightCm", w.heightCm());
        }
        if (w.weightKg() != null) {
            out.put("weightKg", w.weightKg());
        }
        putText(out, "job", w.job());
        return out.isEmpty() ? null : out;
    }

    private static void putText(ObjectNode out, String key, String v) {
        if (v != null && !v.isBlank()) {
            out.put(key, v.strip());
        }
    }
}
