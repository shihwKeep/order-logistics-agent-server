package com.xjjk.agent.chat.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xjjk.agent.chat.persistence.entity.AgentSummaryTaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话长期摘要任务数据访问接口。
 *
 * 后续自定义 SQL 负责目标合并、租约领取、重试和提交校验，
 * 业务层不能通过无条件更新绕过任务状态机。
 */
@Mapper
public interface AgentSummaryTaskMapper
        extends BaseMapper<AgentSummaryTaskEntity> {

    /**
     * 合并会话最新稳定历史目标。
     *
     * INSERT SELECT 同时校验会话归属和稳定游标；重复请求通过唯一会话键合并，
     * PROCESSING 状态保留当前租约，新目标由当前 Worker 收尾时重新置为 PENDING。
     */
    @Insert("""
        INSERT INTO agent_summary_task (
            task_id, tenant_id, user_id, conversation_id,
            requested_memory_version, requested_until_sequence,
            last_evaluated_memory_version, force_generation, force_reason,
            status, retry_count, next_run_at,
            lease_token, locked_by, locked_until, last_error_code,
            created_at, updated_at
        )
        SELECT #{taskId}, #{tenantId}, #{userId}, #{conversationId},
               #{memoryVersion}, #{memoryUntilSequence},
               0, #{forceGeneration}, #{forceReason},
               'PENDING', 0, #{now},
               NULL, NULL, NULL, NULL,
               #{now}, #{now}
        FROM agent_conversation c
        WHERE c.tenant_id = #{tenantId}
          AND c.user_id = #{userId}
          AND c.conversation_id = #{conversationId}
          AND c.memory_version = #{memoryVersion}
          AND c.memory_until_sequence = #{memoryUntilSequence}
        ON DUPLICATE KEY UPDATE
            status = CASE
                WHEN status = 'PROCESSING' THEN status
                WHEN VALUES(requested_memory_version) > last_evaluated_memory_version
                     OR VALUES(force_generation) = 1 THEN 'PENDING'
                ELSE status
            END,
            next_run_at = CASE
                WHEN status = 'PROCESSING' THEN next_run_at
                WHEN VALUES(requested_memory_version) > last_evaluated_memory_version
                     OR VALUES(force_generation) = 1 THEN VALUES(next_run_at)
                ELSE next_run_at
            END,
            retry_count = CASE
                WHEN VALUES(requested_memory_version) > requested_memory_version THEN 0
                ELSE retry_count
            END,
            last_error_code = CASE
                WHEN VALUES(requested_memory_version) > requested_memory_version THEN NULL
                ELSE last_error_code
            END,
            force_reason = CASE
                WHEN VALUES(force_generation) = 1 THEN VALUES(force_reason)
                ELSE force_reason
            END,
            force_generation = GREATEST(
                force_generation,
                VALUES(force_generation)
            ),
            requested_until_sequence = CASE
                WHEN VALUES(requested_memory_version) > requested_memory_version
                    THEN VALUES(requested_until_sequence)
                WHEN VALUES(requested_memory_version) = requested_memory_version
                    THEN GREATEST(
                        requested_until_sequence,
                        VALUES(requested_until_sequence)
                    )
                ELSE requested_until_sequence
            END,
            requested_memory_version = GREATEST(
                requested_memory_version,
                VALUES(requested_memory_version)
            ),
            updated_at = VALUES(updated_at)
        """)
    int upsertRequestedTarget(
            @Param("tenantId") long tenantId,
            @Param("userId") long userId,
            @Param("conversationId") String conversationId,
            @Param("memoryVersion") long memoryVersion,
            @Param("memoryUntilSequence") long memoryUntilSequence,
            @Param("forceGeneration") boolean forceGeneration,
            @Param("forceReason") String forceReason,
            @Param("taskId") String taskId,
            @Param("now") LocalDateTime now
    );

    /** 在短事务内锁定当前可领取任务；MySQL 8 多实例间跳过已锁行。 */
    @Select("""
        SELECT *
        FROM agent_summary_task
        WHERE status IN ('PENDING', 'RETRY')
          AND next_run_at <= #{now}
        ORDER BY next_run_at, id
        LIMIT #{limit}
        FOR UPDATE SKIP LOCKED
        """)
    List<AgentSummaryTaskEntity> selectClaimableForUpdate(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    /** 领取时消费旧 force 标记；处理期间到达的新 force 请求因此可以被保留下来。 */
    @Update("""
        UPDATE agent_summary_task
        SET status = 'PROCESSING',
            lease_token = #{leaseToken},
            locked_by = #{lockedBy},
            locked_until = #{lockedUntil},
            force_generation = 0,
            force_reason = NULL,
            updated_at = #{now}
        WHERE id = #{id}
          AND status IN ('PENDING', 'RETRY')
          AND next_run_at <= #{now}
        """)
    int markClaimed(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("lockedUntil") LocalDateTime lockedUntil,
            @Param("now") LocalDateTime now
    );

    /** 锁定并校验当前 Worker 的租约所有权，提交和状态变更都以此为前置条件。 */
    @Select("""
        SELECT *
        FROM agent_summary_task
        WHERE id = #{id}
          AND status = 'PROCESSING'
          AND lease_token = #{leaseToken}
          AND locked_by = #{lockedBy}
        FOR UPDATE
        """)
    AgentSummaryTaskEntity selectLeaseForUpdate(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy
    );

    /** 完成当前租约；status=PENDING 表示处理期间有新目标或仍有摘要积压。 */
    @Update("""
        UPDATE agent_summary_task
        SET last_evaluated_memory_version = GREATEST(
                last_evaluated_memory_version,
                #{evaluatedMemoryVersion}
            ),
            status = #{status},
            retry_count = 0,
            next_run_at = #{now},
            lease_token = NULL,
            locked_by = NULL,
            locked_until = NULL,
            last_error_code = #{errorCode},
            updated_at = #{now}
        WHERE id = #{id}
          AND status = 'PROCESSING'
          AND lease_token = #{leaseToken}
          AND locked_by = #{lockedBy}
        """)
    int completeLease(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("evaluatedMemoryVersion") long evaluatedMemoryVersion,
            @Param("status") String status,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now
    );

    /** 失败后释放租约并写入安全错误码，不推进已评估版本。 */
    @Update("""
        UPDATE agent_summary_task
        SET status = #{status},
            retry_count = #{retryCount},
            next_run_at = #{nextRunAt},
            lease_token = NULL,
            locked_by = NULL,
            locked_until = NULL,
            last_error_code = #{errorCode},
            updated_at = #{now}
        WHERE id = #{id}
          AND status = 'PROCESSING'
          AND lease_token = #{leaseToken}
          AND locked_by = #{lockedBy}
        """)
    int failLease(
            @Param("id") long id,
            @Param("leaseToken") String leaseToken,
            @Param("lockedBy") String lockedBy,
            @Param("status") String status,
            @Param("retryCount") int retryCount,
            @Param("nextRunAt") LocalDateTime nextRunAt,
            @Param("errorCode") String errorCode,
            @Param("now") LocalDateTime now
    );

    /** 回收过期租约；超过最大次数的任务进入 DEAD，其余立即进入 RETRY。 */
    @Update("""
        UPDATE agent_summary_task
        SET status = CASE
                WHEN retry_count + 1 >= #{maxAttempts} THEN 'DEAD'
                ELSE 'RETRY'
            END,
            retry_count = retry_count + 1,
            next_run_at = #{now},
            lease_token = NULL,
            locked_by = NULL,
            locked_until = NULL,
            last_error_code = 'LEASE_EXPIRED',
            updated_at = #{now}
        WHERE status = 'PROCESSING'
          AND locked_until < #{now}
        ORDER BY locked_until, id
        LIMIT #{limit}
        """)
    int recoverExpiredLeases(
            @Param("now") LocalDateTime now,
            @Param("maxAttempts") int maxAttempts,
            @Param("limit") int limit
    );
}
