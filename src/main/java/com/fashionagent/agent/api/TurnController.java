package com.fashionagent.agent.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fashionagent.agent.flow.TurnService;

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
    public TurnResponse turn(@RequestHeader("X-User-Id") String userId, @RequestBody TurnRequest request) {
        if (userId.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthorized", "X-User-Id가 비었어요.");
        }
        if (!request.hasImage() && !request.hasUtterance()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "bad_request", "utterance나 image 중 하나는 있어야 해요.");
        }
        if (request.hasImage() && request.image().length() > MAX_IMAGE_CHARS) {
            throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "image_too_large", "사진이 6MB를 넘어요.");
        }
        return turns.handle(userId, request);
    }
}
