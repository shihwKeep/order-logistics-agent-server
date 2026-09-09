package com.xjjk.agent.knowledge.tool;

import com.xjjk.agent.identity.domain.AgentIdentity;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Locale;
import java.util.Set;

/** 知识检索工具的组织级灰度开关。 */
@ConfigurationProperties(prefix = "agent.tool.knowledge")
public record KnowledgeToolAvailability(
        boolean enabled, String rolloutMode, Set<Long> allowedOrgIds) {
    public KnowledgeToolAvailability {
        allowedOrgIds = allowedOrgIds == null ? Set.of() : Set.copyOf(allowedOrgIds);
    }

    public boolean isAvailable(AgentIdentity identity) {
        if (!enabled || identity == null) return false;
        String mode = rolloutMode == null ? "OFF" : rolloutMode.strip().toUpperCase(Locale.ROOT);
        return "ALL".equals(mode)
                || ("ALLOWLIST".equals(mode) && allowedOrgIds.contains(identity.orgId()));
    }
}
