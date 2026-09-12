package com.xjjk.agent.memory.persistence;

import com.xjjk.agent.memory.persistence.mapper.MemoryOutboxMapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryOutboxMapperContractTest {

    @Test
    void claimIsOrderedAndUsesSkipLocked() {
        String sql = sql(find("selectClaimableForUpdate").getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("status IN ('PENDING', 'RETRY')")
                .contains("next_run_at <= #{now}")
                .contains("ORDER BY next_run_at, id")
                .contains("FOR UPDATE SKIP LOCKED");
    }

    @Test
    void terminalUpdatesRequireLeaseOwnership() {
        for (String method : java.util.List.of("completeLease", "failLease")) {
            String sql = sql(find(method).getAnnotation(Update.class).value());
            assertThat(sql)
                    .contains("status = 'PROCESSING'")
                    .contains("lease_token = #{leaseToken}")
                    .contains("locked_by = #{lockedBy}");
        }
    }

    @Test
    void expiredLeaseRecoveryIsBounded() {
        String sql = sql(find("recoverExpiredLeases").getAnnotation(Update.class).value());

        assertThat(sql)
                .contains("locked_until < #{now}")
                .contains("LIMIT #{limit}")
                .contains("'DEAD'")
                .contains("'RETRY'");
    }

    private Method find(String name) {
        return Arrays.stream(MemoryOutboxMapper.class.getDeclaredMethods())
                .filter(method -> method.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private String sql(String[] parts) {
        return String.join("\n", parts);
    }
}
