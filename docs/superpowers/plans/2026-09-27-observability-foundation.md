# Observability Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 Agent Server 和 Knowledge Service 建立生产一致的指标、Trace、结构化日志采集基础，并提供可在本地验证的 Prometheus、Tempo、Loki、Grafana、Alertmanager 与 OpenTelemetry Collector 集成栈。

**Architecture:** 两个Spring Boot服务通过Micrometer暴露Prometheus指标并通过OTLP发送Trace；JSON日志写入可配置目录，由Collector的`filelog`接收器采集；Collector执行资源补全、敏感属性删除、批处理和尾部采样，再将指标、Trace和日志分别导出到Prometheus、Tempo和Loki。单机Compose只用于开发和集成验证，生产部署沿用相同遥测协议并使用独立高可用基础设施。

**Tech Stack:** Java 21、Spring Boot 3.5.16、Micrometer Tracing、OpenTelemetry OTLP、Logstash Logback Encoder、OpenTelemetry Collector Contrib 0.161.0、Prometheus 3.15.0、Grafana 12.4.10、Tempo 2.10.8、Loki 3.7.7、Alertmanager 0.34.1、Docker Compose。

---

## 文件结构

### Agent Server

- Modify: `D:\GitCode\order-logistics-agent-server\pom.xml`
- Modify: `D:\GitCode\order-logistics-agent-server\src\main\resources\application.properties`
- Create: `D:\GitCode\order-logistics-agent-server\src\main\resources\logback-spring.xml`
- Create: `D:\GitCode\order-logistics-agent-server\src\test\java\com\xjjk\agent\observation\TelemetryConfigurationContractTest.java`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\compose.observability.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\.env.observability.example`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\collector\otel-collector.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\prometheus\prometheus.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\prometheus\alerts.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\tempo\tempo.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\loki\loki.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\alertmanager\alertmanager.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\grafana\provisioning\datasources\datasources.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\grafana\provisioning\dashboards\dashboards.yml`
- Create: `D:\GitCode\order-logistics-agent-server\infra\observability\grafana\dashboards\observability-overview.json`
- Create: `D:\GitCode\order-logistics-agent-server\scripts\verify-observability.ps1`
- Create: `D:\GitCode\order-logistics-agent-server\docs\runbook\observability-stack.md`

### Knowledge Service

- Modify: `D:\GitCode\order-logistics-knowledge-service\pom.xml`
- Modify: `D:\GitCode\order-logistics-knowledge-service\src\main\resources\application.yml`
- Create: `D:\GitCode\order-logistics-knowledge-service\src\main\resources\logback-spring.xml`
- Create: `D:\GitCode\order-logistics-knowledge-service\src\test\java\com\xjjk\knowledge\observation\TelemetryConfigurationContractTest.java`

## Task 1：Agent Server接入Prometheus和OpenTelemetry Trace

**Files:**

- Modify: `D:\GitCode\order-logistics-agent-server\pom.xml`
- Modify: `D:\GitCode\order-logistics-agent-server\src\main\resources\application.properties`
- Test: `D:\GitCode\order-logistics-agent-server\src\test\java\com\xjjk\agent\observation\TelemetryConfigurationContractTest.java`

- [ ] **Step 1：写配置契约失败测试**

创建测试，先固定必须存在的Actuator、Prometheus和OTLP配置：

```java
package com.xjjk.agent.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class TelemetryConfigurationContractTest {

    @Test
    void shouldExposePrometheusAndConfigureOtlpTracing() throws IOException {
        Properties properties = new Properties();
        try (InputStream input = getClass().getResourceAsStream("/application.properties")) {
            assertThat(input).isNotNull();
            properties.load(input);
        }

        assertThat(properties.getProperty("management.endpoints.web.exposure.include"))
                .contains("health", "info", "prometheus");
        assertThat(properties.getProperty("management.tracing.sampling.probability"))
                .isEqualTo("${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}");
        assertThat(properties.getProperty("management.otlp.tracing.endpoint"))
                .isEqualTo("${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://127.0.0.1:4318/v1/traces}");
        assertThat(properties.getProperty("management.observations.annotations.enabled"))
                .isEqualTo("true");
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run:

```powershell
.\mvnw.cmd -Dtest=TelemetryConfigurationContractTest test
```

Expected: FAIL，因为当前 `application.properties` 尚未包含管理和OTLP配置。

- [ ] **Step 3：增加运行依赖**

在Agent Server `pom.xml` 的Actuator依赖后加入：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.1</version>
</dependency>
```

- [ ] **Step 4：增加应用遥测配置**

在 `application.properties` 末尾加入：

