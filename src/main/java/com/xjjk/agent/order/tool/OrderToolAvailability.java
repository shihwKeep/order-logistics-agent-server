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
        Capability logistics,
        Capability customerOrder) {

    public OrderToolAvailability {
        // 缺失某一项配置时仅关闭该能力，不因空对象导致启动后空指针。
        order = order == null ? Capability.disabled() : order;
        logistics = logistics == null ? Capability.disabled() : logistics;
        customerOrder = customerOrder == null ? Capability.disabled() : customerOrder;
    }

    /** 判断当前认证组织是否可以向模型暴露订单查询工具。 */
    public boolean isOrderAvailable(AgentIdentity identity) {
        return available(order, identity);
    }

    /** 判断当前认证组织是否可以向模型暴露物流查询工具。 */
    public boolean isLogisticsAvailable(AgentIdentity identity) {
        return available(logistics, identity);
    }

    /** 判断当前认证组织是否可以向模型暴露客户订单查询工具。 */
    public boolean isCustomerOrderAvailable(AgentIdentity identity) {
        return available(customerOrder, identity);
    }

    /** 统一应用总开关、灰度模式和组织白名单，未知模式按关闭处理。 */
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
        /** 对所有组织关闭。 */
        OFF,
        /** 只向配置白名单中的组织开放。 */
        ALLOWLIST,
        /** 向所有已认证组织开放。 */
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
