# MQTT、双连接与本机连接配置

## 版本与交付选择

| 面板 | schemaVersion | transport | 平台 |
| --- | --- | --- | --- |
| BLE | 3 | 省略（默认 BLE） | Android / iOS |
| MQTT | 4 | MQTT | Android / iOS / Web |
| BLE + MQTT 分通道 | 6 | DUAL | Android / iOS |
| BLE 设置表单，可同时包含普通控制 | 7 | BLE / DUAL | Android / iOS |
| BLE + MQTT 旧混合路由 | 5 | DUAL | Android / iOS |

单通道设备仅导入对应类型；手机端可先将设备升级为双连接。双连接设备导入 v3/v4 会提升为 v5，沿用原通道，这是整份面板替换，不会合并两个文件。专为 Web 交付时仍推荐 MQTT v4。跨端交付的 DUAL 文件可在 Web 导入，但客户端会提示部分功能可能不可用，并提取 MQTT 绑定与可用组件，去掉 BLE 设置表单；后续导出为 MQTT v4。没有 MQTT 绑定的文件不能在 Web 使用。不要仅修改版本号而遗漏路由。

## MQTT v4 绑定

共用 contract.md 中的 capabilities、widgets、payloadFormats。每项 binding 使用 capabilityId 和可选 publish/subscribe，至少一个；不能包含 read/write/notify/actionValue，也不能设置 controlTransport/stateTransport。

```json
{
  "capabilityId": "power",
  "publish": {
    "topic": "devices/lamp-01/set",
    "qos": 1,
    "payload": {"formatId": "powerCommand", "fieldId": "power"}
  },
  "subscribe": {
    "topic": "devices/lamp-01/state",
    "qos": 1,
    "payload": {"formatId": "state", "fieldId": "power"}
  }
}
```

这是绑定片段，完整文件见 assets/mqtt-all-widgets.thingstalk.json。

- operation 字段仅 topic、payload、qos（默认 0）、retain（默认 false）。支持 QoS 0/1/2；同一状态 topic 必须使用相同订阅 QoS。
- 面板 topic 必须精确、非空，不含 NUL、`+`、`#`、代理码点，不以 `$share/` 开头，UTF-8 长度不超过 65535 字节。大小写及路径须与固件一致。工作台的通配订阅能力不代表面板也支持。
- publish 固定非 retained；subscribe 不需要配置 retain。任何操作写 retain:true 均拒绝。Broker 已保留的状态消息可以接收。
- BOOLEAN/NUMBER/TEXT 选择有效 fieldId；TEXT 仅 publish，绑定 UTF8 字段，不支持 subscribe；ACTION 只能 publish，payload 仅 formatId，不能 subscribe。ACTION 全部字段必须有 defaultValue，属性发布时其他字段必须有 defaultValue。不会自动合并最新状态到命令。
- BINARY/JSON 与 BLE 共用编解码约束，完整报文 1–512 字节。JSON 是严格平面数值/布尔/UTF8 字符串对象，不能多键、漏键或嵌套。BINARY 的 UTF8 格式只能有一个文本字段，可设置帧头尾。
- 没有通用 read 请求或自动请求/应答匹配。设备主动 publish 状态，App subscribe 更新显示；发送成功不乐观修改状态。QoS 确认不等于设备业务成功。固件应说明重复消息的幂等处理，特别是有副作用的动作。

示例协议（须替换为实际设备 topic）：向 `thingstalk/demo/state` 发布 `{"temperature":25.0,"power":true,"brightness":80}`；关闭电源命令为 `thingstalk/demo/set/power` + `{"power":false}`，亮度 50 为 `thingstalk/demo/set/brightness` + `{"brightness":50}`，定位为 `thingstalk/demo/set/locate` + `{"locate":true}`。数值边界、缺失/额外字段及错误类型需纳入验收。

同一连接内相同 topic/QoS 复用订阅；修改面板后旧 topic 可保留到连接结束，但旧组件不再消费。重新挂载组件不会强制重新索取 retained 状态；可等待设备新上报或手动重连。坏报文报告解析错误，不终止后续接收。

## 双连接 v6：按通道定义并运行

新硬件希望用 BLE / MQTT 标签选择实际通信通道时，交付 v6；同时包含设置表单时整份使用 v7，沿用下述分通道行为。需要每个能力独立选择控制/状态、跨通道混合时才使用旧 v5，并明确说明差别。

```json
{
  "schemaVersion": 6,
  "transport": "DUAL",
  "defaultChannel": "BLE",
  "title": "双通道设备",
  "capabilities": [],
  "widgets": [],
  "payloadFormats": [],
  "channels": {
    "ble": { "bindings": [] },
    "mqtt": { "bindings": [] }
  }
}
```

组件/能力/格式共用一次；BLE 与 MQTT 绑定按 capabilityId 引用它们。BLE 分区的绑定仅有 capabilityId、read/write/notify、可选旧 actionValue；MQTT 分区仅有 capabilityId、publish/subscribe。两个 channels 分区都显式提供，某侧可为空；每侧 capabilityId 不重复，每条绑定至少一个操作。各操作字段和编解码约束沿用上文。v6/v7 不允许顶层 bindings 或 controlTransport/stateTransport，也不允许把 publish 写在 BLE 分区里。

defaultChannel 必须为 BLE 或 MQTT。展示页第一次打开/保存后选中它；点击另一 Tab 会切换组件实际通信通道，保持连接但取消旧订阅和排队操作、清除旧值，开始观察新通道。没有自动连接、回退或重发；已经发出的命令无法撤销。某侧缺少能力/控制/状态操作时显示不可用，不借用另一侧的数据或命令。