```properties

# 生产可观测性：指标由Prometheus抓取，Trace异步发送到OpenTelemetry Collector。
management.endpoints.web.exposure.include=health,info,prometheus
management.endpoint.health.probes.enabled=true
management.metrics.tags.application=${spring.application.name}
management.metrics.tags.environment=${DEPLOYMENT_ENVIRONMENT:local}
management.tracing.sampling.probability=${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}
management.otlp.tracing.endpoint=${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://127.0.0.1:4318/v1/traces}
management.otlp.tracing.connect-timeout=2s
management.otlp.tracing.timeout=5s
management.observations.annotations.enabled=true
management.metrics.distribution.percentiles-histogram.http.server.requests=true
management.metrics.distribution.percentiles-histogram.http.client.requests=true
```

不在业务配置中写Collector密钥；生产OTLP认证头由Secret映射为环境变量并在部署层注入。

- [ ] **Step 5：运行测试和应用上下文测试**

Run:

```powershell
.\mvnw.cmd -Dtest=TelemetryConfigurationContractTest test
.\mvnw.cmd -Dtest=OrderLogisticsAgentServerApplicationTests test
```

Expected: `TelemetryConfigurationContractTest` 与现有 `OrderLogisticsAgentServerApplicationTests` 均PASS。

- [ ] **Step 6：提交Agent基础依赖与配置**

```powershell
git add -- pom.xml src/main/resources/application.properties src/test/java/com/xjjk/agent/observation/TelemetryConfigurationContractTest.java
git commit -m "feat: add agent telemetry foundation"
```

## Task 2：Knowledge Service接入OpenTelemetry Trace

**Files:**

- Modify: `D:\GitCode\order-logistics-knowledge-service\pom.xml`
- Modify: `D:\GitCode\order-logistics-knowledge-service\src\main\resources\application.yml`
- Test: `D:\GitCode\order-logistics-knowledge-service\src\test\java\com\xjjk\knowledge\observation\TelemetryConfigurationContractTest.java`

- [ ] **Step 1：写配置契约失败测试**

```java
package com.xjjk.knowledge.observation;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TelemetryConfigurationContractTest {

    @Test
    void shouldExposePrometheusAndConfigureOtlpTracing() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/application.yml")) {
            assertThat(input).isNotNull();
            String yaml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(yaml)
                    .contains("include: health,info,prometheus")
                    .contains("sampling:")
                    .contains("probability: ${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}")
                    .contains("endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://127.0.0.1:4318/v1/traces}");
        }
    }
}
```

- [ ] **Step 2：运行测试确认失败**

Run:

```powershell
Set-Location 'D:\GitCode\order-logistics-knowledge-service'
.\mvnw.cmd -Dtest=TelemetryConfigurationContractTest test
```

Expected: FAIL，因为当前只配置了Prometheus，没有Trace导出配置。

- [ ] **Step 3：增加Trace和JSON日志依赖**

保留已有 `micrometer-registry-prometheus`，在其后加入：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
<dependency>
    <groupId>net.logstash.logback</groupId>
    <artifactId>logstash-logback-encoder</artifactId>
    <version>8.1</version>
