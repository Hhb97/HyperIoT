# REST / WebSocket API

控制台与第三方系统通过本 API 管理平台。所有时间字段均为 **毫秒时间戳（number）**，字段名 **camelCase**。

## 约定

- 除 `/api/auth/login`、`/device-api/**`、`/api/mqtt/**`（EMQX 钩子）外，所有 `/api/**` 需要请求头 `Authorization: Bearer <token>`。
- 分页参数：`page`（从 1 开始，默认 1）、`size`（默认 20，最大 200）；分页响应：`{ "total": 123, "items": [ ... ] }`。
- 错误响应：HTTP 状态码 + `{ "code": "NOT_FOUND", "message": "设备不存在" }`。常见 `code`：`BAD_REQUEST`、`UNAUTHORIZED`、`FORBIDDEN`、`NOT_FOUND`、`CONFLICT`、`TOO_MANY_REQUESTS`、`INTERNAL_ERROR`。
- 无返回体的操作返回 `204 No Content`。

## 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/auth/login` | `{ username, password }` → `{ token, expiresIn(秒), username }`；连续失败会被限流（429） |
| GET | `/api/auth/me` | → `{ username, role }` |
| POST | `/api/auth/password` | `{ oldPassword, newPassword }`（新密码至少 8 位）→ 204 |

## 产品与物模型

Product：`{ id, productKey, name, description, nodeType("DEVICE"|"GATEWAY"), protocol("MQTT"|"HTTP"|"TCP"), deviceCount, createdAt, updatedAt }`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/products?keyword=&page=&size=` | 分页 |
| POST | `/api/products` | `{ name, description, nodeType, protocol }` → Product（`productKey` 由平台生成） |
| GET | `/api/products/{id}` | Product |
| PUT | `/api/products/{id}` | `{ name, description }` |
| DELETE | `/api/products/{id}` | 仍有设备时返回 409 |
| GET | `/api/products/{id}/thing-model` | → ThingModel |
| PUT | `/api/products/{id}/thing-model` | body 为 ThingModel，校验失败 400 |

ThingModel：

```json
{
  "properties": [
    { "identifier": "temperature", "name": "温度", "type": "double", "unit": "℃", "min": -40, "max": 125, "access": "r" },
    { "identifier": "mode", "name": "模式", "type": "enum", "values": ["auto", "manual"], "access": "rw" }
  ],
  "events": [
    { "identifier": "overheat", "name": "过热", "level": "WARN", "params": [ { "identifier": "temp", "type": "double" } ] }
  ],
  "services": [
    { "identifier": "reboot", "name": "重启", "input": [ { "identifier": "delaySeconds", "type": "int", "min": 0, "max": 60 } ], "output": [] }
  ]
}
```

`type` 取值：`int`、`long`、`float`、`double`、`bool`、`string`、`enum`；`access` 取值 `r`（只读）/ `rw`（可下发设置）。

## 设备

Device：`{ id, productKey, deviceName, nickname, status("INACTIVE"|"ONLINE"|"OFFLINE"), enabled, firmwareVersion, ip, lastOnlineAt, lastOfflineAt, lastSeenAt, tags({k:v}), createdAt }`。
`deviceSecret` 仅出现在 **创建 / 详情 / 重置密钥** 的响应里，列表不返回。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/devices?productKey=&status=&keyword=&page=&size=` | 分页，keyword 匹配 deviceName / nickname |
| POST | `/api/devices` | `{ productKey, deviceName, nickname, tags }` → Device（含 deviceSecret） |
| POST | `/api/devices/batch` | `{ productKey, prefix, count(≤500) }` → Device[]（含 deviceSecret） |
| GET | `/api/devices/{id}` | Device（含 deviceSecret） |
| PUT | `/api/devices/{id}` | `{ nickname, tags, enabled }`；禁用会断开连接 |
| DELETE | `/api/devices/{id}` | 同时删除影子、历史数据 |
| POST | `/api/devices/{id}/reset-secret` | → Device（新 deviceSecret），旧连接被踢下线 |
| GET | `/api/devices/{id}/shadow` | → `{ reported: { "<identifier>": { "value": 25.5, "ts": 1700000000000 } }, desired: { "<identifier>": value }, version }` |
| PUT | `/api/devices/{id}/desired` | `{ params: { "mode": "manual" } }` 写入期望值并（在线时）下发 `property/set`；只允许 `access=rw` 的属性 |
| GET | `/api/devices/{id}/telemetry` | `identifier`（必填）、`from`、`to`（默认最近 1 小时）、`agg=raw\|avg\|max\|min\|last`（默认 raw）、`interval`（毫秒，agg≠raw 时必填）、`limit`（默认 1000，最大 5000）→ `{ identifier, points: [ { ts, value } ] }` |
| GET | `/api/devices/{id}/events?page=&size=` | → DeviceEvent `{ id, identifier, level, params, ts }` 分页 |
| GET | `/api/devices/{id}/logs?page=&size=` | → DeviceLog `{ id, direction("UP"\|"DOWN"\|"SYS"), topic, payload, ts }` 分页，最新在前 |
| POST | `/api/devices/{id}/services/{identifier}/invoke?wait=false&timeoutMs=5000` | body `{ params, ttlSeconds }` → Command；`wait=true` 时阻塞等待设备应答（超时则返回当前状态） |
| GET | `/api/devices/{id}/commands?page=&size=` | 该设备的指令记录 |

