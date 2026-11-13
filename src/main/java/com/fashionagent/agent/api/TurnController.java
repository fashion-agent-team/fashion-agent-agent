package com.fashionagent.agent.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public TurnController(TurnService turns) {
        this.turns = turns;
    }

    @PostMapping("/turn")
    public TurnResponse turn(@RequestHeader("X-User-Id") String userId,
            @RequestHeader(value = "X-User-Profile", required = false) String profile,
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
        return turns.handle(userId, request, wearer(profile));
    }

    private static final Logger log = LoggerFactory.getLogger(TurnController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /** 게이트웨이가 넣는 칸(옷 판단에 쓰는 내 정보만). 다른 칸이 와도 GPT에 넘기지 않습니다. */
    static final Set<String> WEARER_FIELDS = Set.of("gender", "ageGroup", "heightCm", "weightKg", "job");

    /** X-User-Profile(base64url JSON) → 정해진 칸만 남긴 JSON. 없거나 깨졌으면 null(정보 없이 대화). */
    static JsonNode wearer(String header) {
        if (header == null || header.isBlank() || header.length() > 2048) {
            return null;
        }
        try {
            JsonNode raw = JSON.readTree(new String(Base64.getUrlDecoder().decode(header.strip()), StandardCharsets.UTF_8));
            if (raw == null || !raw.isObject()) {
                return null;
            }
            ObjectNode out = JSON.createObjectNode();
            for (String f : WEARER_FIELDS) {
                JsonNode v = raw.get(f);
                if (v != null && (v.isNumber() || (v.isString() && !v.asString().isBlank()))) {
                    out.set(f, v);
                }
            }
            return out.isEmpty() ? null : out;
        } catch (RuntimeException e) {
            log.warn("X-User-Profile을 읽지 못했어요: {}", e.toString());
            return null;
        }
    }
}
