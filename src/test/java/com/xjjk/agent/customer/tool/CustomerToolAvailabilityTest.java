package com.xjjk.agent.customer.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CustomerToolAvailabilityTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "坐席", 23L, 1L);

    @Test
    void failsClosedAndSupportsAllowlistAndAll() {
        assertThat(new CustomerToolAvailability(null).isAvailable(IDENTITY)).isFalse();
        assertThat(availability(true, "UNKNOWN", Set.of(23L)).isAvailable(IDENTITY)).isFalse();
        assertThat(availability(true, "ALLOWLIST", Set.of(99L)).isAvailable(IDENTITY)).isFalse();
        assertThat(availability(true, "ALLOWLIST", Set.of(23L)).isAvailable(IDENTITY)).isTrue();
        assertThat(availability(true, "ALL", Set.of()).isAvailable(IDENTITY)).isTrue();
        assertThat(availability(true, "ALL", Set.of()).isAvailable(null)).isFalse();
    }

    private CustomerToolAvailability availability(boolean enabled, String mode, Set<Long> orgs) {
        return new CustomerToolAvailability(
                new CustomerToolAvailability.Capability(enabled, mode, orgs));
    }
}
