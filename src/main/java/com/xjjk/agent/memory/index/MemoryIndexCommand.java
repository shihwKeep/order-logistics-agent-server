package com.xjjk.agent.memory.index;

import com.xjjk.agent.memory.domain.MemoryOutboxOperation;

import java.time.Instant;

/** Agent 向索引服务发出的、已绑定租户和用户的幂等记忆变更。 */
public record MemoryIndexCommand(
        long tenantId,
        long userId,
        String eventId,
        MemoryOutboxOperation operation,
        long memoryGeneration,
        String memoryId,
        long memoryVersion,
        String sourceType,
        String category,
        String canonicalKey,
        String content,
        double confidence,
        Instant expiresAt) {

    public MemoryIndexCommand {
        if (tenantId <= 0 || userId <= 0 || eventId == null || eventId.isBlank()
                || eventId.length() > 64 || operation == null || memoryGeneration <= 0) {
            throw new IllegalArgumentException("记忆索引命令基础字段不合法");
        }
        if (operation == MemoryOutboxOperation.UPSERT) {
            requireMemoryIdentity(memoryId, memoryVersion);
            if (sourceType == null || sourceType.isBlank()
                    || category == null || category.isBlank()
                    || canonicalKey == null || canonicalKey.isBlank()
                    || content == null || content.isBlank()
                    || !Double.isFinite(confidence) || confidence < 0D || confidence > 1D) {
                throw new IllegalArgumentException("记忆 UPSERT 命令字段不合法");
            }
        } else if (operation == MemoryOutboxOperation.DELETE) {
            requireMemoryIdentity(memoryId, memoryVersion);
            requireNoContent(sourceType, category, canonicalKey, content, confidence, expiresAt);
        } else {
            if (memoryId != null || memoryVersion != 0L) {
                throw new IllegalArgumentException("范围删除命令不能携带记忆标识");
            }
            requireNoContent(sourceType, category, canonicalKey, content, confidence, expiresAt);
        }
    }

    public static MemoryIndexCommand clearGeneration(
            long tenantId, long userId, String eventId, long memoryGeneration) {
        return new MemoryIndexCommand(
                tenantId, userId, eventId, MemoryOutboxOperation.CLEAR_GENERATION,
                memoryGeneration, null, 0L, null, null, null, null, 0D, null);
    }

    private static void requireMemoryIdentity(String memoryId, long memoryVersion) {
        if (memoryId == null || memoryId.isBlank() || memoryId.length() > 64
                || memoryVersion <= 0) {
            throw new IllegalArgumentException("记忆索引命令标识不合法");
        }
    }

    private static void requireNoContent(
            String sourceType, String category, String canonicalKey,
            String content, double confidence, Instant expiresAt) {
        if (sourceType != null || category != null || canonicalKey != null || content != null
                || confidence != 0D || expiresAt != null) {
            throw new IllegalArgumentException("删除命令不能携带记忆正文");
        }
    }
}
