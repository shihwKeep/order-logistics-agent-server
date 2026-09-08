package com.xjjk.agent.customer.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.xjjk.agent.customer.domain.CustomerMatchType;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;

import java.time.OffsetDateTime;
import java.util.List;

@FeignClient(name = "agent-customer-search",
        url = "${integration.customer.base-url}",
        configuration = CustomerFeignConfiguration.class)
public interface CustomerSearchClient {
    @PostMapping("/internal/agent/customers/search")
    CustomerServiceResponse<SearchData> search(
            @RequestHeader("X-Agent-Internal-Token") String token,
            @RequestHeader("X-Agent-Tenant-Id") long tenantId,
            @RequestHeader("X-Agent-User-Id") long userId,
            @RequestHeader("X-Agent-Org-Id") long orgId,
            @RequestHeader("X-Agent-Request-Id") String requestId,
            @RequestBody SearchRequest request);

    record SearchRequest(String keyword, CustomerMatchType matchType) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record SearchData(String matchedBy, Long total, Boolean truncated,
                      OffsetDateTime queriedAt, List<CustomerData> items) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record CustomerData(Long customerId, String customerCode, String displayName,
                        String gradeName, String assetTypeName, String customerTypeName) {
    }
}
