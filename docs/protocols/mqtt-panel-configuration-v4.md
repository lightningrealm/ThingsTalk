# MQTT 自定义设备与面板（schemaVersion 4）

MQTT 与 BLE 使用同一套自定义设备入口、五种组件（状态卡、开关、滑块、按钮、数值输入）、命名报文格式和面板编辑器。Android、iOS 可创建两种设备；Web 只创建 MQTT 设备。原 MQTT 工作台保留，作为独立连接调试工具。

## 使用

1. 在“设备”创建 MQTT 设备。
2. 在“管理连接配置”二级页保存 Broker 的 `ws://` 或 `wss://` 地址（含 WebSocket 路径）、Client ID 策略以及可选账号密码，再在设备页选用。HTTPS 页面使用 WSS 地址。每台设备使用独立连接，默认每次生成独立 Client ID。
3. 在开发者设置中定义数据格式、添加组件、绑定发布及订阅 topic；也可以直接导入 [完整五组件示例](../examples/mqtt-temperature-light.thingstalk.json)。
4. 保存配置并连接。设备向状态 topic 上报后，面板解析报文并更新组件；操作交互组件会编码并发布命令。

连接配置在本机复用；Android 使用 Keystore 加密存储，iOS 使用 Keychain；Web 保存普通配置，密码仅保留在本次会话中。详见 [连接配置说明](../guides/mqtt-connections.md)。面板导出不包含服务器地址、凭证、Client ID。Web 使用当前站点的 localStorage 保存设备及草稿，支持 JSON 文件导入和浏览器下载。

## 配置模型

顶层新增 `transport: "MQTT"`，使用 `schemaVersion: 4`。`capabilities`、`widgets`、`payloadFormats` 沿用 v3。每项能力的 `bindings` 使用 `publish` / `subscribe`，不混用 BLE 的 `read` / `write` / `notify`。

```json
{
  "capabilityId": "power",
  "subscribe": {
    "topic": "thingstalk/demo/state",
    "qos": 1,
    "payload": { "formatId": "state", "fieldId": "power" }
  },
  "publish": {
    "topic": "thingstalk/demo/set/power",
    "qos": 1,
    "payload": { "formatId": "powerCommand", "fieldId": "power" }
  }
}
```

以上仅为完整文件中的一项绑定。属性选择报文字段；ACTION 按钮选择完整报文，全部字段必须有默认值。属性发布时，未绑定的其他字段同样必须有默认值；不会将之前收到的状态自动合并进命令。

- 发布和订阅支持 QoS 0 / 1 / 2；同一状态 topic 的绑定必须使用相同 QoS。
- 使用精确 topic，不支持 `+`、`#` 或 `$share/` 共享订阅，避免多个设备状态混入同一组件。
- 命令固定使用 `retain = false`。若文件显式配置 `retain: true`，校验会拒绝。服务器保留的状态消息可以正常接收。
- 编解码仍使用 v3 的定长二进制或平面 JSON，完整报文上限 512 字节。JSON 必须与声明字段集合一致；不支持任意嵌套 JSON、额外键、字符串字段或脚本。
- MQTT 没有通用的“读取特征”操作。当前通过状态订阅获取数据，不将任意发布伪装成同步读取；请求/响应相关性协议尚未实现。

## 示例联调

让固件或独立 MQTT 客户端向 `thingstalk/demo/state` 发布：

```json
{"temperature":25.0,"power":true,"brightness":80}
```

三个状态字段会更新对应组件。示例命令为：

| 操作 | Topic | Payload |
| --- | --- | --- |
| 关闭开关 | `thingstalk/demo/set/power` | `{"power":false}` |
| 亮度设为 50 | `thingstalk/demo/set/brightness` | `{"brightness":50}` |
| 定位按钮 | `thingstalk/demo/set/locate` | `{"locate":true}` |

发布完成表示提交到 Broker；QoS 确认不表示设备执行业务成功。面板不乐观修改设备状态，需要设备再次发布真实状态。QoS 1 的重复投递需由硬件协议处理；有副作用的动作尤其需要设备侧明确幂等策略，当前不自动重试控制命令。

## 消息流与生命周期

`MqttPanelOperations` 实现领域层 `PanelOperations`。先收集实时消息再发送 SUBSCRIBE，避免漏掉早于 SUBACK 到达的 retained 状态。同一连接内相同 topic/QoS 复用订阅；每个组件按自己的字段解码。坏报文作为 `InvalidPayload` 上报，不终止后续接收。

订阅属于设备连接，离开单个组件不会向 Broker 取消其他组件的订阅。修改配置后旧 topic 可保留到本次连接结束，但已取消的组件收集器不再消费它。重新订阅本地组件不会强制向 Broker 重发 SUBSCRIBE，因此不会主动重新索取 retained 状态，等待后续上报；需要重新获取服务器保留状态时断开再连接。

前台切页保持设备 MQTT 会话；应用进入后台或手动断开会关闭连接并清除实时值。断线后需手动重连，新连接重新订阅；不自动重发命令、不承诺离线补齐。当前没有后台监测服务或系统推送。
