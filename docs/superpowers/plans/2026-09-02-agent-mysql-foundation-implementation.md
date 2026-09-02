# Agent MySQL Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 使用 Docker MySQL 8.4、HikariCP、MyBatis-Plus 与 Flyway 为 Agent 业务库建立可启动、可迁移、可健康检查的基础设施。

**Architecture:** MySQL 8.4 容器通过宿主机 `3307` 提供 `order_logistics_agent` 数据库，避免占用 EC MySQL 5.7 使用的 `3306`。Spring Boot 使用单数据源自动配置，非敏感参数放入 Nacos，真实密码通过本机环境变量提供；Flyway 仅管理 Agent 库。

**Tech Stack:** Java 21、Spring Boot 3.5.16、MyBatis-Plus 3.5.17、HikariCP、Flyway、MySQL 8.4、Docker Compose、Nacos 3.2.3、Actuator

---

## 文件结构

- Create: `compose.yaml`：定义 MySQL 8.4 容器、端口、数据卷和健康检查。
- Create: `.env.example`：记录 Docker 所需环境变量名称，不包含真实密码。
- Modify: `.gitignore`：排除本地 `.env.local`。
- Modify: `pom.xml`：加入 MyBatis-Plus、MySQL Connector/J、Flyway 依赖。
- Modify externally: Nacos `order-logistics-agent-server.properties`：加入数据源、连接池、Flyway 与 MyBatis-Plus 配置。
- Modify externally: IDEA Run Configuration：加入 Agent 数据库用户名和密码。

本阶段不创建 Java 类、Mapper、业务表或测试类。

### Task 1：创建 Docker Compose 配置

**Files:**
- Create: `compose.yaml`
- Create: `.env.example`
- Modify: `.gitignore`

- [ ] **Step 1：创建 `compose.yaml`**

```yaml
services:
  mysql:
    image: mysql:8.4
    container_name: order-logistics-agent-mysql
    restart: unless-stopped
    ports:
      - "3307:3306"
    environment:
      MYSQL_ROOT_PASSWORD: ${AGENT_MYSQL_ROOT_PASSWORD}
      MYSQL_DATABASE: order_logistics_agent
      TZ: Asia/Shanghai
    command:
      - --character-set-server=utf8mb4
      - --collation-server=utf8mb4_0900_ai_ci
    volumes:
      - agent_mysql_data:/var/lib/mysql
    healthcheck:
      test:
        - CMD-SHELL
        - mysqladmin ping -h 127.0.0.1 -uroot -p$$MYSQL_ROOT_PASSWORD --silent
      interval: 5s
      timeout: 5s
      retries: 20
      start_period: 20s

volumes:
  agent_mysql_data:
```

- [ ] **Step 2：创建 `.env.example`**

```properties
AGENT_MYSQL_ROOT_PASSWORD=replace-with-a-local-strong-password
AGENT_DB_USERNAME=root
AGENT_DB_PASSWORD=replace-with-the-same-local-strong-password
```

- [ ] **Step 3：在 `.gitignore` 末尾增加本地密钥文件规则**

```gitignore

### Local secrets ###
.env.local
```

- [ ] **Step 4：创建本机 `.env.local`**

复制 `.env.example` 为 `.env.local`，把示例值替换为只在本机使用的强密码。不要把真实密码发送到聊天，也不要执行 `git add -f .env.local`。

- [ ] **Step 5：验证 Compose 配置能够解析**

```powershell
docker compose --env-file .env.local config --quiet
```

预期：命令退出且没有错误输出。

### Task 2：启动并验证 MySQL 8.4

**Files:**
- No file changes.

- [ ] **Step 1：启动 MySQL 容器**

```powershell
docker compose --env-file .env.local up -d mysql
```

预期：创建或启动 `order-logistics-agent-mysql`。

- [ ] **Step 2：查看容器状态**

```powershell
docker compose --env-file .env.local ps
```

预期：MySQL 的状态最终变为 `healthy`，宿主机端口显示为 `3307->3306`。

- [ ] **Step 3：确认服务器版本和数据库存在**

```powershell
docker exec order-logistics-agent-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT VERSION();"'
docker exec order-logistics-agent-mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SHOW DATABASES;"' | Select-String '^order_logistics_agent$'
```

预期：输出一个 `8.4.x` 版本号和 `order_logistics_agent`。命令文本不会展开或打印真实密码。

### Task 3：加入数据库依赖

**Files:**
- Modify: `pom.xml`

- [ ] **Step 1：在 `<properties>` 中加入 MyBatis-Plus 版本**

```xml
<mybatis-plus.version>3.5.17</mybatis-plus.version>
```

放在现有 `spring-cloud-alibaba.version` 后面。

- [ ] **Step 2：在 `<dependencies>` 中加入四个依赖**

