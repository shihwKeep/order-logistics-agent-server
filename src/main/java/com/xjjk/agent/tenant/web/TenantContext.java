package com.xjjk.agent.tenant.web;

public final class TenantContext {

    public static final String TENANT_ID_ATTRIBUTE =
            TenantContext.class.getName() + ".tenantId";

    private TenantContext() {
    }
}
