package com.xjjk.agent.memory.service;

public class ImplicitMemoryExtractionException extends RuntimeException {

    private final Code code;

    public ImplicitMemoryExtractionException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    public enum Code {
        MODEL_CALL_FAILED,
        MODEL_TIMEOUT,
        MODEL_PROTOCOL_ERROR
    }
}
