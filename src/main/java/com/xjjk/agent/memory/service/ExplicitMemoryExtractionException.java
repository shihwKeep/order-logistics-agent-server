package com.xjjk.agent.memory.service;

public class ExplicitMemoryExtractionException extends RuntimeException {

    private final Code code;

    public ExplicitMemoryExtractionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        MODEL_TIMEOUT,
        MODEL_CALL_FAILED,
        MODEL_PROTOCOL_ERROR
    }
}
