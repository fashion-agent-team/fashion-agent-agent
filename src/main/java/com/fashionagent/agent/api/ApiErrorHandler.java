package com.fashionagent.agent.api;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = TurnController.class)
public class ApiErrorHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return body(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ResponseEntity<Map<String, Object>> missingHeader(MissingRequestHeaderException e) {
        return body(HttpStatus.UNAUTHORIZED, "unauthorized", "X-User-Id 헤더가 없어요.");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", "요청 JSON을 읽지 못했어요.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("처리하지 못한 오류", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal", "서버 오류예요.");
    }

    static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(Map.of("error", Map.of("code", code, "message", message)));
    }
}
