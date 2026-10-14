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
| `HYPERIOT_TS_TYPE` | `jdbc` | 时序存储：`jdbc`（关系库里的 `telemetry` 表）或 `influx`（InfluxDB 1.x，也可用 profile `influx`） |
| `HYPERIOT_INFLUX_URL` / `_DB` | `http://localhost:8086` / `hyperiot` | InfluxDB 地址与库名 |
| `HYPERIOT_INFLUX_USER` / `_PASSWORD` | 空 | InfluxDB 账号（开启了认证时必填；自动建库需要管理员权限） |

更多项（`application.yml` 里没列出的）用 Spring 的松散绑定：`hyperiot.telemetry.retention-days`（默认 30）、`hyperiot.command.reply-timeout`（30s）、`hyperiot.command.default-ttl`（5m）、`hyperiot.access.rate-limit-per-second`（每设备 50 条/秒，0 关闭）、`hyperiot.model.strict`（true）、`hyperiot.bus.partitions`（内存总线每订阅者的分区线程数，默认 4）等，完整列表见 `HyperIotProperties.java`。

## 生产栈：PostgreSQL + Kafka + EMQX + InfluxDB 1.8

一个 profile 打开全部：`--spring.profiles.active=prod`（等价于 `postgres,kafka,emqx,influx`）。

```bash
export HYPERIOT_DB_URL=jdbc:postgresql://<pg>:5432/hyperiot  HYPERIOT_DB_USER=...  HYPERIOT_DB_PASSWORD=...
export HYPERIOT_KAFKA_SERVERS=<kafka>:9092
export HYPERIOT_MQTT_BROKER_URL=tcp://<emqx>:1883  HYPERIOT_MQTT_USERNAME=hyperiot-platform  HYPERIOT_MQTT_PASSWORD=...
export HYPERIOT_HOOK_TOKEN=...                       # EMQX 调用平台钩子时带的令牌
export HYPERIOT_INFLUX_URL=http://<influx>:8086  HYPERIOT_INFLUX_DB=hyperiot  HYPERIOT_INFLUX_USER=...  HYPERIOT_INFLUX_PASSWORD=...
export HYPERIOT_ADMIN_PASSWORD=...  HYPERIOT_JWT_SECRET=...  HYPERIOT_PUBLIC_BASE_URL=http://<平台对设备可达的地址>:8080
java -jar hyperiot-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=prod
```

四个外部组件各自要准备的事：

| 组件 | 你需要做的 | 平台自动做的 |
| --- | --- | --- |
| **PostgreSQL** | 建一个空库：`CREATE DATABASE hyperiot;`，给账号该库的建表权限 | 启动时 Flyway 建表 |
| **Kafka** | 确保 `advertised.listeners` 是平台机器能访问的地址；账号需要建 topic 权限（或自己提前建 6 个 topic，见 §4） | 自动创建 6 个 topic、使用 `hyperiot-*` 消费者组 |
| **EMQX 5.x** | ① 配 HTTP 认证 → `POST <平台>/api/mqtt/auth`；② 配 HTTP 授权 → `POST <平台>/api/mqtt/acl`；两处都带请求头 `X-Hook-Token: <HYPERIOT_HOOK_TOKEN>`；③ 允许平台账号 `hyperiot-platform`（平台的认证钩子会把它识别为超级用户，所以只需要它的密码与 `HYPERIOT_MQTT_PASSWORD` 一致）；详见 §4 | 以该账号连接 EMQX，共享订阅 `hyperiot/#`，订阅 `$SYS` 的上下线事件 |
| **InfluxDB 1.8** | 开了认证的话，给平台一个管理员账号（要自动建库时）；或自己建好库和保留策略并把 `HYPERIOT_INFLUX_*` 设为只写读账号、关闭自动建库（`hyperiot.timeseries.influx.auto-create=false`） | 自动 `CREATE DATABASE`、创建/更新默认保留策略（`retention-days`，默认 30 天） |

> EMQX 4.x 的认证 / 授权插件配置方式与 5.x 完全不同，本平台的钩子请求体与响应格式按 5.x 编写，**不兼容 4.x**。

### 在已有的 EMQX 5.7.2 上配置（Dashboard 操作）

下面的钩子地址 `<平台地址>` 必须是**从 EMQX 所在机器能访问到的平台地址**（例如平台跑在开发机上、EMQX 在虚拟机里，就填开发机的局域网 IP 和平台端口，不能填 `localhost`）。

**① 认证**：Dashboard → Access Control → Authentication → Create → *Password-Based* → *HTTP Server*