</dependency>
```

- [ ] **Step 4：补充Knowledge遥测配置**

在现有 `management` 下合并以下配置，不复制第二个顶层 `management`：

```yaml
management:
  endpoint:
    health:
      show-details: when_authorized
      probes:
        enabled: true
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
  metrics:
    tags:
      application: ${spring.application.name}
      environment: ${DEPLOYMENT_ENVIRONMENT:local}
    distribution:
      percentiles-histogram:
        http.server.requests: true
        http.client.requests: true
        knowledge.retrieval.duration: true
        knowledge.ingestion.duration: true
  tracing:
    sampling:
      probability: ${MANAGEMENT_TRACING_SAMPLING_PROBABILITY:0.1}
  otlp:
    tracing:
      endpoint: ${OTEL_EXPORTER_OTLP_TRACES_ENDPOINT:http://127.0.0.1:4318/v1/traces}
      connect-timeout: 2s
      timeout: 5s
  observations:
    annotations:
      enabled: true
```

- [ ] **Step 5：运行测试**

```powershell
.\mvnw.cmd -Dtest=TelemetryConfigurationContractTest,KnowledgeServiceApplicationTest test
```

Expected: 遥测契约测试PASS；如果现有根上下文测试仍因已知的排除数据库配置导致 `OutboxPublisher` 缺少 `OutboxMapper` 而失败，必须单独记录该既有失败，同时运行全部不依赖该错误配置的Knowledge测试，不能把它误报为本次引入的回归。

- [ ] **Step 6：提交Knowledge基础依赖与配置**

```powershell
git add -- pom.xml src/main/resources/application.yml src/test/java/com/xjjk/knowledge/observation/TelemetryConfigurationContractTest.java
git commit -m "feat: add knowledge tracing foundation"
```

## Task 3：两个服务输出安全JSON结构化日志

**Files:**

- Create: `D:\GitCode\order-logistics-agent-server\src\main\resources\logback-spring.xml`
- Create: `D:\GitCode\order-logistics-knowledge-service\src\main\resources\logback-spring.xml`
- Modify: 两个 `TelemetryConfigurationContractTest.java`

- [ ] **Step 1：为日志配置增加失败断言**

在两个契约测试中分别增加：

```java
@Test
void shouldUseStructuredLogConfiguration() throws IOException {
    try (InputStream input = getClass().getResourceAsStream("/logback-spring.xml")) {
        assertThat(input).isNotNull();
        String xml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        assertThat(xml)
                .contains("LogstashEncoder")
                .contains("traceId")
                .contains("spanId")
                .contains("requestId")
                .contains("OBSERVABILITY_LOG_DIR");
    }
}
```

Agent测试需要补充 `StandardCharsets` import；Knowledge测试已经包含。

- [ ] **Step 2：运行测试确认失败**

分别运行：

```powershell
Set-Location 'D:\GitCode\order-logistics-agent-server'
.\mvnw.cmd -Dtest=TelemetryConfigurationContractTest test

Set-Location 'D:\GitCode\order-logistics-knowledge-service'
.\mvnw.cmd -Dtest=TelemetryConfigurationContractTest test
```

Expected: 两边都因缺少 `logback-spring.xml` 失败。

- [ ] **Step 3：创建Agent日志配置**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="applicationName"
                    source="spring.application.name"
                    defaultValue="order-logistics-agent-server"/>
    <property name="LOG_DIR" value="${OBSERVABILITY_LOG_DIR:-logs}"/>

    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${applicationName}"}</customFields>
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>requestId</includeMdcKeyName>
        </encoder>
    </appender>

    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${applicationName}.json</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${applicationName}.%d{yyyy-MM-dd}.%i.json.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>14</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${applicationName}"}</customFields>
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>requestId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON_CONSOLE"/>
        <appender-ref ref="JSON_FILE"/>
    </root>
</configuration>
```

- [ ] **Step 4：创建Knowledge日志配置**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="applicationName"
                    source="spring.application.name"
                    defaultValue="order-logistics-knowledge-service"/>
    <property name="LOG_DIR" value="${OBSERVABILITY_LOG_DIR:-logs}"/>

    <appender name="JSON_CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${applicationName}"}</customFields>
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>requestId</includeMdcKeyName>
        </encoder>
    </appender>

    <appender name="JSON_FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${applicationName}.json</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${applicationName}.%d{yyyy-MM-dd}.%i.json.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>14</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"service":"${applicationName}"}</customFields>
            <includeMdcKeyName>traceId</includeMdcKeyName>
            <includeMdcKeyName>spanId</includeMdcKeyName>
            <includeMdcKeyName>requestId</includeMdcKeyName>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="JSON_CONSOLE"/>
        <appender-ref ref="JSON_FILE"/>
    </root>
</configuration>
```

不要在任何logger中记录HTTP请求体、模型Prompt、知识正文、记忆正文、Authorization或Cookie。

- [ ] **Step 5：运行日志配置测试和启动冒烟**

分别运行两个契约测试，然后以测试配置启动每个服务一次，确认日志文件每行都是合法JSON，并包含 `service` 字段；发起一个HTTP请求后，应用请求日志中的 `traceId/spanId` 非空。启动失败时先修复日志配置，不能通过删除文件Appender绕过。

- [ ] **Step 6：分别提交日志配置**

Agent：

```powershell
git add -- src/main/resources/logback-spring.xml src/test/java/com/xjjk/agent/observation/TelemetryConfigurationContractTest.java
git commit -m "feat: emit structured agent logs"
```

Knowledge：

```powershell
git add -- src/main/resources/logback-spring.xml src/test/java/com/xjjk/knowledge/observation/TelemetryConfigurationContractTest.java
git commit -m "feat: emit structured knowledge logs"
```

## Task 4：建立可复现的观测基础设施栈

**Files:**

- Create: `infra/observability/.env.observability.example`
- Create: `infra/observability/compose.observability.yml`
- Create: `infra/observability/collector/otel-collector.yml`
- Create: `infra/observability/prometheus/prometheus.yml`
- Create: `infra/observability/tempo/tempo.yml`
- Create: `infra/observability/loki/loki.yml`
- Create: `infra/observability/alertmanager/alertmanager.yml`

- [ ] **Step 1：写Compose静态校验脚本并确认失败**

先创建 `scripts/verify-observability.ps1`：

```powershell
$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
$compose = Join-Path $repoRoot 'infra\observability\compose.observability.yml'

if (-not (Test-Path -LiteralPath $compose)) {
    throw "缺少观测Compose文件：$compose"
}

