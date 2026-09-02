package com.xjjk.agent.auth.client;

public class SspxOAuthClientException extends RuntimeException {

    public enum Reason {
        UNAVAILABLE,
        INVALID_RESPONSE
    }

    private final Reason reason;

    public SspxOAuthClientException(Reason reason, Throwable cause) {
        super(reason.name(), cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
