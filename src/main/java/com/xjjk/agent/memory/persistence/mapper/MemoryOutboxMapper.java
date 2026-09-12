package com.xjjk.agent.memory.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.memory.persistence.entity.MemoryOutboxEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MemoryOutboxMapper extends BaseMapper<MemoryOutboxEntity> {

    @Select("""
        SELECT * FROM agent_memory_outbox
        WHERE status IN ('PENDING', 'RETRY') AND next_run_at <= #{now}
        ORDER BY next_run_at, id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<MemoryOutboxEntity> selectClaimableForUpdate(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit);

    @Update("""
        UPDATE agent_memory_outbox
        SET status = 'PROCESSING', lease_token = #{leaseToken}, locked_by = #{lockedBy},
            locked_until = #{lockedUntil}, updated_at = #{now}
        WHERE id = #{id} AND status IN ('PENDING', 'RETRY') AND next_run_at <= #{now}
        """)
    int markClaimed(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("now") LocalDateTime now);

    @Update("""
        UPDATE agent_memory_outbox
        SET status = 'DONE', lease_token = NULL, locked_by = NULL,
            locked_until = NULL, last_error_code = NULL, updated_at = #{now}
        WHERE id = #{id} AND status = 'PROCESSING'
          AND lease_token = #{leaseToken} AND locked_by = #{lockedBy}
        """)
    int completeLease(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("now") LocalDateTime now);

    @Update("""
        UPDATE agent_memory_outbox
        SET status = #{status}, retry_count = #{retryCount}, next_run_at = #{nextRunAt},
            lease_token = NULL, locked_by = NULL, locked_until = NULL,
            last_error_code = #{errorCode}, updated_at = #{now}
        WHERE id = #{id} AND status = 'PROCESSING'
          AND lease_token = #{leaseToken} AND locked_by = #{lockedBy}
        """)
    int failLease(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("status") String status,
            @Param("retryCount") int retryCount,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now);

    @Update("""
        UPDATE agent_memory_outbox
        SET status = CASE WHEN retry_count + 1 >= #{maxAttempts} THEN 'DEAD' ELSE 'RETRY' END,
            retry_count = retry_count + 1, next_run_at = #{now},
            lease_token = NULL, locked_by = NULL, locked_until = NULL,
            last_error_code = 'LEASE_EXPIRED', updated_at = #{now}
        WHERE status = 'PROCESSING' AND locked_until < #{now}
        ORDER BY locked_until, id LIMIT #{limit}
        """)
    int recoverExpiredLeases(
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit);
}