| 项 | 值 |
| --- | --- |
| Method | `POST` |
| URL | `http://<平台地址>/api/mqtt/auth` |
| Headers | `content-type: application/json`、`x-hook-token: <HYPERIOT_HOOK_TOKEN>` |
| Body（键值对） | `clientid` = `${clientid}`，`username` = `${username}`，`password` = `${password}` |

**② 授权**：Dashboard → Access Control → Authorization → Create → *HTTP Server*

| 项 | 值 |
| --- | --- |
| Method | `POST` |
| URL | `http://<平台地址>/api/mqtt/acl` |
| Headers | 同上（`content-type`、`x-hook-token`） |
| Body（键值对） | `clientid` = `${clientid}`，`topic` = `${topic}`，`action` = `${action}` |

并在授权设置里把 *No Match* 设为 **Deny**；如果之前启用过 *Built-in Database* 之类的授权源，把它们禁用或排到 HTTP 之后，否则可能先于平台放行。认证同理：只保留这一个认证器，或把它排在最前面。

**③ 平台账号**：不用在 EMQX 里单独建用户。平台以 `HYPERIOT_MQTT_USERNAME`（默认 `hyperiot-platform`）和 `HYPERIOT_MQTT_PASSWORD` 连接，EMQX 会通过上面的认证钩子问平台，平台识别出这个账号并返回超级用户（超级用户不受授权限制，可以订阅 `hyperiot/#` 和 `$SYS`）。所以**平台必须先于设备启动并且 EMQX 能访问到钩子地址**；平台在 EMQX 不可用时每 5 秒重试一次连接。

**④ 上线 / 下线事件**：平台通过订阅 `$SYS/brokers/+/clients/+/connected|disconnected` 得到设备在线状态。EMQX 5.7 里 Dashboard → Management → Settings → System Topics 中“客户端连接 / 断开事件”需要是开启状态（默认开启）。

**⑤ 验证**：
1. 平台日志出现 `Connected to external MQTT broker …` 和 `Subscribed to $share/hyperiot/hyperiot/#`。
2. 创建产品和设备后，用 MQTTX 之类的客户端按 `docs/protocol.md` 的凭据规则连接：签名正确能连上，错误会被拒绝。
3. 设备连上后控制台里应显示“在线”，上报属性能在设备详情里看到。

> 上面的菜单名称和字段名是按 EMQX 5.x 文档写的，没有在 5.7.2 的界面上逐项对照；如果你的界面和描述有出入，以界面为准，保持“方法 / URL / 请求头 / 请求体字段”这几项一致即可。

InfluxDB 中的数据模型：measurement `telemetry`，tag `device_id`、`identifier`，field `num`（浮点，布尔存 1/0）和 `str`（字符串），时间戳毫秒。设备删除时平台会 `DELETE` 该设备的序列；过期由保留策略负责。`last` 聚合在 InfluxDB 下返回的是时间桶的起点，而 JDBC 下返回的是该桶最后一个样本的时间。

> **InfluxDB 部分没有在真实的 InfluxDB 上跑过**：仓库里的测试用一个遵循官方 HTTP API 文档的假服务验证了请求格式（行协议、InfluxQL）、结果解析、建库与保留策略语句，以及整个平台在 influx 模式下的写入 / 查询 / 删除流程。首次接真实 InfluxDB 请先冒烟：上报几条数据 → 控制台看曲线（原始值与聚合）→ 删除设备。

## 2. 单机

```bash
java -jar hyperiot-server-0.1.0-SNAPSHOT.jar                    # H2 文件库，数据在 ./data
java -jar hyperiot-server-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres   # PostgreSQL
```

H2 适合试用和小规模；生产建议 PostgreSQL。表结构由 Flyway 自动迁移（`db/migration`），SQL 同时兼容两者。

## 3. PostgreSQL

```bash
export HYPERIOT_DB_URL=jdbc:postgresql://db:5432/hyperiot HYPERIOT_DB_USER=hyperiot HYPERIOT_DB_PASSWORD=...
java -jar hyperiot-server.jar --spring.profiles.active=postgres
```

> **未在真实 PostgreSQL 上跑过自动化测试**（开发环境没有 Docker），SQL 只使用标准语法并在 H2 的 PostgreSQL 模式下通过测试。上线前请在测试库跑一遍冒烟（建表、上报、聚合查询、OTA）。

数据量上来之后，把时序数据放到 InfluxDB 1.8：加 profile `influx`（或 `HYPERIOT_TS_TYPE=influx`），见上面的“生产栈”。关系库里只留产品、设备、规则、告警、指令等业务数据。

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
