---
name: thingstalk-ble-panel
description: 为硬件开发者按 ThingsTalk 已有客户端标准实现 BLE、MQTT 或双连接固件及配套面板 JSON。用于硬件接入和能力映射，不用于修改客户端或维护客户端契约。
---

# 硬件对齐 ThingsTalk 面板标准

为用户指定的硬件能力生成固件协议及 `.thingstalk.json` 面板。配置定义组件、能力、报文和 BLE/MQTT 绑定；App 使用已有组件渲染，不需要硬件开发者编写 Compose 页面。

## 任务边界与兼容目标

ThingsTalk 客户端是既定的兼容目标。本任务只实现固件、设备配置、协议说明和测试向量。即使工作区包含客户端源码，也只能只读核对行为；不得修改客户端代码、测试、校验器、Skill 契约或兼容快照来适配硬件，不自动切换为客户端维护任务。

先记录目标 App 版本/构建、目标平台、固件版本和本包 contractRevision。契约修订号、schemaVersion 和 App 版本不是同一个概念；快照不证明某个已发布 App 已包含这些能力。版本未知时标注“目标版本兼容性待验证”，可以按本契约设计，但不能宣称目标 App 已验证可用。发现源码与契约冲突时报告差异，不自行刷新契约。

需求应映射到已有 widget、SetupProtocol、固定字段语义和报文规则。不得添加枚举/JSON 键、重新解释固定字段或放宽校验来绕过限制。缺口须在最终输出以“需要客户端扩展，当前不可直接使用”明确列出需求、限制、已有能力替代方案及未交付部分；仅报告扩展需求，不实施客户端扩展。可交付已支持的子集，但不能静默丢弃需求或将未支持部分混入配置。

交付配置属于硬件项目；本包规范、示例和快照是只读参考。示例 UUID/topic 不是目标设备事实。只有在目标客户端实际验证后，才报告导入和通信通过；修改另一个客户端后通过不构成目标版本兼容证明。

## 先区分需求

- **普通控制面板**：日常读值、开关、滑块、数值输入、文本输入加发送按钮、固定动作按钮，使用 capabilities/widgets/payloadFormats 和 BLE/MQTT 绑定。
- **设置面板（SetupPanel）**：Wi-Fi、设备端 MQTT 等文本/密码表单，使用 v7 顶层 setupPanels，分组校验、提交和确认。新硬件网络设置通过 BLE_CHUNKED_SETTINGS_V2，独立 AI 设置通过 BLE_AI_SETTINGS_V2 执行（已有 V1 硬件保留原协议），不支持 MQTT 提交设置。
- **App 本机 MQTT 连接配置**：ThingsTalk 连接 Broker 使用的地址、账号密码和 Client ID，由用户在 App 连接库管理，不是上述两种面板。

两种面板可共存于同一份设备 JSON 和同一个展示页；PanelDefinition 是整份配置，不增加名为 normalPanels、panels 或 widget.type=SETUP 的字段。不要因为硬件“支持 MQTT”就给它增加设备端 MQTT 设置表单，也不要用普通 BUTTON 承载密码提交。按用户实际需要选择。

## 按需阅读与版本选择

保留 `thingstalk-ble-panel` 名称以兼容已有引用。先读 [兼容规范与选择表](references/contract.md) 和 [能力快照](references/compatibility.json)，再读相关分支：

- 普通 BLE 控制：v3；参考 [六组件示例](assets/all-widgets.thingstalk.json) 和 [字节向量](assets/wire-vectors.json)。
- 普通 MQTT 控制：推荐 v4；读 [MQTT 与连接配置规范](references/mqtt-dual.md)，参考 [六组件示例](assets/mqtt-all-widgets.thingstalk.json)。Web 仅 MQTT，不提供 BLE 设置表单。
- 双连接控制：新交付使用 v6 分通道；读同一 MQTT 规范，参考 [v6 示例](assets/dual-channels.thingstalk.json)。确有固定混合路由需求时参考 [旧 v5 示例](assets/dual-power.thingstalk.json)。
- 设备设置表单：整份配置使用 v7；读 [设置表单与事务规范](references/ble-network-provisioning.md)，参考 [可导入示例](assets/setup-panel.thingstalk.json)。可只有设置表单，也可与普通组件共存；DUAL 的 BLE/MQTT 绑定仍分区。新配置显式填写 V2 protocol；既有 V1 不自动升级。改标签、UUID 不会改变字段编号或线协议。