## 指令 / 规则 / 告警

Command：`{ id, requestId, deviceId, productKey, deviceName, identifier, params, status("PENDING"|"SENT"|"SUCCESS"|"FAILED"|"TIMEOUT"), response, createdAt, sentAt, repliedAt, expireAt }`

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/commands?status=&page=&size=` | 全部指令 |
| GET | `/api/rules?page=&size=` | 规则列表 |
| POST | `/api/rules` | Rule（见下） |
| GET / PUT / DELETE | `/api/rules/{id}` | |
| POST | `/api/rules/{id}/enable`、`/disable` | → 204 |
| GET | `/api/alarms?status=&level=&page=&size=` | 告警列表 |
| POST | `/api/alarms/{id}/ack` | 确认告警 → 204 |

Rule：

```json
{
  "id": 1, "name": "高温告警", "description": "", "productKey": "pk123", "deviceName": null,
  "logic": "AND",
  "conditions": [ { "identifier": "temperature", "op": "GT", "value": 80 } ],
  "actions": [
    { "type": "COMMAND", "identifier": "fanOn", "params": {} },
    { "type": "WEBHOOK", "url": "https://example.com/hook" }
  ],
  "level": "CRITICAL", "enabled": true, "createdAt": 0
}
```

`op`：`GT`、`GE`、`LT`、`LE`、`EQ`、`NE`；`deviceName` 为 null 表示该产品下全部设备；`level`：`INFO`、`WARN`、`CRITICAL`。条件满足时创建告警（同一规则同一设备只保留一条活跃告警），条件不再满足时自动恢复。

Alarm：`{ id, ruleId, ruleName, deviceId, productKey, deviceName, level, status("ACTIVE"|"ACKED"|"RECOVERED"), message, triggerValue, triggeredAt, recoveredAt, ackedAt }`

## OTA

Firmware：`{ id, productKey, version, name, description, size, sha256, createdAt }`
OtaTask：`{ id, name, firmwareId, firmwareVersion, productKey, status("RUNNING"|"COMPLETED"|"CANCELED"), total, success, failed, createdAt, devices?: [ { deviceId, deviceName, status("PENDING"|"NOTIFIED"|"DOWNLOADING"|"SUCCESS"|"FAILED"|"CANCELED"), progress, message, updatedAt } ] }`（`devices` 仅详情接口返回）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/firmwares?productKey=&page=&size=` | |
| POST | `/api/firmwares` | `multipart/form-data`：`file`、`productKey`、`version`、`name`、`description` |
| DELETE | `/api/firmwares/{id}` | 被任务引用时 409 |
| GET | `/api/ota/tasks?page=&size=` | |
| POST | `/api/ota/tasks` | `{ name, firmwareId, deviceIds: [..] }`，`deviceIds` 为空表示该产品下全部设备 |
| GET | `/api/ota/tasks/{id}` | OtaTask（含 devices） |
| POST | `/api/ota/tasks/{id}/cancel` | → 204 |

## 看板

`GET /api/dashboard` →

```json
{
  "products": 3, "devices": 120, "online": 87, "activeAlarms": 2,
  "uplinkPerMinute": [ { "ts": 1700000000000, "count": 340 } ],
  "recentAlarms": [ { "...": "Alarm" } ]
}
```

`uplinkPerMinute` 为最近 30 分钟（按分钟，旧到新）的上行消息数。

## WebSocket 实时推送

连接 `ws(s)://<host>/ws`，**连接后第一条消息必须是** `{ "type": "auth", "token": "<JWT>" }`，鉴权成功返回 `{ "type": "ready" }`，随后平台推送：

```json
{ "type": "telemetry", "deviceId": 1, "productKey": "pk", "deviceName": "dev1", "ts": 1700000000000, "params": { "temperature": 25.5 } }
{ "type": "status",    "deviceId": 1, "productKey": "pk", "deviceName": "dev1", "status": "ONLINE", "ts": 1700000000000 }
{ "type": "alarm",     "alarm": { "...": "Alarm" } }
{ "type": "command",   "command": { "...": "Command" } }
```

## 设备侧接口

设备协议（MQTT 主题、HTTP 上报、TCP 私有协议、签名算法）见 [protocol.md](protocol.md)。