docker compose --env-file (Join-Path $repoRoot 'infra\observability\.env.observability') `
    -f $compose config --quiet

$required = @(
    'http://127.0.0.1:9090/-/ready',
    'http://127.0.0.1:3000/api/health',
    'http://127.0.0.1:3100/ready',
    'http://127.0.0.1:3200/ready',
    'http://127.0.0.1:13133/'
)
foreach ($uri in $required) {
    $response = Invoke-WebRequest -UseBasicParsing -Uri $uri -TimeoutSec 5
    if ($response.StatusCode -ne 200) {
        throw "观测组件未就绪：$uri"
    }
}
Write-Output 'observability stack is healthy'
```

Run:

```powershell
.\scripts\verify-observability.ps1
```

Expected: FAIL，提示缺少Compose文件。

- [ ] **Step 2：固定镜像版本和本地密钥入口**

创建 `.env.observability.example`：

```dotenv
OTEL_COLLECTOR_VERSION=0.161.0
PROMETHEUS_VERSION=3.15.0
GRAFANA_VERSION=12.4.10
TEMPO_VERSION=2.10.8
LOKI_VERSION=3.7.7
ALERTMANAGER_VERSION=0.34.1
GRAFANA_ADMIN_USER=admin
GRAFANA_ADMIN_PASSWORD=change-me-before-start
DEPLOYMENT_ENVIRONMENT=local
```

实际 `.env.observability` 加入 `.gitignore`，不得提交真实密码。

- [ ] **Step 3：创建Compose编排**

`compose.observability.yml` 必须包含以下服务、持久卷和健康检查：

```yaml
name: order-logistics-observability

x-runtime-defaults: &runtime-defaults
  restart: unless-stopped
  logging:
    driver: json-file
    options:
      max-size: 20m
      max-file: "3"

services:
  otel-collector:
    <<: *runtime-defaults
    image: otel/opentelemetry-collector-contrib:${OTEL_COLLECTOR_VERSION}
    command: ["--config=/etc/otelcol/config.yml"]
    environment:
      DEPLOYMENT_ENVIRONMENT: ${DEPLOYMENT_ENVIRONMENT}
      AGENT_METRICS_TARGET: host.docker.internal:8082
      KNOWLEDGE_METRICS_TARGET: host.docker.internal:8084
    ports:
      - "127.0.0.1:4317:4317"
      - "127.0.0.1:4318:4318"
      - "127.0.0.1:13133:13133"
      - "127.0.0.1:8889:8889"
    volumes:
      - ./collector/otel-collector.yml:/etc/otelcol/config.yml:ro
      - ../../logs:/var/log/order-logistics/agent:ro
      - ../../../order-logistics-knowledge-service/logs:/var/log/order-logistics/knowledge:ro
    depends_on:
      tempo:
        condition: service_healthy
      loki:
        condition: service_healthy

  prometheus:
    <<: *runtime-defaults
    image: prom/prometheus:v${PROMETHEUS_VERSION}
    command:
      - --config.file=/etc/prometheus/prometheus.yml
      - --storage.tsdb.path=/prometheus
      - --storage.tsdb.retention.time=30d
      - --web.enable-lifecycle
      - --web.enable-remote-write-receiver
    ports: ["127.0.0.1:9090:9090"]
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
      - ./prometheus/alerts.yml:/etc/prometheus/alerts.yml:ro
      - prometheus-data:/prometheus
    depends_on:
      otel-collector:
        condition: service_started

  tempo:
    <<: *runtime-defaults
    image: grafana/tempo:${TEMPO_VERSION}
    command: ["-config.file=/etc/tempo/tempo.yml"]
    ports: ["127.0.0.1:3200:3200"]
    volumes:
      - ./tempo/tempo.yml:/etc/tempo/tempo.yml:ro
      - tempo-data:/var/tempo
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3200/ready"]
      interval: 10s
      timeout: 5s
      retries: 30

  loki:
    <<: *runtime-defaults
    image: grafana/loki:${LOKI_VERSION}
    command: ["-config.file=/etc/loki/loki.yml"]
    ports: ["127.0.0.1:3100:3100"]
    volumes:
      - ./loki/loki.yml:/etc/loki/loki.yml:ro
      - loki-data:/loki
    healthcheck:
      test: ["CMD", "wget", "-q", "-O", "-", "http://127.0.0.1:3100/ready"]
      interval: 10s
      timeout: 5s
      retries: 30

  alertmanager:
    <<: *runtime-defaults
    image: prom/alertmanager:v${ALERTMANAGER_VERSION}
    command: ["--config.file=/etc/alertmanager/alertmanager.yml", "--storage.path=/alertmanager"]
    ports: ["127.0.0.1:9093:9093"]
    volumes:
      - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro
      - alertmanager-data:/alertmanager

  grafana:
    <<: *runtime-defaults
    image: grafana/grafana:${GRAFANA_VERSION}
    environment:
      GF_SECURITY_ADMIN_USER: ${GRAFANA_ADMIN_USER}
      GF_SECURITY_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
      GF_USERS_ALLOW_SIGN_UP: "false"
      GF_AUTH_ANONYMOUS_ENABLED: "false"
    ports: ["127.0.0.1:3000:3000"]
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus
      - tempo
      - loki

volumes:
  prometheus-data:
  tempo-data:
  loki-data:
  alertmanager-data:
  grafana-data:
```

