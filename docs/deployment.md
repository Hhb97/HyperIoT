# 部署与运维

## 1. 配置项

所有配置在 `hyperiot.*` 下，可用环境变量（见下表）或 `application-local.yml` / `--hyperiot.xxx=…` 覆盖。

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 控制台 / REST / WebSocket 端口 |
| `HYPERIOT_PUBLIC_BASE_URL` | `http://localhost:8080` | **设备能访问到的平台地址**，OTA 下载链接由它生成 |
| `HYPERIOT_ADMIN_USERNAME` | `admin` | 初始管理员用户名（仅首次启动、用户表为空时创建） |
| `HYPERIOT_ADMIN_PASSWORD` | 空 | 初始管理员密码（≥ 8 位）。为空则生成随机密码并打印一次日志 |
| `HYPERIOT_JWT_SECRET` | 空 | JWT 签名密钥（≥ 32 字节）。为空则每次启动随机生成（重启后所有登录失效）；**多节点必须配置成一致的值** |
| `HYPERIOT_MQTT_MODE` | `embedded` | `embedded` 内置 Broker；`external` 连接外部 Broker（EMQX） |
| `HYPERIOT_MQTT_PORT` | `1883` | 内置 Broker 端口 |
| `HYPERIOT_MQTT_BROKER_URL` / `_USERNAME` / `_PASSWORD` | `tcp://localhost:1883` / `hyperiot-platform` / 空 | external 模式下平台连接 Broker 用 |
| `HYPERIOT_HOOK_TOKEN` | 空 | EMQX 调用 `/api/mqtt/**` 时必须带的 `X-Hook-Token`；为空则钩子一律拒绝 |
| `HYPERIOT_BUS` | `memory` | `memory` 或 `kafka`（也可用 profile `kafka`） |
| `HYPERIOT_KAFKA_SERVERS` | `localhost:9092` | Kafka 地址 |
| `HYPERIOT_TCP_ENABLED` / `_PORT` | `false` / `9100` | TCP 私有协议网关 |
| `HYPERIOT_WEBHOOK_ALLOW_PRIVATE` | `false` | 规则 Webhook 是否允许访问内网地址 |
| `HYPERIOT_OTA_DIR` | `./data/firmware` | 固件文件目录（多节点需共享存储） |
| `HYPERIOT_DB_URL` / `_USER` / `_PASSWORD` | — | profile `postgres` 下的数据库连接 |

更多项（`application.yml` 里没列出的）用 Spring 的松散绑定：`hyperiot.telemetry.retention-days`（默认 30）、`hyperiot.command.reply-timeout`（30s）、`hyperiot.command.default-ttl`（5m）、`hyperiot.access.rate-limit-per-second`（每设备 50 条/秒，0 关闭）、`hyperiot.model.strict`（true）、`hyperiot.bus.partitions`（内存总线每订阅者的分区线程数，默认 4）等，完整列表见 `HyperIotProperties.java`。

## 2. 单机

```bash
java -jar hyperiot-server-0.1.0-SNAPSHOT.jar                    # H2 文件库，数据在 ./data
java -jar hyperiot-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres   # PostgreSQL
```

H2 适合试用和小规模；生产建议 PostgreSQL。表结构由 Flyway 自动迁移（`db/migration`），SQL 同时兼容两者。

## 3. PostgreSQL / TimescaleDB

```bash
export HYPERIOT_DB_URL=jdbc:postgresql://db:5432/hyperiot HYPERIOT_DB_USER=hyperiot HYPERIOT_DB_PASSWORD=...
java -jar hyperiot-server.jar --spring.profiles.active=postgres
```

> **未在真实 PostgreSQL 上跑过自动化测试**（开发环境没有 Docker），SQL 只使用标准语法并在 H2 的 PostgreSQL 模式下通过测试。上线前请在测试库跑一遍冒烟（建表、上报、聚合查询、OTA）。

数据量上来之后可以把 `telemetry` 改成 TimescaleDB 超表（时间列是毫秒整数）：

```sql
CREATE EXTENSION IF NOT EXISTS timescaledb;
SELECT create_hypertable('telemetry', 'ts', chunk_time_interval => 86400000, migrate_data => true);  -- 1 天一个分块
```

应用侧不用改：保留策略的 `DELETE` 在超表上也能工作，更高效的做法是改用 `drop_chunks`。

## 4. 集群拓扑（EMQX + Kafka）

`docker-compose.cluster.yml` 演示了架构图里的完整拓扑：

