# 与应用同步维护

此文档仅供 ThingsTalk 客户端维护任务使用，不分发给硬件团队。硬件分发目录为 `skills/thingstalk-ble-panel/`，下文 references/assets 均相对该目录；维护脚本位于本 Skill 的 scripts/。不支持 Skill 的硬件工具使用硬件目录中的 PROMPT.md。本项目不自动安装全局 Skill。

规范的事实来源：

- domain/panel/PanelDefinition.kt：组件/能力类型、配置模型、组件映射和 validatePanel。
- domain/panel/PayloadFormat.kt：格式模型、字段范围和引用约束。
- data/panel/PanelJson.kt、ChannelPanelJson.kt、PanelPayloadCodec.kt、PanelValueCodec.kt：实际导入及编解码行为。
- domain/panel/MqttPanelBinding.kt、DualPanelBinding.kt：MQTT 与双连接操作、路由及校验。
- data/panel/BlePanelOperations.kt、MqttPanelOperations.kt、DualPanelOperations.kt：真实执行路径。
- domain/mqtt/MqttProfile.kt、data/mqtt/MqttProfileStorage.kt、MqttValidation.kt、KtorMqttTransport.kt、presentation/mqtt/MqttProfilePages.kt：本机连接配置与传输边界；另追踪三端 MqttProfileStorage 平台实现。
- presentation/panel/ConfigurableDevicePage.kt、PanelNumberControls.kt：组件行为。
- presentation/panel/PanelFileTransfer.kt：文件大小等限制。
- presentation/panel/DualPanelBindingEditor.kt、PanelEditorPage.kt、presentation/device/DeviceConnectionPanel.kt、presentation/components/TransportTabs.kt：共用面板的通道 Tab 与导入说明。

上述目录均位于项目 `shared/src/commonMain/kotlin/com/lr/thingstalk/`。

## 修改组件或协议时

1. 更新 App 实现、校验、迁移和有意义的测试。区分“仅加组件”与“不兼容配置变化”；不要为了更新文档而随意增加 schemaVersion。
2. 审阅 `references/contract.md` 和 `references/mqtt-dual.md`：同步组件表、绑定要求、类型、限制和硬件操作语义。新组件应补入可导入示例；不能支持的要求继续标明。
3. 更新 `assets/all-widgets.thingstalk.json` 、`assets/mqtt-all-widgets.thingstalk.json`、`assets/dual-power.thingstalk.json`、`assets/dual-channels.thingstalk.json`、`assets/setup-panel.thingstalk.json` 和 `assets/wire-vectors.json`。向量数值/字节要按协议独立核对，不从待测编码器自动生成预期结果。
4. 在项目根目录执行（revision 填新的文档修订号，日期填实际审阅日）：

   ```sh
   python3 skills/thingstalk-contract-maintainer/scripts/sync_contract.py --refresh --revision <新的修订号> --reviewed-on YYYY-MM-DD
   python3 skills/thingstalk-contract-maintainer/scripts/sync_contract.py --check
   ./gradlew :shared:testAndroidHostTest --tests '*HardwareSkillContractTest'
   ```

5. 将代码、Skill、示例、快照与生成的 PROMPT.md 作为同一项变更审阅；是否提交由当前任务决定。发布时只分发整个 `thingstalk-ble-panel` 目录或其中的 Prompt，不能把本维护 Skill 一起交给硬件端。`--refresh` 只更新枚举清单/指纹和生成文档，不会推理或自动修正文字规范。

`compatibility.json` 的 `contractRevision` 是本包修订号；`panelSchemaVersions` 按 BLE/MQTT/DUAL 记录普通面板推荐版本，SETUP 记录设置面板所需版本，两者独立。指纹追踪当前工作区内容（包含未提交改动），不声称对应已发布应用版本。冻结固件交付时，同时记录所用 App 构建/固件版本。

## 检查覆盖范围

`--check` 比较被跟踪源码、文档、示例的指纹和生成 Prompt，发现漂移返回非零。维护脚本只留在客户端仓库；使用 --repo 指向无源码目录可做包一致性检查，但无法验证源码新鲜度。它不是任意面板配置的语义验证器。

`HardwareSkillContractTest` 使用应用真实 PanelJson/编解码器验证本包 BLE、MQTT、v5/v6 双连接及 v7 设置表单示例、BLE/MQTT 的所有当前组件覆盖、双连接路由与错误路由拒绝、有效向量双向编解码及无效向量拒绝。测试通过不代表真实硬件已实现该协议；发布前仍需用外设逐项联调。规则修改后应审查完整规范；仅更新哈希使检查变绿不能替代审阅。

## 设置面板维护

同步审阅 `references/ble-network-provisioning.md`、`domain/panel/SetupPanel.kt`、`domain/ble/ChunkedBleSettingsProtocol.kt`、`data/ble/BleSetupPanelClient.kt`、`data/ble/TracingBleConnection.kt`、`presentation/panel/SetupPanelView.kt` 和设备会话管理。设置表单使用 v7 setupPanels；同步字段验证、协议适配器、精简及木鱼示例。普通数值组件不自动获得文本/分包能力。运行 `BleSetupPanelClientTest`、`HardwareSkillContractTest`、设备会话和日志测试，并在硬件端验证对应 UUID/帧布局/保存回执。

## 角色边界验收

发布前检查 Skill 和独立 Prompt 的以下场景（静态审阅不等于已运行 AI 行为测试）：

- 输入框加发送按钮：映射 TEXT_INPUT/TEXT/UTF8，交付固件和 JSON，不改客户端。
- 图片组件或任意设置字段：报告缺口与替代方案，不生成不存在的枚举或修改校验器。
- 用户安装的是旧版本：明确版本差异，选择已支持能力或报告待验证，不把工作区能力当成已发布能力。
- 硬件工作区包含客户端源码：只读核对；客户端源码、测试、契约与快照保持不变。

复制完整硬件目录到独立临时目录检查链接、示例及许可证齐全；不能包含维护说明或刷新脚本。建议硬件工作区只放硬件仓库与该分发包，减少角色混淆；提示词不能替代文件系统权限。

## 设置协议版本维护

V1 网络/AI 的 14 字节帧保留在兼容解析路径，省略 protocol 仍为网络 V1；不要改默认值或自动重写用户已有配置。V2 网络/AI 共用 16 字节状态及 section/field ID 位图，新增能力以客户端定义为准。更新时同时运行旧版事务测试、新版状态与保存测试，以及 `assets/setup-status-vectors.json` 的真实解析测试。不同版本不能自动探测切换或重放。硬件包设置示例使用 V2，仓库既有木鱼示例保留 V1。
