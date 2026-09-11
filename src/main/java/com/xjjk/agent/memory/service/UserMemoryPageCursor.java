package com.xjjk.agent.memory.service;

import java.time.LocalDateTime;

public record UserMemoryPageCursor(LocalDateTime updatedAt, long id) {
}