本包可独立复制给硬件团队；不依赖 App 仓库或客户端维护工具。以 [任务边界](references/hardware-boundary.md) 和能力快照为准，不得从旧企划书或组件名称推断未列出的能力。

## 固件与配置协同设计

先核对目标客户端的已有 panel/widget、SetupProtocol、字段语义和版本，再设计硬件与配置；硬件实现客户端标准。已有规范能覆盖的场景直接复用，不把固定协议规则当作缺陷改成任意表单。输出须列出选用的组件/设置协议及对应硬件能力；只有确实无法覆盖时才按扩展提示报告缺口。

1. 从已有固件头文件、GATT 表、topic/报文协议和用户需求提取事实。列出每项能力的数据类型、范围、单位、读写权限、上报方式和操作语义。缺少关键协议时询问或明确提出待确认设计；不能将示例 UUID、命令码视为现有硬件事实。
2. 新协议优先采用短、完整、独立的状态帧与单项控制命令。既有协议保持实际含义；不兼容项列出需要的固件调整或 App 扩展，不能凭空增加 widget、codec、JSON 路径或请求应答功能。
3. 建立映射表：硬件能力 → capabilityId → widget → BLE read/write/notify 或 MQTT publish/subscribe → UUID 路径或精确 topic → formatId/fieldId；双连接 v6/v7 分列 channels.ble.bindings/channels.mqtt.bindings，v5 才列 controlTransport/stateTransport。严格按规范区分传输完成与业务成功。双连接交付须说明 v6/v7 按标签选通道还是 v5 固定混合路由。v6/v7 声明默认通道；v5 明确列出实际选用的路由，不能把“两种绑定齐全”说成会随连接标签自动切换。
4. 设置表单另列 setupPanelId → section.id → field.id → readUuid/commandUuid/statusUuid、长度/规则、secret 和事务回执。先确认固件实现当前适配器；缺少兼容协议时明确扩展需求。若使用设备名拼接 MQTT topic，为设置字段 6 声明与 topic 一致的 defaultValue（IDENTIFIER），不为凭据设置默认值；已有设备名不会静默覆盖。同步实现/说明固件收发和面板 JSON，提供正反向测试向量。对写入多字段报文，说明其他默认字段的副作用；不能用固定默认值覆盖设备未知状态。必要时拆成独立命令特征或命令格式。
5. 验证配置引用、GATT 属性/topic 与 QoS、帧长度、边界值、真实读回/通知/状态发布和重连。双连接验证单边断线不会自动切换或重发命令。可用 App 离线试算器核对字节；有客户端源码和现成测试环境时可只读核对并运行现有测试，不修改测试或实现。没有运行过的硬件、导入或测试不能声称验证通过。

## 交付物

- `设备名.thingstalk.json`：严格 JSON，无注释、无未定义字段、无占位 UUID/topic；必须是 App 可导入的配置结构。
- 协议说明：固件/协议版本、GATT 属性表和/或 MQTT topic、QoS、方向表，字节布局或 JSON 字段表、单位换算、命令行为、状态通知时机及最大报文长度。
- 测试向量：每种报文的字段值和准确字节，包含负值/边界、开关两态、动作，以及至少一个应拒绝的报文。
- MQTT 部署说明（适用时）：Broker 的 WS/WSS 入口、路径、认证要求及 Client ID 策略；用户在二级页配置并在一级页选用，不把地址、账号密码或本机配置 ID 写入面板 JSON。
- 设置表单说明（适用时）：设置窗口、编辑前加密读取准备、未配置返回空值、配对要求、字段映射、密码保留/清除、保存确认与网络连接的区别；示例只含定义，不含真实输入值。
- 能力映射与验收结果：明确已验证和待验证项目；列出当前 App 不支持的需求。

把面板文件作为固件发布附件或仓库内配套文件交付。用户在 ThingsTalk 中创建/关联设备，再进入“开发者设置 → 协议与面板编辑 → 导入配置文件 → 保存并应用面板”。不要声称广播/GATT 描述符能让 App 自动下载并安装配置；当前没有此发现协议。配置里不放平台设备 ID、MAC、连接凭据或脚本。
