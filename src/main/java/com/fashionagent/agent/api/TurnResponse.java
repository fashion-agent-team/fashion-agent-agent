package com.fashionagent.agent.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fashionagent.agent.model.OutfitAdvice;
import com.fashionagent.agent.model.OutfitCheck;

/** POST /api/v1/agent/turn 응답 (openapi.yaml TurnResponse). 앱은 speech만 읽어도 동작합니다. */
public record TurnResponse(String sessionId, String speech, Next next, Result result) {

    public enum Next {
        listen, camera_outfit, camera_worn, camera_face, camera_teeth, done
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Result(String type, OutfitAdvice outfitAdvice, OutfitCheck outfitCheck, String outfitMode) {

        public Result(String type, OutfitAdvice outfitAdvice, OutfitCheck outfitCheck) {
            this(type, outfitAdvice, outfitCheck, null);
        }

        public static Result of(String type) {
            return new Result(type, null, null);
        }
    }
}
