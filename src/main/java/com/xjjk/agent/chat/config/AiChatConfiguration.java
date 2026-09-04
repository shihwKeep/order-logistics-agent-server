package com.xjjk.agent.chat.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
// Bean 之间通过参数注入依赖，可以使用 proxyBeanMethods=false；如果依赖直接调用其他 @Bean 方法来获取容器中的实例，就需要注意保留代理
public class AiChatConfiguration {

    @Bean
    public ChatClient agentChatClient(
            ChatClient.Builder builder,
            AiPromptProperties promptProperties
    ) {
        return builder
                .defaultSystem(promptProperties.system()) // 依赖builder、promptProperties是通过方法参数注入
                .build();
    }
}