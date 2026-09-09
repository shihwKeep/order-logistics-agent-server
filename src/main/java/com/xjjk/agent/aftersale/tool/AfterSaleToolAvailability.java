package com.xjjk.agent.aftersale.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/** 售后搜索与详情工具相互独立的组织灰度策略。 */
@ConfigurationProperties(prefix = "agent.tool.after-sale")
public record AfterSaleToolAvailability(Capability search, Capability detail) {
    public AfterSaleToolAvailability {
        // 搜索和详情独立默认关闭，允许分阶段灰度发布。
        search = search == null ? Capability.disabled() : search;
        detail = detail == null ? Capability.disabled() : detail;
    }

    /** 判断当前组织是否可以使用售后列表搜索。 */
    public boolean isSearchAvailable(AgentIdentity identity) {
        return available(search, identity);
    }

    /** 判断当前组织是否可以使用售后详情查询。 */
    public boolean isDetailAvailable(AgentIdentity identity) {
        return available(detail, identity);
    }

    /** 统一执行开关、模式和组织白名单判断。 */
    private boolean available(Capability capability, AgentIdentity identity) {
        if (!capability.enabled() || identity == null) {
            return false;
        }
        RolloutMode mode = RolloutMode.parse(capability.rolloutMode());
        return mode == RolloutMode.ALL
                || (mode == RolloutMode.ALLOWLIST
                && capability.allowedOrgIds().contains(identity.orgId()));
    }

    /** 单项售后能力的灰度配置。 */
    public record Capability(boolean enabled, String rolloutMode, Set<Long> allowedOrgIds) {
        public Capability {
            allowedOrgIds = allowedOrgIds == null ? Set.of() : Set.copyOf(allowedOrgIds);
        }

        private static Capability disabled() {
            return new Capability(false, "OFF", Set.of());
        }
    }

    private enum RolloutMode {
        /** 完全关闭。 */
        OFF,
        /** 只允许指定组织。 */
        ALLOWLIST,
        /** 允许所有已认证组织。 */
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
