# Agent MySQL 基础设施设计

## 目标

为智能坐席自己的业务数据建立独立、可迁移、可观测的 MySQL 基础设施，同时与现有 EC MySQL 5.7 老库保持明确隔离。

本阶段只完成数据库容器、连接池、MyBatis-Plus、Flyway 和健康检查，不提前设计聊天、用户、提示词或知识库业务表。

## 技术选择

- Agent 数据库：MySQL 8.4 LTS Docker 容器。
- 数据访问：MyBatis-Plus 3.5.17 的 Spring Boot 3 Starter。
- 连接池：Spring Boot 默认的 HikariCP。
- 数据库迁移：Flyway Core 与 Flyway MySQL 模块。
- JDBC 驱动：Spring Boot 依赖管理提供的 MySQL Connector/J。
- 验证方式：Docker 健康状态、应用启动日志、Actuator 与现有接口回归。

## 数据源分阶段策略

### 当前阶段

只接入 Agent 数据库，使用 Spring Boot 的单数据源自动配置：

```text
Spring Boot
    |
    +-- HikariCP
    |      |
    |      +-- 127.0.0.1:3307/order_logistics_agent
    |
    +-- MyBatis-Plus
    |
    +-- Flyway
```

### 后续阶段

EC MySQL 5.7 作为第二数据源单独接入：

- 使用独立配置类、独立 Mapper 包和独立事务管理器。
- 使用只读数据库账号，并在连接与事务层声明只读。
- Flyway 永远不管理 EC 数据库。
- 不采用通过注解动态切换数据源的方案，避免漏写注解时选错数据库。
- 不接入 OMS 数据库。

## MySQL 容器

- 镜像主版本：`mysql:8.4`。
- 容器内端口：`3306`。
- Windows 宿主机端口：`3307`，避免与现有 MySQL 5.7 的 `3306` 冲突。
- 数据库名：`order_logistics_agent`。
- 字符集：`utf8mb4`。
- 排序规则：`utf8mb4_0900_ai_ci`。
- 使用命名 volume 持久化 `/var/lib/mysql`。
- 配置容器 healthcheck，应用启动前可以明确判断数据库是否就绪。

本地学习阶段按用户选择使用容器 root 账号。生产环境必须改为 Agent 专用的最小权限账号。

## 配置与密钥归属

### Git 仓库

Git 中可以保存：

- Docker Compose 的服务、端口、volume 和 healthcheck 结构。
- Maven 依赖。
- Flyway SQL 迁移脚本。
- `.env.example` 中的环境变量名称和非敏感示例。

Git 中不能保存真实数据库密码。

### Nacos

Nacos 保存非敏感运行参数：

- JDBC URL。
- HikariCP 最小空闲连接数、最大连接数和连接超时。
- Flyway 开关与脚本路径。
- MyBatis-Plus 通用配置。

Nacos 中的密码配置只引用环境变量占位符，不保存真实密码。

### 本机环境变量

真实凭据通过本机环境变量提供：

- `AGENT_DB_USERNAME`
- `AGENT_DB_PASSWORD`
- `AGENT_MYSQL_ROOT_PASSWORD`

这些变量可以在 IDEA Run Configuration 和 Docker Compose 的本地环境文件中配置。本地环境文件必须被 `.gitignore` 排除。

## 连接池基线

本机学习环境使用以下初始值：

- `minimum-idle`: 2
- `maximum-pool-size`: 10
- `connection-timeout`: 3000 毫秒

这些值是开发基线，不直接宣称为生产最优值。后续通过并发评测、连接等待指标和数据库容量再调整。

## Flyway 边界

- Flyway 仅连接并管理 `order_logistics_agent`。
- 迁移文件位置使用 `classpath:db/migration`。
- 已经执行过的版本脚本不得原地修改；数据库变化通过新增版本脚本完成。
- 当前基础设施阶段不创建空洞的测试表，也不创建无业务意义的占位迁移。
- 第一个真实业务模块确定表结构后，再使用描述该业务结构的 `V1` 迁移文件。
- 后续 EC 数据源不参与 Flyway 自动迁移。

## 启动与失败处理

数据库基础设施采用 fail-fast：

- MySQL 容器不可访问时，应用启动失败。
- 数据库不存在时，应用启动失败。
- 用户名或密码错误时，应用启动失败。
- Flyway 迁移校验失败时，应用启动失败。
- 不允许应用在数据库不可用的状态下对外声明整体健康。

日志和异常响应不得打印数据库密码。

## 验证方案

本阶段不新增测试类，按已确认的学习方式执行运行时验证：

1. `docker ps` 显示 MySQL 容器为 healthy。
2. Spring Boot 正常启动，HikariCP 与 Flyway 日志无异常。
3. 不带租户请求头访问 `/actuator/health`，返回 HTTP 200 和 `UP`。
4. 使用 `X-Company-Id: 1` 访问 `/api/v1/system/ping`，返回 HTTP 200，并包含 `companyId: 1`。
5. 缺失、格式错误和越权的租户请求仍分别返回既定错误。

真正的数据 Mapper 将随第一个业务表一起实现和验证，不为基础设施额外创建探针 Mapper 或测试表。

## 非目标

- 不在本阶段创建用户、会话、消息、提示词或知识库表。
- 不在本阶段连接 EC 数据源。
- 不实现动态数据源路由。
- 不使用 EC MySQL 5.7 存储 Agent 自身数据。
- 不升级或修改现有 EC MySQL 5.7 实例。

## 兼容性依据

- MyBatis-Plus 官方为 Spring Boot 3 提供 `mybatis-plus-spring-boot3-starter`，当前设计版本为 3.5.17：https://baomidou.com/en/getting-started/install/
- Flyway 的 MySQL 支持需要单独引入数据库模块：https://documentation.red-gate.com/fd/mysql-277579322.html
- Spring Boot 通过 `spring.datasource.*` 自动配置数据源和连接池：https://docs.spring.io/spring-boot/3.5/reference/data/sql.html
- MySQL 5.7 已进入 Sustaining Support，官方建议升级：https://www.mysql.com/cn/support/eol-notice.html
