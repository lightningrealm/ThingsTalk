# 设备设置表单 v7

`setupPanels` 与普通组件共存于同一份配置，描述分组、字段、校验及 BLE 命令/状态/读取特征。需要设置表单时使用 schemaVersion 7；运行时输入的 Wi-Fi、MQTT 凭据不写入配置文件或日志。Web 不支持 BLE 设置表单。

普通面板 widget 与设置表单不是同一模型；不能给普通数值组件任意追加文本或分包能力。完整字段、帧布局和硬件约束见[设置协议规范](../../skills/thingstalk-ble-panel/references/ble-network-provisioning.md)。

用户先准备设备访问，完成必要系统配对后再编辑提交。未保存过的分组允许无初始值；有读取定义的字段根据协议读取，密码不读回。客户端区分准备读取、写入、状态读取、回执超时和固件拒绝，不在未知结果时自动重发。

固件确认保存不等于实际联网成功；网络状态通过对应状态字段或面板能力呈现。本机 MQTT 连接配置和写入硬件的 MQTT 参数相互独立。

设备名称字段可以声明 defaultValue，仅限字段 6、IDENTIFIER，不允许凭据默认值。导入提示建议名称及 topic 一致性，未保存的分组可预填；已有硬件配置不被默认值覆盖。密码为空时保存需用户确认其含义。

示例：[设置面板](../examples/muyu-setup-v7.thingstalk.json)、[含建议设备名与网络状态](../examples/muyu-network-default-v7.thingstalk.json)。示例 UUID、topic 与命令需要硬件对应实现，不是任意外设的通用协议。
