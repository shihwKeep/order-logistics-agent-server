package com.xjjk.agent.customer.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/** 客户工具独立灰度开关；空值和未知值全部按关闭处理。 */
@ConfigurationProperties(prefix = "agent.tool")
public record CustomerToolAvailability(Capability customer) {
    public CustomerToolAvailability {
        customer = customer == null ? Capability.disabled() : customer;
    }

    public boolean isAvailable(AgentIdentity identity) {
        if (identity == null || !customer.enabled()) {
            return false;
        }
        RolloutMode mode = RolloutMode.parse(customer.rolloutMode());
        return mode == RolloutMode.ALL
                || (mode == RolloutMode.ALLOWLIST
                && customer.allowedOrgIds().contains(identity.orgId()));
    }

    public record Capability(boolean enabled, String rolloutMode, Set<Long> allowedOrgIds) {
        public Capability {
            allowedOrgIds = allowedOrgIds == null ? Set.of() : Set.copyOf(allowedOrgIds);
        }

        private static Capability disabled() {
            return new Capability(false, "OFF", Set.of());
        }
    }

    private enum RolloutMode {
        OFF, ALLOWLIST, ALL;

        static RolloutMode parse(String value) {
            if (value == null || value.isBlank()) {
                return OFF;
            }
            try {
                return valueOf(value.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                return OFF;
            }
        }
    }
}
