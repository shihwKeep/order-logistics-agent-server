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
import com.xjjk.agent.knowledge.tool.KnowledgeQueryTools;
import com.xjjk.agent.knowledge.tool.KnowledgeToolAvailability;
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

/**
 * Spring AI 对话模型适配层。
 *
 * <p>该服务只负责把已筛选的会话上下文、当前用户消息和当前组织可用的工具
 * 注册到一次模型请求中。工具是否对某个坐席开放在这里完成筛选，模型只能看到
 * 本次请求真正注册的工具定义，不能通过猜测工具名绕过灰度配置。</p>
 */
@Service
public class AiChatService {

    private final ChatClient chatClient;
    private final List<ToolCallback> productToolCallbacks;
    private final Map<String, ToolCallback> orderToolCallbacks;
    private final ToolCallback customerToolCallback;
    private final ToolCallback customerOrderToolCallback;
    private final Map<String, ToolCallback> afterSaleToolCallbacks;
    private final ToolCallback knowledgeToolCallback;
    private final OrderToolAvailability orderToolAvailability;
    private final CustomerToolAvailability customerToolAvailability;
    private final AfterSaleToolAvailability afterSaleToolAvailability;
    private final KnowledgeToolAvailability knowledgeToolAvailability;

    public AiChatService(
            @Qualifier("agentChatClient") ChatClient chatClient,
            ProductQueryTools productQueryTools,
            OrderQueryTools orderQueryTools,
            OrderToolAvailability orderToolAvailability,
            CustomerQueryTools customerQueryTools,
            CustomerOrderQueryTools customerOrderQueryTools,
            CustomerToolAvailability customerToolAvailability,
            AfterSaleQueryTools afterSaleQueryTools,
            AfterSaleToolAvailability afterSaleToolAvailability,
            KnowledgeQueryTools knowledgeQueryTools,
            KnowledgeToolAvailability knowledgeToolAvailability
    ) {
        this.chatClient = chatClient;
        // Spring AI 根据 @Tool 方法生成 ToolCallback；商品能力当前始终注册。
        this.productToolCallbacks = List.of(ToolCallbacks.from(productQueryTools));
        // 一个工具对象可能声明多个 @Tool 方法，按工具名建立只读索引，
        // 后续才能分别控制订单查询和物流查询的组织级可见性。
        this.orderToolCallbacks = Arrays.stream(ToolCallbacks.from(orderQueryTools))
                .collect(Collectors.toUnmodifiableMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()));
        this.orderToolAvailability = orderToolAvailability;
        this.customerToolAvailability = customerToolAvailability;
        this.afterSaleToolAvailability = afterSaleToolAvailability;
        this.knowledgeToolAvailability = knowledgeToolAvailability;
        this.customerToolCallback = requireSingleCallback(
                customerQueryTools, "search_customers");
        this.customerOrderToolCallback = requireSingleCallback(
                customerOrderQueryTools, "list_customer_orders");
        this.afterSaleToolCallbacks = Arrays.stream(ToolCallbacks.from(afterSaleQueryTools))
                .collect(Collectors.toUnmodifiableMap(
                        callback -> callback.getToolDefinition().name(),
                        Function.identity()));
        this.knowledgeToolCallback = requireSingleCallback(
                knowledgeQueryTools, "search_knowledge");

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
                    // 与 Token 估算使用同一个已增强系统提示词，避免预算漂移。
                    .system(selection.effectiveSystemPrompt())
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

    /**
     * 每次请求基于服务端认证身份生成精确工具清单。
     *
     * <p>可用性判断只接受 {@link AgentIdentity}，不读取模型参数。返回不可变副本，
     * 防止构建完模型请求后又被其他线程修改。</p>
     */
    List<ToolCallback> selectToolCallbacks(AgentIdentity identity) {
        List<ToolCallback> selected = new ArrayList<>(productToolCallbacks);
        // 订单、物流、客户订单虽然来自相关业务域，但分别拥有独立的能力开关。
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
        if (knowledgeToolAvailability.isAvailable(identity)) {
            selected.add(knowledgeToolCallback);
        }
        return List.copyOf(selected);
    }

    /** 将只允许声明一个方法的工具对象转换为回调，并在启动阶段核对工具名。 */
    private ToolCallback requireSingleCallback(Object toolObject, String expectedName) {
        ToolCallback[] callbacks = ToolCallbacks.from(toolObject);
        if (callbacks.length != 1
                || !expectedName.equals(callbacks[0].getToolDefinition().name())) {
            throw new IllegalStateException("缺少工具定义: " + expectedName);
        }
        return callbacks[0];
    }

    /** 从订单工具索引中取得指定回调；缺失说明代码声明与注册清单已经漂移。 */
    private ToolCallback requireOrderCallback(String toolName) {
        ToolCallback callback = orderToolCallbacks.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("缺少订单工具定义: " + toolName);
        }
        return callback;
    }

    /** 从售后工具索引中取得指定回调；缺失时直接阻止应用带着残缺工具集运行。 */
    private ToolCallback requireAfterSaleCallback(String toolName) {
        ToolCallback callback = afterSaleToolCallbacks.get(toolName);
        if (callback == null) {
            throw new IllegalStateException("缺少售后工具定义: " + toolName);
        }
        return callback;
    }
}