```
设备 ──▶ EMQX ──(共享订阅)──▶ HyperIoT 节点 1..N ◀──▶ Kafka ◀──▶ 各节点消费者组
              └─ HTTP 认证 / 授权钩子 ──▶ /api/mqtt/auth, /api/mqtt/acl
```

- EMQX 的**认证**交给平台：`POST /api/mqtt/auth`，请求体 `{clientid, username, password}`，平台校验 HMAC 签名；平台自己的连接账号（`hyperiot-platform`）被识别为超级用户。
- EMQX 的**授权**交给平台：`POST /api/mqtt/acl`，请求体 `{clientid, topic, action}`。
- 上线 / 下线：平台订阅 `$SYS/brokers/+/clients/+/connected|disconnected`（EMQX 默认发布）；也可以配置 EMQX 在连接事件时调用 `POST /api/mqtt/event`（`{"event":"client.connected","clientid":"…","peername":"ip:port"}`）。
- 平台用共享订阅 `$share/hyperiot/hyperiot/#` 接收上行，多个节点自动分摊流量。
- 平台连接 Broker 的账号必须能订阅 `hyperiot/#` 与 `$SYS/#`、发布 `hyperiot/#`。

> **实验性**：EMQX 的配置项（环境变量形式）、`$SYS` 事件 payload 字段是按 EMQX 5 文档编写的，没有在真实 EMQX 上验证；仓库里的测试用一个普通 MQTT Broker 代替 EMQX 验证了钩子逻辑和上下行链路。部署前请对照你使用的 EMQX 版本的文档核对，尤其是认证 / 授权 HTTP 的请求体与返回格式。

Kafka：每种消息一个 topic（`hyperiot.property`、`.event`、`.status`、`.command-reply`、`.ota-progress`、`.shadow-get`），启动时自动创建（6 分区、副本数 1——生产请预先创建并设置合适的副本数）。消费者组名前缀 `hyperiot-`。

多节点注意：`HYPERIOT_JWT_SECRET` 必须一致；固件目录需要共享存储（或每个节点都能下载到同一个文件）；影子缓存见 `architecture.md` 的限制。

## 5. TLS

平台本身不终结 TLS，请在前面放：

| 流量 | 方案 |
| --- | --- |
| 控制台 / REST / WebSocket / OTA 下载 | Nginx / Caddy / 云负载均衡终结 HTTPS，并转发 WebSocket（`Upgrade`），把 `HYPERIOT_PUBLIC_BASE_URL` 设成 `https://…` |
| MQTT | EMQX 原生支持 `8883` TLS 监听；内置 Broker 用 stunnel / HAProxy 终结 |
| TCP 私有协议 | 同上 |

设备的 MQTT 密码是静态签名，**不加密就等于明文密码**，公网设备必须走 TLS。

## 6. 监控

- 健康检查：`GET /actuator/health`（无需认证）。
- 指标：`GET /actuator/prometheus`（**需要 Bearer Token**；Prometheus 可通过 `authorization` 配置带上，或在反向代理上为内网抓取开放该路径）。关键指标：
  - `hyperiot_uplink_messages_total`：收到的设备消息数
  - `hyperiot_bus_dropped_total`：因消费者过慢被丢弃的消息数（应为 0，持续增长说明某个消费者跟不上）
  - JVM、Tomcat、HikariCP 的标准指标
- 日志：设备侧问题优先看控制台里“设备日志”（含被拒绝的上报及原因）。

## 7. 备份与升级

- 备份：数据库 + 固件目录。设备密钥在数据库里，备份要加密保存。
- 升级：Flyway 启动时自动执行新迁移；**只增不改历史迁移文件**。滚动升级前先确认新版本迁移对旧版本向后兼容。

## 8. 安全清单

- [ ] 设置 `HYPERIOT_ADMIN_PASSWORD`、`HYPERIOT_JWT_SECRET`，不要用文档或示例里的值
- [ ] 首次登录后修改管理员密码（控制台右上角“修改密码”）
- [ ] 前面有 TLS；`HYPERIOT_PUBLIC_BASE_URL` 是 `https://`
- [ ] 只暴露必要端口：443（控制台）、8883 或 1883（MQTT）；`/actuator/**` 不对公网开放
- [ ] 数据库不对公网开放，凭据不出现在镜像和代码里
- [ ] 规则 Webhook 目标可信，或保持 `allow-private-addresses=false`
- [ ] 外接 EMQX 时配置了 `HYPERIOT_HOOK_TOKEN`，并且 EMQX 到平台的钩子地址不对设备网络开放
- [ ] 定期备份并演练恢复
