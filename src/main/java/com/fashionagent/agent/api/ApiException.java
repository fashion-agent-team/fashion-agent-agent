package com.fashionagent.agent.api;

import org.springframework.http.HttpStatus;

/** 약속 4절의 오류 응답 {"error":{"code","message"}}로 바뀌는 예외. message는 개발자용입니다. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }
}
