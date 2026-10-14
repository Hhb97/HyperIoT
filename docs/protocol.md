# 设备接入协议

HyperIoT 支持三种接入方式，**三种方式共用同一套消息格式与物模型校验**：

| 方式 | 适用场景 | 方向 | 认证 |
| --- | --- | --- | --- |
| MQTT 3.1.1 | 绝大多数联网设备（推荐） | 上行 + 下行 | 签名密码（HMAC-SHA256） |
| HTTP | 无法保持长连接的设备、脚本、网关回传 | 仅上行 | 请求体签名（`X-Signature`） |
| TCP（私有协议示例） | 自研协议设备、遗留系统 | 上行 + 下行 | 认证帧签名 |

## 1. 设备凭证

创建设备后平台生成 `productKey`（产品标识）、`deviceName`（设备名）、`deviceSecret`（设备密钥）。**密钥不会在网络上传输**，设备用它计算签名。

```
MQTT clientId = productKey + "." + deviceName
MQTT username = deviceName + "&" + productKey
MQTT password = hex( HMAC-SHA256( key = deviceSecret,
                     data = "clientId" + clientId + "deviceName" + deviceName + "productKey" + productKey ) )
```

> 签名是静态的（不含时间戳），所以**公网环境必须使用 TLS**（在 EMQX 或负载均衡层终结）。重置设备密钥会使旧签名立即失效并踢下线。

## 2. MQTT 主题

每个设备只能访问自己的命名空间 `hyperiot/{productKey}/{deviceName}/…`，越权的发布/订阅会被拒绝。

### 设备 → 平台（上行）

| 主题后缀 | 说明 | 载荷 |
| --- | --- | --- |
| `thing/property/post` | 属性上报 | `{"id":"1","ts":1700000000000,"params":{"temperature":25.5}}` |
| `thing/event/{identifier}/post` | 事件上报 | `{"id":"2","ts":…,"params":{"temp":99.5}}` |
| `thing/service/{identifier}/reply` | 服务调用应答 | `{"id":"<平台下发的 id>","code":200,"data":{},"msg":""}` |
| `shadow/get` | 请求完整影子 | `{}` |
| `ota/inform` | 上报当前固件版本 | `{"version":"1.0.0"}` |
| `ota/progress` | 上报升级进度 | `{"version":"2.0.0","step":50,"desc":"downloading"}` |

约定：

- `id` 可选，是设备自己的消息 ID；**携带 `id` 时平台会应答**（见下表 `post_reply`），不带则不应答（省流量）。
- `ts` 可选，毫秒时间戳；缺省、早于 7 天前或晚于未来 5 分钟时，平台使用接收时间。
- 属性值会按物模型校验：类型、范围、枚举；**未在物模型中声明的属性默认被拒绝**（`hyperiot.model.strict=false` 可放开）。同一条消息里合法的属性照常入库，非法的被拒绝并记入设备日志。
- `ota/progress` 的 `step`：`0~99` 进度，`100` 成功，`-1` 失败（`desc` 写明原因）。

### 平台 → 设备（下行，设备订阅 `hyperiot/{productKey}/{deviceName}/#` 即可）

| 主题后缀 | 说明 | 载荷 |
| --- | --- | --- |
| `thing/service/{identifier}/invoke` | 调用服务 | `{"id":"<requestId>","params":{"delaySeconds":5}}`，设备执行后向 `…/reply` 回复同一个 `id` |
| `thing/property/set` | 设置属性（期望值） | `{"id":"…","version":3,"params":{"mode":"manual"}}`，设备应用后**上报新值**，平台据此清除期望值 |
| `thing/property/post_reply` | 属性上报结果 | `{"id":"1","code":200,"msg":"ok"}`；`code=400` 表示部分/全部被拒绝，`msg` 为原因 |
| `thing/event/{identifier}/post_reply` | 事件上报结果 | 同上 |
| `shadow/get_reply` | 影子内容 | `{"state":{"reported":{…},"desired":{…}},"version":3,"ts":…}` |
| `ota/upgrade` | 固件升级指令 | `{"id":"<taskId>","version":"2.0.0","url":"https://…","size":4096,"sha256":"…"}` |

