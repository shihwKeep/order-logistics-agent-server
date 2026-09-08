package com.xjjk.agent.chat.service.model;

import com.xjjk.agent.aftersale.tool.AfterSaleQueryTools;
import com.xjjk.agent.aftersale.tool.AfterSaleToolAvailability;
import com.xjjk.agent.chat.domain.memory.ChatContextSelection;
import com.xjjk.agent.chat.service.memory.RequestChatMemory;
import com.xjjk.agent.identity.domain.AgentIdentity;
import com.xjjk.agent.customer.tool.CustomerOrderQueryTools;
import com.xjjk.agent.customer.tool.CustomerQueryTools;
import com.xjjk.agent.customer.tool.CustomerToolAvailability;
import com.xjjk.agent.order.tool.OrderQueryTools;
import com.xjjk.agent.order.tool.OrderToolAvailability;
import com.xjjk.agent.product.tool.ProductQueryTools;
import com.xjjk.agent.tool.AgentToolRequestContext;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class AiChatService {

    private final ChatClient chatClient;
    private final List<ToolCallback> productToolCallbacks;
    private final Map<String, ToolCallback> orderToolCallbacks;
    private final ToolCallback customerToolCallback;
    private final ToolCallback customerOrderToolCallback;
    private final Map<String, ToolCallback> afterSaleToolCallbacks;
    private final OrderToolAvailability orderToolAvailability;
    private final CustomerToolAvailability customerToolAvailability;
    private final AfterSaleToolAvailability afterSaleToolAvailability;

    public AiChatService(
            @Qualifier("agentChatClient") ChatClient chatClient,
            ProductQueryTools productQueryTools,
            OrderQueryTools orderQueryTools,
            OrderToolAvailability orderToolAvailability,
            CustomerQueryTools customerQueryTools,
            CustomerOrderQueryTools customerOrderQueryTools,
            CustomerToolAvailability customerToolAvailability,
            AfterSaleQueryTools afterSaleQueryTools,
            AfterSaleToolAvailability afterSaleToolAvailability
    ) {
        this.chatClient = chatClient;
        this.productToolCallbacks = List.of(ToolCallbacks.from(productQueryTools));
        this.orderToolCallbacks = Arrays.stream(ToolCallbacks.from(orderQueryTools))
                .collect(Collectors.toUnmodifiableMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()));
        this.orderToolAvailability = orderToolAvailability;
        this.customerToolAvailability = customerToolAvailability;
        this.afterSaleToolAvailability = afterSaleToolAvailability;
        this.customerToolCallback = requireSingleCallback(
                customerQueryTools, "search_customers");
        this.customerOrderToolCallback = requireSingleCallback(
                customerOrderQueryTools, "list_customer_orders");
        this.afterSaleToolCallbacks = Arrays.stream(ToolCallbacks.from(afterSaleQueryTools))
                .collect(Collectors.toUnmodifiableMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()));

        // 启动期即验证代码声明的工具名，避免注解改名后灰度选择静默失效。
        requireOrderCallback("search_orders");
        requireOrderCallback("get_order_logistics");
        requireAfterSaleCallback("search_after_sales");
        requireAfterSaleCallback("get_after_sale_detail");
    }

    /**
     * 使用已经完成预算筛选的历史进行流式对话。
     *
     * 每次订阅创建独立的记忆和 Advisor，
     * 不跨请求共享临时消息，不由框架直接写入业务消息表。
     *
     * @param message 当前用户问题，应与筛选时使用的问题一致
     * @param selection 已完成权限检查和预算筛选的上下文
     * @param toolRequestContext 仅在服务端流转的工具身份、SSE 输出和单轮调用保护上下文
     * @return 模型流式响应
     */
    public Flux<ChatResponse> stream(
            String message,
            ChatContextSelection selection,
            AgentToolRequestContext toolRequestContext
    ) {
        Assert.hasText(message, "消息内容不能为空");
        Objects.requireNonNull(selection, "上下文筛选结果不能为空");
        Objects.requireNonNull(toolRequestContext, "工具请求上下文不能为空");

        return Flux.defer(() -> {
            // 在订阅时创建，避免多次订阅复用已经追加过消息的记忆。
            RequestChatMemory memory = new RequestChatMemory(selection);

            // 使用框架原生 Advisor，将历史加入模型请求，
            // 并将本轮消息追加到请求独立的临时记忆。
            MessageChatMemoryAdvisor memoryAdvisor =
                    MessageChatMemoryAdvisor.builder(memory).build();

            List<ToolCallback> selectedCallbacks = selectToolCallbacks(
                    toolRequestContext.identity());

            return chatClient
                    .prompt()
                    .user(message)
                    // 工具只注册在本次请求，ToolContext 中的可信身份、requestId
                    // 和 SSE 发布器不会进入模型提示词，也不能由模型参数覆盖。
                    // 订单与物流是两个独立灰度能力。必须按当前认证组织筛选
                    // ToolCallback，不能注册整个 OrderQueryTools 后在工具内部假关闭。
                    .toolCallbacks(selectedCallbacks)
                    .toolContext(Map.of(
                            AgentToolRequestContext.CONTEXT_KEY,
                            toolRequestContext))
                    .advisors(spec -> spec
                            .advisors(memoryAdvisor)
                            .param(
                                    ChatMemory.CONVERSATION_ID,
                                    selection.source().conversationId()
                            ))
                    .stream()
                    .chatResponse();
        });
    }

    /** 每次请求基于可信身份生成精确工具清单；商品工具保持始终注册。 */
    List<ToolCallback> selectToolCallbacks(AgentIdentity identity) {
        List<ToolCallback> selected = new ArrayList<>(productToolCallbacks);
        if (orderToolAvailability.isOrderAvailable(identity)) {
            selected.add(requireOrderCallback("search_orders"));
        }
        if (orderToolAvailability.isLogisticsAvailable(identity)) {
            selected.add(requireOrderCallback("get_order_logistics"));
        }
        if (customerToolAvailability.isAvailable(identity)) {
            selected.add(customerToolCallback);
        }
        if (orderToolAvailability.isCustomerOrderAvailable(identity)) {
            selected.add(customerOrderToolCallback);
        }
        if (afterSaleToolAvailability.isSearchAvailable(identity)) {
            selected.add(requireAfterSaleCallback("search_after_sales"));
        }
        if (afterSaleToolAvailability.isDetailAvailable(identity)) {
            selected.add(requireAfterSaleCallback("get_after_sale_detail"));
        }
        return List.copyOf(selected);
    }

    private ToolCallback requireSingleCallback(Object toolObject, String expectedName) {
        ToolCallback[] callbacks = ToolCallbacks.from(toolObject);
        if (callbacks.length != 1
                || !expectedName.equals(callbacks[0].getToolDefinition().name())) {
            throw new IllegalStateException("缺少工具定义: " + expectedName);
        }
        return callbacks[0];
    }

    private ToolCallback requireOrderCallback(String toolName) {
        ToolCallback callback = orderToolCallbacks.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("缺少订单工具定义: " + toolName);
        }
        return callback;
    }

    private ToolCallback requireAfterSaleCallback(String toolName) {
        ToolCallback callback = afterSaleToolCallbacks.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("缺少售后工具定义: " + toolName);
        }
        return callback;
    }
}
