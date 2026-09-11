package com.xjjk.agent.memory.service;

public final class UserMemoryDisabledException extends RuntimeException {

    public UserMemoryDisabledException() {
        super("USER_MEMORY_DISABLED");
    }
}
