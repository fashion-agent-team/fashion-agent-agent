package com.fashionagent.agent.tools;

/** 도구가 {"error":{"code","message"}}를 돌려줬거나 서버에 닿지 못함. */
public class ToolException extends RuntimeException {

    private final String tool;
    private final String code;

    public ToolException(String tool, String code, String message) {
        super(tool + ": " + code + " - " + message);
        this.tool = tool;
        this.code = code;
    }

    public ToolException(String tool, String code, String message, Throwable cause) {
        super(tool + ": " + code + " - " + message, cause);
        this.tool = tool;
        this.code = code;
    }

    public String tool() {
        return tool;
    }

    public String code() {
        return code;
    }
}
