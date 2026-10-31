# 项目文档

## 使用与架构

- [设备创建与配置导入](guides/devices.md)
- [MQTT 连接配置](guides/mqtt-connections.md)
- [消息、模板与本地工作台](guides/workspace.md)
- [外观设置](guides/appearance.md)
- [架构与会话生命周期](architecture/sessions.md)
- [图标源文件与平台资源](design/app-icon.md)

## 协议与示例

- [BLE 报文与面板 v3](protocols/panel-configuration-v3.md)
- [MQTT 面板 v4](protocols/mqtt-panel-configuration-v4.md)
- [混合路由双连接 v5](protocols/dual-transport-panel-v5.md)
- [分通道双连接 v6](protocols/channel-panel-v6.md)
- [设置表单 v7](protocols/setup-panel-v7.md)
- [配置示例目录](examples/)
- [完整硬件交接规范](../skills/thingstalk-ble-panel/references/contract.md)

## 本地资料约定

`local/` 不提交 Git：`planning/` 存放企划，`research/` 存放调研，`troubleshooting/` 存放排查过程，`history/` 存放会话交接与改动流水，`design/` 存放设计过程和预览，`hardware-patches/` 存放外部硬件联调补丁。正式协议和可复用结论应整理到上面的公共目录，不依赖本地资料才能理解或构建项目。