Tempo固定在官方仍维护并包含安全修复的2.10.8版本，避免Tempo 3.x分布式写入架构给本地单机验证额外引入Kafka；生产环境可以独立评估3.x迁移，但禁止把镜像改成 `latest` 或静默更换未记录版本。

- [ ] **Step 4：创建Collector配置**

```yaml
receivers:
  otlp:
    protocols:
      grpc:
        endpoint: 0.0.0.0:4317
      http:
        endpoint: 0.0.0.0:4318
  prometheus:
    config:
      scrape_configs:
        - job_name: order-logistics-agent-server
          metrics_path: /actuator/prometheus
          static_configs:
            - targets: ["${env:AGENT_METRICS_TARGET}"]
        - job_name: order-logistics-knowledge-service
          metrics_path: /actuator/prometheus
          static_configs:
            - targets: ["${env:KNOWLEDGE_METRICS_TARGET}"]
  filelog/agent:
    include: [/var/log/order-logistics/agent/*.json]
    start_at: end
    operators:
      - type: json_parser
  filelog/knowledge:
    include: [/var/log/order-logistics/knowledge/*.json]
    start_at: end
    operators:
      - type: json_parser

processors:
  memory_limiter:
    check_interval: 1s
    limit_mib: 512
    spike_limit_mib: 128
  resource:
    attributes:
      - key: deployment.environment
        value: ${env:DEPLOYMENT_ENVIRONMENT}
        action: upsert
  attributes/redact:
    actions:
      - key: http.request.header.authorization
        action: delete
      - key: http.request.header.cookie
        action: delete
      - key: user.message
        action: delete
      - key: model.prompt
        action: delete
      - key: knowledge.evidence
        action: delete
      - key: memory.content
        action: delete
  tail_sampling:
    decision_wait: 5s
    num_traces: 50000
    expected_new_traces_per_sec: 200
    policies:
      - name: errors
        type: status_code
        status_code: {status_codes: [ERROR]}
      - name: evaluation
        type: string_attribute
        string_attribute: {key: evaluation.run_id, values: [".+"], enabled_regex_matching: true}
      - name: normal-sample
        type: probabilistic
        probabilistic: {sampling_percentage: 10}
  batch:
    send_batch_size: 1024
    timeout: 2s

exporters:
  prometheus:
    endpoint: 0.0.0.0:8889
    resource_to_telemetry_conversion: {enabled: true}
  otlphttp/tempo:
    endpoint: http://tempo:4318
    tls: {insecure: true}
  otlphttp/loki:
    endpoint: http://loki:3100/otlp
    tls: {insecure: true}
  debug:
    verbosity: basic

extensions:
  health_check:
    endpoint: 0.0.0.0:13133

service:
  extensions: [health_check]
  pipelines:
    metrics:
      receivers: [prometheus]
      processors: [memory_limiter, resource, batch]
      exporters: [prometheus]
    traces:
      receivers: [otlp]
      processors: [memory_limiter, resource, attributes/redact, tail_sampling, batch]
      exporters: [otlphttp/tempo]
    logs:
      receivers: [filelog/agent, filelog/knowledge, otlp]
      processors: [memory_limiter, resource, attributes/redact, batch]
      exporters: [otlphttp/loki]
```

- [ ] **Step 5：创建Prometheus配置**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

rule_files:
  - /etc/prometheus/alerts.yml

alerting:
  alertmanagers:
    - static_configs:
        - targets: [alertmanager:9093]

scrape_configs:
  - job_name: otel-collector
    static_configs:
      - targets: [otel-collector:8889]
```

- [ ] **Step 6：创建Tempo、Loki和Alertmanager最小安全配置**

创建 `tempo/tempo.yml`：

```yaml
stream_over_http_enabled: true

server:
  http_listen_port: 3200

distributor:
  receivers:
    otlp:
      protocols:
        grpc:
          endpoint: 0.0.0.0:4317
        http:
          endpoint: 0.0.0.0:4318

ingester:
  trace_idle_period: 10s
  max_block_duration: 5m

compactor:
  compaction:
    block_retention: 168h

metrics_generator:
  registry:
    external_labels:
      source: tempo
  storage:
    path: /var/tempo/generator/wal
    remote_write:
      - url: http://prometheus:9090/api/v1/write
        send_exemplars: true

storage:
  trace:
    backend: local
    wal:
      path: /var/tempo/wal
    local:
      path: /var/tempo/blocks

overrides:
  defaults:
    metrics_generator:
      processors: [service-graphs, span-metrics, local-blocks]
```

创建 `loki/loki.yml`：

```yaml
auth_enabled: false

