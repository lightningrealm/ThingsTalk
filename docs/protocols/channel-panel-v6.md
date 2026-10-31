# 分通道面板 v6

2026-10-31。v5 将 BLE/MQTT 操作放在同一 binding，再为每项能力指定 controlTransport/stateTransport；连接标签不改变这些路由。因此一个双连接文件若全部选择 MQTT，BLE 连接正常也不会让组件改走 BLE。

v6 在文件中直接分区：共用 capabilities、widgets、payloadFormats，操作分别位于 channels.ble.bindings / channels.mqtt.bindings。展示页 Tab 选择实际通信通道。混合路由仍使用 v5；不会静默升级旧文件。

```json
{
  "schemaVersion": 6,
  "transport": "DUAL",
  "defaultChannel": "BLE",
  "title": "设备面板",
  "capabilities": [],
  "widgets": [],
  "payloadFormats": [],
  "channels": {
    "ble": { "bindings": [] },
    "mqtt": { "bindings": [] }
  }
}
```

- BLE binding：capabilityId、read/write/notify、可选旧格式 actionValue。
- MQTT binding：capabilityId、publish/subscribe。
- 不再有顶层 bindings 或 controlTransport/stateTransport；错放到另一通道的字段会拒绝。defaultChannel 必须为 BLE 或 MQTT，只决定初次打开/保存后的通道，不是故障回退配置。
- 两个通道可用不同报文格式，但引用相同能力。每个通道内 capabilityId 不重复，两边可以引用同一个 ID。允许某侧缺少某项能力；该侧显示未配置并禁用相应操作，不回退到另一侧。
- 切换标签保持连接，取消旧命令/订阅、清除旧显示并观察新通道；不自动连接或重发。已经发送到硬件的命令无法撤销。
- 切换只影响当前设备工作区的运行状态，不改文件或默认通道；默认通道在编辑器设置。连接重建不隐式覆盖当前选择。
- Android/iOS 支持 v6，Web 仍只支持 MQTT v4。WS/WSS 和凭据边界保持不变。

旧 v5 导入仍遵守显式混合路由。编辑器可点击“转换为分通道配置 v6”，检查默认通道和两侧绑定后保存。这是明确的行为变更，不仅是格式美化。导入继续替换整份草稿，不合并文件。

内部沿用已验证的操作模型，文件 DTO 负责将两个分区映射为内部绑定；运行时只投影选中的通道。编辑器、持久化和导出保留完整双通道定义。

示例：[分通道设备配置](../examples/muyu-dual-v6.thingstalk.json)。
