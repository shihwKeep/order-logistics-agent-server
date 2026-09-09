package com.xjjk.agent.customer.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/** 客户工具独立灰度开关；空值和未知值全部按关闭处理。 */
@ConfigurationProperties(prefix = "agent.tool")
public record CustomerToolAvailability(Capability customer) {
    public CustomerToolAvailability {
        // Nacos 未配置客户能力时采用安全关闭默认值。
        customer = customer == null ? Capability.disabled() : customer;
    }

    /** 基于当前认证组织判断客户工具是否应注册到本轮模型请求。 */
    public boolean isAvailable(AgentIdentity identity) {
        if (identity == null || !customer.enabled()) {
            return false;
        }
        RolloutMode mode = RolloutMode.parse(customer.rolloutMode());
        return mode == RolloutMode.ALL
                || (mode == RolloutMode.ALLOWLIST
                && customer.allowedOrgIds().contains(identity.orgId()));
    }

    /** 客户工具的总开关、发布模式和组织白名单。 */
    public record Capability(boolean enabled, String rolloutMode, Set<Long> allowedOrgIds) {
        public Capability {
            allowedOrgIds = allowedOrgIds == null ? Set.of() : Set.copyOf(allowedOrgIds);
        }

        private static Capability disabled() {
            return new Capability(false, "OFF", Set.of());
        }
    }

    private enum RolloutMode {
        /** 关闭、组织白名单和全量开放三种灰度模式。 */
        OFF, ALLOWLIST, ALL;

        /** 配置为空或拼写未知时安全降级为 OFF。 */
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