server:
  http_listen_port: 3100

common:
  path_prefix: /loki
  storage:
    filesystem:
      chunks_directory: /loki/chunks
      rules_directory: /loki/rules
  replication_factor: 1
  ring:
    kvstore:
      store: inmemory

schema_config:
  configs:
    - from: 2024-01-01
      store: tsdb
      object_store: filesystem
      schema: v13
      index:
        prefix: index_
        period: 24h

limits_config:
  retention_period: 336h
  allow_structured_metadata: true

compactor:
  working_directory: /loki/compactor
  retention_enabled: true
  delete_request_store: filesystem

analytics:
  reporting_enabled: false
```

Tempo本地保留7天Trace，Loki本地保留14天日志；生产环境改用对象存储并部署多副本，不能复用这里的单机文件系统配置。Alertmanager初始使用本地接收器，不填写任何真实Webhook：

```yaml
route:
  receiver: local-default
  group_by: [alertname, service, environment]
  group_wait: 30s
  group_interval: 5m
  repeat_interval: 4h
receivers:
  - name: local-default
```

创建上述三个文件后分别启动并检查 `/ready`；如果配置字段与固定镜像版本不匹配，依据该版本官方配置调整并把最终配置完整提交。

- [ ] **Step 7：运行Compose静态校验**

```powershell
Copy-Item infra\observability\.env.observability.example infra\observability\.env.observability
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml config --quiet
```

Expected: exit code 0，没有缺失变量、无效挂载或YAML错误。

- [ ] **Step 8：提交基础设施栈**

```powershell
git add -- .gitignore infra/observability scripts/verify-observability.ps1
git commit -m "feat: add production observability stack"
```

## Task 5：配置Grafana数据源、基础看板和告警

**Files:**

- Create: `infra/observability/grafana/provisioning/datasources/datasources.yml`
- Create: `infra/observability/grafana/provisioning/dashboards/dashboards.yml`
- Create: `infra/observability/grafana/dashboards/observability-overview.json`
- Create: `infra/observability/prometheus/alerts.yml`

- [ ] **Step 1：创建数据源配置**

```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    uid: prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    jsonData:
      exemplarTraceIdDestinations:
        - name: trace_id
          datasourceUid: tempo
  - name: Tempo
    uid: tempo
    type: tempo
    access: proxy
    url: http://tempo:3200
    jsonData:
      tracesToLogsV2:
        datasourceUid: loki
        spanStartTimeShift: -1m
        spanEndTimeShift: 1m
        filterByTraceID: true
        filterBySpanID: true
      serviceMap:
        datasourceUid: prometheus
  - name: Loki
    uid: loki
    type: loki
    access: proxy
    url: http://loki:3100
    jsonData:
      derivedFields:
        - name: TraceID
          matcherRegex: '"traceId":"([a-f0-9]+)"'
          datasourceUid: tempo
          url: '$${__value.raw}'
```

- [ ] **Step 2：创建Dashboard Provider**

```yaml
apiVersion: 1
providers:
  - name: order-logistics
    orgId: 1
    folder: Order Logistics Agent
    type: file
    disableDeletion: true
    updateIntervalSeconds: 30
    options:
      path: /var/lib/grafana/dashboards