Tab 选择是工作区运行状态，不写回默认配置；编辑器可修改 defaultChannel。导入仍替换整份草稿，不合并两份 JSON。旧 v5 保留原行为；可在编辑器明确转换为 v6，再检查并保存。v6/v7 DUAL 的展示页 Tab 选择实际通道，绑定编辑页的 Tab 仍只选择正在编辑哪一侧。

完整示例 assets/dual-channels.thingstalk.json：BLE 电源 00/01，MQTT 为 {"power":false}/{"power":true}，默认 BLE。验收必须分别选择两侧进行控制、状态接收，测试单边离线、未配置操作、切换时未完成命令与迟到状态；不能仅检查 JSON 能导入。

## 旧双连接 v5 混合路由（兼容）

旧 v5：手机端设备展示页顶部常驻 BLE / MQTT 连接 Tab，单通道设备另一侧显示启用/升级双连接入口；升级会断开当前连接，保留现有组件与绑定。双连接的组件操作编辑也使用 BLE / MQTT Tab，组件仍共用一套面板。连接 Tab 显示各自状态；绑定 Tab 显示是否已配置，控制通道与状态来源保持在上方。切换标签只改变可见设置，不改路由、不关闭会话、不清空输入。导入入口不变且仍替换整份草稿；MQTT-only 文件不会自动生成 BLE 特征映射，可在 BLE 标签补充。文件格式不因 Tab 改版而升级。

binding 可同时含 BLE read/write/notify 和 MQTT publish/subscribe，各操作仍按各自规范校验，未选中的通道也必须有效。

- 有 write/publish 时必须填写 controlTransport:"BLE" 或 "MQTT"，并存在对应 write/publish。
- 有 read/notify/subscribe 时必须填写 stateTransport:"BLE" 或 "MQTT"，并存在对应状态操作。
- 没有该类操作就省略对应路由；路由不能是 DUAL。ACTION 只能控制，不能绑定任何状态操作。
- 每项能力只选一个控制通道和一个状态来源，两者可以不同。交付说明必须列出所选路由；未指定时标明拟定默认与影响。若全部 controlTransport/stateTransport 都写 MQTT，则即使 BLE 绑定齐全，运行时仍全部走 MQTT，连接设置 Tab 不会覆盖这些路由。BLE 状态可 read + notify，MQTT 状态仅 subscribe。
- BLE 与 MQTT 可以用不同格式，但必须对应同一能力类型、单位与实际含义；不能把不兼容数值范围隐藏在通道切换里。

完整示例 assets/dual-power.thingstalk.json：BLE 电源为单字节 `00`/`01`，MQTT 为 `{"power":false}`/`{"power":true}`。默认控制选 BLE、状态选 MQTT，因此点击开关只执行 BLE write，状态只消费 MQTT subscribe，已定义的 BLE read/notify 和 MQTT publish 不会自动执行。若要反向路由，应显式修改两个路由值。

连接独立管理，不自动跨通道回退、不同时向两边发命令、不自动重连或补发。控制通道断开会取消其排队/执行操作并禁用控件；状态通道断开清除相应显示，另一通道仍可运行。已发出的命令无法靠取消撤销。App 前台切换设备、消息或我的页面保持会话与现有订阅，用户可在连接管理主动停止；iOS 进入后台保留 BLE 会话/订阅，设备 MQTT 仍断开，不承诺杀进程后的恢复或系统通知。

验收包含两种路由、单边断开与重连、状态延迟、重复消息及错误报文；不可将模拟测试当作双协议真机执行确认。

## 本机连接配置与统一 WS/WSS

三端 MQTT 统一采用 **MQTT 5 over WS/WSS**。当前 App 不提供原生 TCP/TLS（mqtt://、mqtts://）或 MQTT 3.1.1。Broker 必须开放 WebSocket 端口和路径；HTTPS 页面使用 WSS，公网部署优先 WSS。固件自身可使用 Broker 的 TCP 入口，App 使用同一 Broker 的 WS/WSS 入口，只要 topic/权限一致即可通信；无需强制硬件也走 WebSocket。

硬件交付时另提供部署说明：完整 WS/WSS 地址（含端口/路径）、所需认证方式、Client ID 要求及发布/订阅权限。不要在示例中放真实密码。

用户在“管理连接配置”二级页新增/编辑名称、服务器地址、可选用户名/密码、自动或固定 Client ID；顶栏“保存并选用”，在工作台/设备一级页直接选择并连接。配置库共用，工作台及各设备分别记住所选配置。默认每次连接生成独立 Client ID，固定 ID 的并发连接可能互相踢下线。修改/删除/切换配置不替换现有连接，下次手动连接生效。

Android 用 Keystore 密钥加密保存；iOS 用 Keychain（AfterFirstUnlockThisDeviceOnly，卸载后可能保留）；Web 保存普通配置和密码需求标记，密码只留本次会话，刷新后需补充。当前连接使用干净会话；没有自定义 CA/客户端证书、URL 查询参数、遗嘱、会话过期的配置入口，也没有新增后台 MQTT 常驻监测或推送。

**面板文件与本机连接配置分开。** `.thingstalk.json` 不加入服务器地址、用户名、密码、Client ID、连接配置 ID、BLE 平台 ID/MAC。它只描述组件、能力、格式、UUID/topic 和路由。连接配置库没有配套的公开导入/导出协议，不要伪造 profileId/broker/credentials 等字段；用户应在 App 中自行配置并关联。

消息页接入真实连接/错误/设置保存结果与有界通信记录。原始 MQTT 消息和 BLE notify 不自动变成告警或系统通知；消息已读/确认只修改本机记录，不发送硬件命令。普通面板协议与 v7 设置协议保持不变。