```xml
<dependency>
    <groupId>com.baomidou</groupId>
    <artifactId>mybatis-plus-spring-boot3-starter</artifactId>
    <version>${mybatis-plus.version}</version>
</dependency>

<dependency>
    <groupId>com.mysql</groupId>
    <artifactId>mysql-connector-j</artifactId>
    <scope>runtime</scope>
</dependency>

<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>

<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-mysql</artifactId>
</dependency>
```

- [ ] **Step 3：在 IDEA Maven 面板点击 Reload All Maven Projects**

预期：`pom.xml` 中的依赖不再报红，External Libraries 中可以看到 MyBatis-Plus、MySQL Connector/J 和 Flyway。

本阶段按用户确认的日常开发方式使用 IDEA 自动编译，不执行 Maven 测试类。

### Task 4：在 Nacos 增加非敏感数据库配置

**Files:**
- Modify externally: Nacos namespace `order-logistics-agent-dev`
- Data ID: `order-logistics-agent-server.properties`
- Group: `ORDER_LOGISTICS_AGENT`

- [ ] **Step 1：保留原有租户配置，并追加以下配置**

```properties

# Agent MySQL
spring.datasource.url=${AGENT_DB_URL:jdbc:mysql://127.0.0.1:3307/order_logistics_agent?useUnicode=true&characterEncoding=UTF-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true}
spring.datasource.username=${AGENT_DB_USERNAME}
spring.datasource.password=${AGENT_DB_PASSWORD}

# HikariCP
spring.datasource.hikari.pool-name=AgentHikariPool
spring.datasource.hikari.minimum-idle=2
spring.datasource.hikari.maximum-pool-size=10
spring.datasource.hikari.connection-timeout=3000

# Flyway
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.flyway.validate-on-migrate=true
spring.flyway.clean-disabled=true

# MyBatis-Plus
mybatis-plus.configuration.map-underscore-to-camel-case=true
mybatis-plus.global-config.banner=false
```

- [ ] **Step 2：发布配置**

确认 Nacos 配置正文没有真实数据库密码，发布时填写配置变更说明：

```text
配置 Agent MySQL、HikariCP、Flyway 和 MyBatis-Plus
```

### Task 5：配置 IDEA 运行环境变量

**Files:**
- Modify externally: IDEA `OrderLogisticsAgentServerApplication` Run Configuration

- [ ] **Step 1：保留现有 Nacos 环境变量并追加两个变量**

```text
AGENT_DB_USERNAME=root
AGENT_DB_PASSWORD=与 .env.local 中相同的本机密码
```

不要删除已有的 `NACOS_USERNAME`、`NACOS_PASSWORD`、`NACOS_NAMESPACE` 或 `NACOS_SERVER_ADDR`。

- [ ] **Step 2：确认密码没有写入项目文件**

```powershell
git status --short
```

预期：看不到 `.env.local`，只看到本阶段允许提交的 `compose.yaml`、`.env.example`、`.gitignore` 和 `pom.xml`。

### Task 6：启动应用并完成回归验证

**Files:**
- No file changes.

- [ ] **Step 1：停止 IDEA 中的旧应用进程并重新启动**

预期日志包含：

- `AgentHikariPool` 启动成功。
- Flyway 成功连接 `order_logistics_agent`。
- 因当前没有迁移脚本，Flyway 可以提示没有找到 migration，但应用必须正常启动。

- [ ] **Step 2：验证 Actuator**

```http
GET http://localhost:8080/actuator/health
```

不添加 `X-Company-Id`。预期 HTTP 200，响应状态为 `UP`。

- [ ] **Step 3：验证合法租户接口**

```http
GET http://localhost:8080/api/v1/system/ping
X-Company-Id: 1
```

预期 HTTP 200，业务码为 `SUCCESS`，并包含 `companyId: 1`。

- [ ] **Step 4：回归异常租户场景**

- 无 `X-Company-Id`：HTTP 400，`TENANT_HEADER_MISSING`。
- `X-Company-Id: abc`：HTTP 400，`TENANT_INVALID`。
- `X-Company-Id: 2`：HTTP 403，`TENANT_ACCESS_DENIED`。

### Task 7：提交数据库基础设施检查点

**Files:**
- Stage: `.gitignore`
- Stage: `.env.example`
- Stage: `compose.yaml`
- Stage: `pom.xml`

- [ ] **Step 1：确认待提交内容不包含本机密码**

```powershell
git status --short
git diff --check
git diff -- .gitignore .env.example compose.yaml pom.xml
```

预期：`.env.local` 不在状态列表和差异中；差异只包含配置结构、示例值和依赖。

- [ ] **Step 2：提交检查点**

```powershell
git add .gitignore .env.example compose.yaml pom.xml
git commit -m "feat: add Agent MySQL infrastructure"
```

- [ ] **Step 3：检查工作区**

```powershell
git status --short
```

预期：没有输出。

- [ ] **Step 4：推送 `main`**

```powershell
git push origin main
```

预期：GitHub 的 `main` 包含数据库基础设施提交；`.env.local` 和真实密码不出现在仓库中。