```

- [ ] **Step 3：创建基础总览Dashboard**

创建以下完整JSON；四个Panel分别展示吞吐、5xx、P95和Collector健康状态：

```json
{
  "annotations": {"list": []},
  "editable": true,
  "graphTooltip": 1,
  "links": [
    {
      "title": "打开 Tempo Explore",
      "type": "link",
      "url": "/explore?left={\"datasource\":\"tempo\"}"
    }
  ],
  "panels": [
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "reqps"}, "overrides": []},
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 0},
      "id": 1,
      "targets": [{"expr": "sum(rate(http_server_requests_seconds_count{application=~\"order-logistics-.*\"}[5m])) by (application)", "legendFormat": "{{application}}", "refId": "A"}],
      "title": "HTTP吞吐",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "reqps"}, "overrides": []},
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 0},
      "id": 2,
      "targets": [{"expr": "sum(rate(http_server_requests_seconds_count{status=~\"5..\"}[5m])) by (application)", "legendFormat": "{{application}}", "refId": "A"}],
      "title": "HTTP 5xx",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"unit": "s"}, "overrides": []},
      "gridPos": {"h": 8, "w": 12, "x": 0, "y": 8},
      "id": 3,
      "targets": [{"expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, application))", "legendFormat": "{{application}}", "refId": "A"}],
      "title": "HTTP P95耗时",
      "type": "timeseries"
    },
    {
      "datasource": {"type": "prometheus", "uid": "prometheus"},
      "fieldConfig": {"defaults": {"mappings": [], "thresholds": {"mode": "absolute", "steps": [{"color": "red", "value": null}, {"color": "green", "value": 1}]}}, "overrides": []},
      "gridPos": {"h": 8, "w": 12, "x": 12, "y": 8},
      "id": 4,
      "targets": [{"expr": "up{job=\"otel-collector\"}", "refId": "A"}],
      "title": "Collector健康状态",
      "type": "stat"
    }
  ],
  "refresh": "30s",
  "schemaVersion": 41,
  "tags": ["order-logistics", "observability"],
  "templating": {"list": []},
  "time": {"from": "now-1h", "to": "now"},
  "timezone": "browser",
  "title": "Order Logistics Observability Overview",
  "uid": "order-logistics-observability-overview",
  "version": 1
}
```

创建后使用JSON解析命令验证：

```powershell
Get-Content infra\observability\grafana\dashboards\observability-overview.json -Raw | ConvertFrom-Json | Out-Null
```

- [ ] **Step 4：创建基础告警规则**

```yaml
groups:
  - name: observability-foundation
    rules:
      - alert: TelemetryCollectorDown
        expr: up{job="otel-collector"} == 0
        for: 2m
        labels: {severity: P1}
        annotations:
          summary: OpenTelemetry Collector不可用
          runbook: docs/runbook/observability-stack.md
      - alert: ServiceHighHttp5xxRate
        expr: |
          sum(rate(http_server_requests_seconds_count{status=~"5.."}[5m])) by (application)
          /
          clamp_min(sum(rate(http_server_requests_seconds_count[5m])) by (application), 0.001)
          > 0.05
        for: 5m
        labels: {severity: P1}
        annotations:
          summary: "{{ $labels.application }} 5xx比例连续5分钟超过5%"
          runbook: docs/runbook/observability-stack.md
      - alert: TelemetryExporterDrops
        expr: rate(otelcol_exporter_send_failed_spans[5m]) > 0
        for: 5m
        labels: {severity: P2}
        annotations:
          summary: Collector持续丢弃Trace
          runbook: docs/runbook/observability-stack.md
```

- [ ] **Step 5：启动并验证Grafana关联跳转**

启动栈后通过Grafana API检查三个数据源健康；发送一次Agent和Knowledge健康请求，确认Prometheus有HTTP指标、Tempo可按service查询Trace、Loki可按service查询日志，并从Trace跳转到相同 `traceId` 的日志。

- [ ] **Step 6：提交数据源、看板和告警**

```powershell
git add -- infra/observability/grafana infra/observability/prometheus/alerts.yml
git commit -m "feat: provision observability dashboards and alerts"
```

## Task 6：验证遥测故障不阻断业务

**Files:**

- Modify: `scripts/verify-observability.ps1`
- Create: `docs/runbook/observability-stack.md`

- [ ] **Step 1：扩展验证脚本**

在健康检查后增加：

```powershell
$agentHealth = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8082/actuator/health' -TimeoutSec 5
$knowledgeHealth = Invoke-WebRequest -UseBasicParsing -Uri 'http://127.0.0.1:8084/actuator/health' -TimeoutSec 5
if ($agentHealth.StatusCode -ne 200 -or $knowledgeHealth.StatusCode -ne 200) {
    throw '业务服务健康检查失败'
}

$targets = Invoke-RestMethod -Uri 'http://127.0.0.1:9090/api/v1/targets'
$down = @($targets.data.activeTargets | Where-Object { $_.health -ne 'up' })
if ($down.Count -gt 0) {
    throw "Prometheus存在不可用采集目标：$($down.scrapeUrl -join ', ')"
}
```

- [ ] **Step 2：运行正常路径验证**

```powershell
.\scripts\verify-observability.ps1
```

Expected: 输出 `observability stack is healthy`，且Prometheus所有目标为UP。

- [ ] **Step 3：执行Collector故障测试**

```powershell
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml stop otel-collector
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8082/actuator/health
Invoke-WebRequest -UseBasicParsing http://127.0.0.1:8084/actuator/health
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml start otel-collector
```

Expected: Collector停止期间两个业务健康接口仍返回200；日志出现异步导出失败或丢弃统计，但业务线程不等待Collector恢复。

- [ ] **Step 4：编写运行手册**

使用下面的完整内容创建 `docs/runbook/observability-stack.md`：

````markdown
# 可观测性平台运行手册

## 本地启动

```powershell
Copy-Item infra\observability\.env.observability.example infra\observability\.env.observability
notepad infra\observability\.env.observability
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml up -d
.\scripts\verify-observability.ps1
```

启动前必须修改 `GRAFANA_ADMIN_PASSWORD`。`.env.observability` 不得提交Git。

## 地址

| 组件 | 地址 |
|---|---|
| Grafana | `http://127.0.0.1:3000` |
| Prometheus | `http://127.0.0.1:9090` |
| Alertmanager | `http://127.0.0.1:9093` |
| Tempo API | `http://127.0.0.1:3200` |
| Loki API | `http://127.0.0.1:3100` |
| Collector Health | `http://127.0.0.1:13133` |

