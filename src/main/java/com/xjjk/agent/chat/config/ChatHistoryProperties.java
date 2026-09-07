package com.xjjk.agent.chat.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 历史上下文读取配置。
 *
 * 限制候选消息数量及正文读取总量，
 * 不等同于模型上下文的 Token 预算。
 *
 * 限制	                    保护的对象
 * maxScanMessages	候选消息数量，避免无限向前查找
 * maxReadBytes	    读取的正文总量，控制传输量和应用内存压力
 * 后续 Token 预算	实际提交给模型的上下文大小及成本
 *
 * @param maxScanMessages 一次读取最多保留的候选消息条数
 * @param maxReadBytes 一次快照最多读取的历史正文总字节数
 */
@Validated
@ConfigurationProperties(prefix = "agent.chat.history")
public record ChatHistoryProperties(

        @Min(value = 2, message = "历史候选消息条数不能小于 2")
        @Max(value = 1000, message = "历史候选消息条数不能超过 1000")
        int maxScanMessages,

        @Min(value = 1024, message = "历史正文读取预算不能小于 1024 字节")
        @Max(
                value = 16777216,
                message = "历史正文读取预算不能超过 16 MiB"
        )
        long maxReadBytes

) {
}
