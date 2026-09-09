package com.xjjk.agent.chat.routing;

import com.xjjk.agent.chat.api.dto.ChatActionRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BusinessQueryPlanTest {

    @Test
    void createsValidatedGeneralDirectAndModelPlans() {
        assertThat(BusinessQueryPlan.general().mode())
                .isEqualTo(BusinessQueryMode.GENERAL);

        ChatActionRequest action = new ChatActionRequest(
                "QUERY_AFTER_SALE_DETAIL", null, null, "HH20260414_00002");
        BusinessQueryPlan direct = BusinessQueryPlan.direct(
                action, "after-sale-detail");
        assertThat(direct.directAction()).isEqualTo(action);
        assertThat(direct.acceptedResultKinds())
                .containsExactly("after-sale-detail");

        BusinessQueryPlan model = BusinessQueryPlan.modelRequired(
                Set.of("product-list"));
        assertThat(model.mode()).isEqualTo(BusinessQueryMode.MODEL_REQUIRED);
        assertThat(model.directAction()).isNull();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> BusinessQueryPlan.modelRequired(Set.of()));
    }
}