Tempo和Loki不直接作为日常查询界面，统一从Grafana Explore进入。

## 常用命令

```powershell
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml ps
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml logs --tail 200 otel-collector
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml restart otel-collector
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml down
```

日常停止不能增加 `-v`，否则会删除本地指标、Trace、日志和Grafana数据卷。

## 单次请求排查

1. 从客户端或服务日志取得 `requestId`。
2. 在Grafana Explore选择Loki，查询 `{service=~"order-logistics-.*"} |= "<requestId>"`。
3. 从日志字段取得 `traceId`，点击派生字段 `TraceID` 跳转Tempo。
4. 在Tempo确认失败Span及其 `error.type`、服务版本、阶段和依赖耗时。
5. 回到Loki按 `traceId` 查看同一次请求在两个服务中的结构化日志。

禁止把用户原始消息、完整Prompt、知识证据、记忆正文、Authorization和Cookie复制到工单。

## 故障处理

### Collector不可用

检查 `otel-collector` 日志中的接收、队列、批处理和导出错误。业务服务必须继续提供服务；如果业务线程等待Collector，立即按P1处理并回滚遥测变更。Collector恢复后确认 `otelcol_exporter_send_failed_*` 不再增长。

### Prometheus目标Down

打开 `http://127.0.0.1:9090/targets`，确认Agent端口8082、Knowledge端口8084和 `/actuator/prometheus` 可访问。检查认证拦截器是否错误保护了Actuator端点。

### Tempo或Loki不可写

检查组件 `/ready`、Collector导出错误和Docker卷磁盘空间。禁止通过关闭Collector队列上限解决；先恢复后端，再确认丢弃计数和告警恢复。

### 磁盘不足

使用 `docker system df -v` 和宿主机磁盘工具定位增长来源。先缩短开发环境留存或归档数据，不直接删除未知Docker卷。生产环境按对象存储生命周期和备份策略处理。

## 生产边界

本目录Compose只用于本地集成验证。生产环境必须启用TLS与认证、独立Secret、对象存储、组件多副本、容量配额、备份恢复和网络策略；Prometheus、Tempo、Loki和Grafana不能直接暴露公网。生产日志与Trace访问必须审计。

## 留存与敏感数据

本地指标保留30天、Trace保留7天、日志保留14天。生产期限由合规策略配置。发现敏感内容进入Loki或Tempo时，立即停止相关日志源、限制访问、记录事件范围、删除受影响数据并修复产生敏感字段的埋点，然后再恢复采集。
````

- [ ] **Step 5：提交脚本和Runbook**

```powershell
git add -- scripts/verify-observability.ps1 docs/runbook/observability-stack.md
git commit -m "docs: add observability verification runbook"
```

## Task 7：完整验收与基线记录

**Files:**

- Verify only; do not modify unrelated user files.

- [ ] **Step 1：运行Agent测试**

```powershell
Set-Location 'D:\GitCode\order-logistics-agent-server'
.\mvnw.cmd test
```

Expected: 全部测试通过。若存在本计划开始前已经记录的失败，必须给出失败测试名、开始前证据和本次相关性，不能直接声称通过。

- [ ] **Step 2：运行Knowledge测试**

```powershell
Set-Location 'D:\GitCode\order-logistics-knowledge-service'
.\mvnw.cmd test
```

Expected: 除已记录的既有 `KnowledgeServiceApplicationTest` 上下文问题外无新增失败；优先修复本计划导致的所有失败。

- [ ] **Step 3：验证Docker配置与运行状态**

```powershell
Set-Location 'D:\GitCode\order-logistics-agent-server'
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml config --quiet
.\scripts\verify-observability.ps1
```

Expected: Compose静态校验退出码0，全部组件和采集目标健康。

- [ ] **Step 4：验证没有提交密钥和高基数指标**

```powershell
rg -n "change-me-before-start|Authorization: Bearer|sk-[A-Za-z0-9_-]+" infra src/main/resources
rg -n "tag\(.*(requestId|conversationId|userId|tenantId|documentId)" src/main/java
```

Expected: 第一条只允许命中示例环境文件中的明确占位密码，不能命中真实密钥；第二条不得出现把高基数字段注册为指标标签的代码。

- [ ] **Step 5：记录两个仓库提交并停止本地栈**

```powershell
git -C 'D:\GitCode\order-logistics-agent-server' log -n 5 --oneline
git -C 'D:\GitCode\order-logistics-knowledge-service' log -n 5 --oneline
docker compose --env-file infra\observability\.env.observability -f infra\observability\compose.observability.yml down
```

不要添加 `-v`，保留本地观测数据供下一阶段验证。确认两个仓库都位于 `main`，且用户原有未提交业务文件仍然存在。
