# Spring MVC 拦截器与 OpenFeign 循环依赖问题复盘

## 问题背景

在 `order-logistics-agent-server` 接入现有 `sspx-server` 登录认证时，Agent 增加了如下调用链：

```text
IdentityWebMvcConfiguration
  → SspxAuthenticationInterceptor
  → SspxAuthenticationService
  → SspxAuthClient（OpenFeign）
```

身份拦截器负责保护 `/api/v1/**`，认证服务通过 OpenFeign 调用 SSPX 的 `/authorizationcenter/user/current`，使用原始 Bearer Token 重新确认用户身份。

## 故障现象

项目可以通过 Java 编译，但 Spring Boot 启动失败，错误信息指出 ApplicationContext 中存在循环依赖：

```text
IdentityWebMvcConfiguration
  → SspxAuthenticationInterceptor
  → SspxAuthenticationService
  → SspxAuthClient
  → WebMvcAutoConfiguration$EnableWebMvcConfiguration
  → IdentityWebMvcConfiguration
```

这说明“编译成功”只能证明 Java 类型和语法正确，不能证明 Spring Bean 在运行时一定能成功组装。

## 根因分析

`IdentityWebMvcConfiguration` 实现了 `WebMvcConfigurer`。Spring 初始化 MVC 基础设施时，需要先创建该配置类及其依赖的身份拦截器。

身份拦截器原本通过构造器立即注入 `SspxAuthenticationService`；创建认证服务时又立即创建 `SspxAuthClient`。OpenFeign 创建客户端需要 Spring MVC 的 Contract 等基础组件，而此时 MVC 配置还没有初始化完成，于是依赖链重新回到了 `EnableWebMvcConfiguration`，形成闭环。

问题不在业务请求、Bearer Token、Nacos 或 MySQL，而在 Spring 容器启动阶段的 Bean 创建时机。

## 没有采用的处理方式

没有增加以下配置：

```properties
spring.main.allow-circular-references=true
```

该配置只是允许 Spring 尝试处理循环引用，没有消除错误的初始化关系，还会让组件边界变得不清晰。Spring Boot 默认禁止循环依赖本身就是一种架构保护，因此不应把开启该开关作为正式修复。

也没有修改已经在生产使用的 `sspx-server` 和 Gateway，因为故障发生在 Agent 自己的 Bean 组装阶段，与两个既有项目的认证实现无关。

## 修复方案

在 `SspxAuthenticationInterceptor` 的认证服务注入点增加 `@Lazy`：

```java
import org.springframework.context.annotation.Lazy;

public SspxAuthenticationInterceptor(
        @Lazy SspxAuthenticationService authenticationService
) {
    this.authenticationService = authenticationService;
}
```

`@Lazy` 使 Spring 在启动阶段注入认证服务的懒代理，而不是立即沿着认证服务继续创建 Feign 客户端。

修复后的创建过程：

```text
应用启动阶段：
MVC 配置
  → 身份拦截器
  → 注入认证服务懒代理
  → MVC 初始化完成

收到受保护请求后：
身份拦截器
  → 调用懒代理
  → 创建认证服务和 Feign 客户端
  → 调用 SSPX 验证 Token
```

此方案只调整对象的初始化时机，不改变认证流程、异常映射或租户校验逻辑。

## 验证过程

首先使用隔离掉数据库自动配置的启动命令稳定复现原错误，确认故障与 Spring Bean 初始化有关。

应用最小修改后，再使用同一启动方式验证，日志出现：

```text
Started OrderLogisticsAgentServerApplication
```

随后实际发送请求，防止问题只是被推迟到第一次请求：

| 验证场景 | 实际结果 |
|---|---|
| `GET /actuator/health`，不带认证信息 | HTTP 200，`status=UP` |
| `GET /api/v1/system/whoami`，有租户但缺少 Token | HTTP 401，`AUTH_HEADER_MISSING` |
| 同一接口使用无效 Bearer Token | HTTP 401，`AUTH_TOKEN_INVALID` |
| Maven 编译 | `BUILD SUCCESS` |

无效 Token 场景触发了认证服务及 Feign 客户端的真实创建，证明懒加载之后的首次调用链也可以正常工作。

对应修复提交：

```text
5dde7c2 fix: break MVC and Feign initialization cycle
```

## 面试回答参考

可以按“现象—定位—根因—方案—验证”回答：

> 我在给 Spring Boot Agent 服务接入 OpenFeign 认证时遇到过一次启动阶段的循环依赖。身份拦截器是通过 `WebMvcConfigurer` 注册的，它构造时会继续创建认证服务和 Feign 客户端；Feign 创建又依赖尚未初始化完成的 MVC 组件，因此形成了 MVC 配置、拦截器、认证服务、Feign、MVC 配置的闭环。开始时项目能正常编译，但 ApplicationContext 启动失败，这也说明编译验证不能替代运行时 Bean 装配验证。
>
> 我没有通过 `spring.main.allow-circular-references=true` 绕过，而是在拦截器的认证服务注入点使用 `@Lazy`。启动阶段先注入懒代理，让 MVC 完成初始化，第一次受保护请求到达时再创建认证服务和 Feign 客户端。之后我不仅验证了应用能够启动，还分别请求了 Actuator、缺少 Token 和无效 Token 的接口，确认第一次真实调用也正常。这个修复只改变初始化时机，没有改生产中的认证服务和 Gateway，也没有降低安全校验。

## 可进一步追问的要点

### 为什么不用允许循环依赖配置？

它会掩盖 Bean 边界和初始化顺序问题，并增加后续维护风险。当前问题可以通过一个明确的延迟初始化边界解决，没有必要放宽整个应用的容器规则。

### `@Lazy` 是否只是掩盖问题？

这里被延迟的是外部认证客户端调用链。Web MVC 的启动配置不需要在启动阶段真正访问认证服务；只有请求进入拦截器时才需要它。因此延迟创建符合组件的实际生命周期。验证中还实际触发了无效 Token 请求，证明不是只让应用“表面启动成功”。

### 为什么不直接在 Gateway 验证后信任 `CurrentUser` 请求头？

Agent 端口若被绕过，调用者可以伪造普通请求头。当前设计保留原始 Bearer Token，并由 Agent 向 SSPX 重新验证身份，再将认证用户的 `CompanyId` 与 `X-Company-Id` 交叉校验，形成纵深防御。生产环境还应限制 Agent 端口只能由 Gateway 访问。

### 这次问题带来的工程经验是什么？

- Java 编译通过不等于 Spring ApplicationContext 能正常启动。
- 注册 MVC 扩展时，应注意构造依赖是否会反向依赖 MVC 基础设施。
- 外部客户端应设置清晰的创建边界、超时和失败关闭策略。
- 修复循环依赖时，应优先修正依赖关系或生命周期，而不是打开全局兼容开关。
- 修复后既要验证启动，也要触发第一次真实请求，避免把初始化错误推迟到线上流量阶段。
