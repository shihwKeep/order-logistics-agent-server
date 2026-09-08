package com.xjjk.agent.order.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderToolAvailabilityTest {

    private static final AgentIdentity ORG_23 =
            new AgentIdentity(10567L, "10567", "测试坐席", 23L, 1L);

    @Test
    void failsClosedForDisabledOffBlankAndUnknownModes() {
        assertThat(availability(false, "ALL", Set.of(23L), true, "ALL", Set.of())
                .isOrderAvailable(ORG_23)).isFalse();
        assertThat(availability(true, "OFF", Set.of(23L), true, "ALL", Set.of())
                .isOrderAvailable(ORG_23)).isFalse();
        assertThat(availability(true, " ", Set.of(23L), true, "ALL", Set.of())
                .isOrderAvailable(ORG_23)).isFalse();
        assertThat(availability(true, "UNKNOWN", Set.of(23L), true, "ALL", Set.of())
                .isOrderAvailable(ORG_23)).isFalse();

        assertThat(availability(true, "ALL", Set.of(), false, "ALL", Set.of(23L))
                .isLogisticsAvailable(ORG_23)).isFalse();
        assertThat(availability(true, "ALL", Set.of(), true, "OFF", Set.of(23L))
                .isLogisticsAvailable(ORG_23)).isFalse();
        assertThat(availability(true, "ALL", Set.of(), true, " ", Set.of(23L))
                .isLogisticsAvailable(ORG_23)).isFalse();
        assertThat(availability(true, "ALL", Set.of(), true, "UNKNOWN", Set.of(23L))
                .isLogisticsAvailable(ORG_23)).isFalse();
    }

    @Test
    void allAndAllowlistUseAuthenticatedOrganization() {
        assertThat(availability(true, "ALL", Set.of(), false, "OFF", Set.of())
                .isOrderAvailable(ORG_23)).isTrue();
        assertThat(availability(true, "ALLOWLIST", Set.of(23L), false, "OFF", Set.of())
                .isOrderAvailable(ORG_23)).isTrue();
        assertThat(availability(true, "ALLOWLIST", Set.of(99L), false, "OFF", Set.of())
                .isOrderAvailable(ORG_23)).isFalse();
    }

    @Test
    void orderAndLogisticsSwitchesAreIndependent() {
        OrderToolAvailability availability = availability(
                true, "ALLOWLIST", Set.of(23L),
                true, "ALLOWLIST", Set.of(99L));

        assertThat(availability.isOrderAvailable(ORG_23)).isTrue();
        assertThat(availability.isLogisticsAvailable(ORG_23)).isFalse();
    }

    @Test
    void customerOrderHasAnIndependentFailClosedSwitch() {
        OrderToolAvailability availability = new OrderToolAvailability(
                capability(false, "OFF", Set.of()),
                capability(false, "OFF", Set.of()),
                capability(true, "ALLOWLIST", Set.of(23L)));

        assertThat(availability.isCustomerOrderAvailable(ORG_23)).isTrue();
        assertThat(new OrderToolAvailability(null, null, null)
                .isCustomerOrderAvailable(ORG_23)).isFalse();
    }

    private OrderToolAvailability availability(
            boolean orderEnabled,
            String orderMode,
            Set<Long> orderAllowed,
            boolean logisticsEnabled,
            String logisticsMode,
            Set<Long> logisticsAllowed) {
        return new OrderToolAvailability(
                new OrderToolAvailability.Capability(
                        orderEnabled, orderMode, orderAllowed),
                new OrderToolAvailability.Capability(
                        logisticsEnabled, logisticsMode, logisticsAllowed),
                null);
    }

    private OrderToolAvailability.Capability capability(
            boolean enabled, String mode, Set<Long> allowed) {
        return new OrderToolAvailability.Capability(enabled, mode, allowed);
    }
}
