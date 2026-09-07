package com.xjjk.agent.order.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 订单与物流工具的按组织灰度策略。
 *
 * <p>未知、空白或缺失模式一律按 OFF 处理；模型请求中没有改变灰度组织的入口。</p>
 */
@ConfigurationProperties(prefix = "agent.tool")
public record OrderToolAvailability(
        Capability order,
        Capability logistics) {

    public OrderToolAvailability {
        order = order == null ? Capability.disabled() : order;
        logistics = logistics == null ? Capability.disabled() : logistics;
    }

    public boolean isOrderAvailable(AgentIdentity identity) {
        return available(order, identity);
    }

    public boolean isLogisticsAvailable(AgentIdentity identity) {
        return available(logistics, identity);
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

    /** 单项工具能力的开关、灰度模式和组织白名单。 */
    public record Capability(
            boolean enabled,
            String rolloutMode,
            Set<Long> allowedOrgIds) {

        public Capability {
            allowedOrgIds = allowedOrgIds == null
                    ? Set.of() : Set.copyOf(allowedOrgIds);
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
