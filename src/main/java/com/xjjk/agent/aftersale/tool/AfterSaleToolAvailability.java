package com.xjjk.agent.aftersale.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/** 售后搜索与详情工具相互独立的组织灰度策略。 */
@ConfigurationProperties(prefix = "agent.tool.after-sale")
public record AfterSaleToolAvailability(Capability search, Capability detail) {
    public AfterSaleToolAvailability {
        search = search == null ? Capability.disabled() : search;
        detail = detail == null ? Capability.disabled() : detail;
    }

    public boolean isSearchAvailable(AgentIdentity identity) {
        return available(search, identity);
    }

    public boolean isDetailAvailable(AgentIdentity identity) {
        return available(detail, identity);
    }

    private boolean available(Capability capability, AgentIdentity identity) {
        if (!capability.enabled() || identity == null) {
            return false;
        }
        RolloutMode mode = RolloutMode.parse(capability.rolloutMode());
        return mode == RolloutMode.ALL
                || (mode == RolloutMode.ALLOWLIST
                && capability.allowedOrgIds().contains(identity.orgId()));
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
        OFF,
        ALLOWLIST,
        ALL;

        private static RolloutMode parse(String value) {
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
