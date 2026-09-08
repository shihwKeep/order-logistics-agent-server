package com.xjjk.agent.aftersale.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSaleToolAvailabilityTest {
    private static final AgentIdentity IDENTITY =
            new AgentIdentity(10567L, "10567", "坐席", 23L, 1L);

    @Test
    void searchAndDetailFailClosedAndSupportIndependentRollout() {
        assertThat(new AfterSaleToolAvailability(null, null)
                .isSearchAvailable(IDENTITY)).isFalse();
        assertThat(availability(capability(true, "UNKNOWN", 23L), capability(true, "ALL"))
                .isSearchAvailable(IDENTITY)).isFalse();
        assertThat(availability(capability(true, "ALLOWLIST", 99L), capability(true, "ALL"))
                .isSearchAvailable(IDENTITY)).isFalse();
        assertThat(availability(capability(true, "ALLOWLIST", 23L), capability(false, "ALL"))
                .isSearchAvailable(IDENTITY)).isTrue();
        assertThat(availability(capability(true, "ALLOWLIST", 23L), capability(false, "ALL"))
                .isDetailAvailable(IDENTITY)).isFalse();
        assertThat(availability(capability(false, "OFF"), capability(true, "ALL"))
                .isDetailAvailable(IDENTITY)).isTrue();
        assertThat(availability(capability(true, "ALL"), capability(true, "ALL"))
                .isDetailAvailable(null)).isFalse();
    }

    private AfterSaleToolAvailability availability(
            AfterSaleToolAvailability.Capability search,
            AfterSaleToolAvailability.Capability detail) {
        return new AfterSaleToolAvailability(search, detail);
    }

    private AfterSaleToolAvailability.Capability capability(
            boolean enabled, String mode, Long... orgIds) {
        return new AfterSaleToolAvailability.Capability(enabled, mode, Set.of(orgIds));
    }
}