QoS：平台下行使用 QoS 1，设备上行建议 QoS 1。设备离线期间的下行指令**不依赖 MQTT 离线消息**：平台自己保存指令（带 TTL），设备上线后补发。

### 设备影子与期望值

```
控制台/API  ──PUT /api/devices/{id}/desired──▶  平台保存 desired
                                                 │ 设备在线：立即下发 thing/property/set
                                                 │ 设备离线：上线时下发
设备应用后 ──thing/property/post(新值)──▶  平台比较 reported 与 desired，一致则清除该项
```

只有物模型里 `access: "rw"` 的属性才能设置。

## 3. HTTP 上行

```
POST /device-api/{productKey}/{deviceName}/property
POST /device-api/{productKey}/{deviceName}/event/{identifier}
Content-Type: application/json
X-Signature: hex( HMAC-SHA256( key = deviceSecret, data = <原样的请求体字节> ) )

{"params":{"temperature":22.5}}
```

- 成功返回 `200 {"code":200}`，签名错误或设备不存在统一返回 `401`（不泄露设备是否存在）。
- HTTP 设备没有连接：**首次上报视为上线，5 分钟无上报视为离线**。
- 请求体上限 64 KB。

示例（bash + openssl）：

```bash
BODY='{"params":{"temperature":22.5}}'
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$DEVICE_SECRET" -hex | sed 's/^.* //')
curl -X POST "http://localhost:8080/device-api/$PK/$DN/property" \
     -H 'Content-Type: application/json' -H "X-Signature: $SIG" -d "$BODY"
```

## 4. TCP 私有协议（示例网关）

默认关闭，`HYPERIOT_TCP_ENABLED=true` 启用（端口 `HYPERIOT_TCP_PORT`，默认 9100）。**按行分隔的 JSON**，一行一帧，单帧最大 64 KB。

设备 → 平台：

```json
{"type":"auth","productKey":"pk","deviceName":"dn","sign":"hex(HMAC-SHA256(secret, 'productKey'+pk+'deviceName'+dn))"}
{"type":"property","id":"1","params":{"temperature":25.5}}
{"type":"event","identifier":"overheat","params":{"temp":99.5}}
{"type":"reply","identifier":"reboot","id":"<requestId>","code":200}
{"type":"shadow_get"}
{"type":"ota_inform","version":"1.0.0"}
{"type":"ota_progress","version":"2.0.0","step":50}
{"type":"ping"}
```

平台 → 设备：

```json
{"type":"auth_reply","code":200}
{"type":"pong"}
{"type":"downlink","topic":"hyperiot/pk/dn/thing/service/reboot/invoke","payload":{"id":"…","params":{}}}
```

- 连接后 10 秒内必须完成认证，否则断开；认证失败返回 `{"type":"auth_reply","code":401}` 并断开。
- 空闲超过 `hyperiot.tcp.idle-timeout`（默认 3 分钟）断开，设备应定期发送 `ping`。
- `downlink` 帧里的 `topic` 与 MQTT 主题一致，设备按 `topic` 后缀区分消息类型。
- 同一设备重复登录时，旧连接被踢掉。
- 这是一个“如何接入私有协议”的参考实现，**没有加密**，生产环境请在前面放 TLS 终结（stunnel / HAProxy / 云负载均衡）。

## 5. OTA 升级

1. 控制台上传固件（存储为内容寻址文件，记录 SHA-256），创建升级任务，选择目标设备（缺省为该产品下所有未升级设备）。
2. 在线设备立即收到 `ota/upgrade`；离线设备上线后收到。
3. `url` 是带签名的下载地址（签名 = `HMAC-SHA256(deviceSecret, "ota" + firmwareId + deviceName)`），无需登录，支持 `Range` 断点续传。设备下载后**必须校验 `sha256`**。
4. 设备通过 `ota/progress` 上报进度，`step=100` 表示成功；也可以重启后通过 `ota/inform` 上报新版本，平台以此确认。
5. 一个设备同一时间只跟随一个升级任务（新任务取代旧任务）；超过 30 分钟没有进展的设备被标记为失败，任务随之结束。
6. `hyperiot.public-base-url` 必须配置为设备能访问到的平台地址，否则下发的下载地址设备无法使用。
