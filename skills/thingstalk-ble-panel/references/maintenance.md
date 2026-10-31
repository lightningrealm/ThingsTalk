# 与应用同步维护

这个目录是随应用代码版本维护的分发包，不依赖开发者本机的绝对路径。硬件团队可复制整个 `thingstalk-ble-panel` 文件夹到其 AI 工具的技能目录；不支持 Skill 的工具直接读取本包 `PROMPT.md`。本项目不自动安装到任何人的全局技能目录。

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
   python3 skills/thingstalk-ble-panel/scripts/sync_contract.py --refresh --revision <新的修订号> --reviewed-on YYYY-MM-DD
   python3 skills/thingstalk-ble-panel/scripts/sync_contract.py --check
   ./gradlew :shared:testAndroidHostTest --tests '*HardwareSkillContractTest'
   ```

5. 提交代码、Skill、示例、快照与生成的 PROMPT.md 到同一次变更，并重新分发整个包或新的 Prompt。`--refresh` 只更新枚举清单/指纹和生成文档，不会推理或自动修正文字规范。

`compatibility.json` 的 `contractRevision` 是本包修订号；`panelSchemaVersions` 按 BLE/MQTT/DUAL 记录普通面板推荐版本，SETUP 记录设置面板所需版本，两者独立。指纹追踪当前工作区内容（包含未提交改动），不声称对应已发布应用版本。冻结固件交付时，同时记录所用 App 构建/固件版本。

## 检查覆盖范围

`--check` 比较被跟踪源码、文档、示例的指纹和生成 Prompt，发现漂移返回非零。没有 App 源码的独立分发包只检查包内一致性，并明确提示无法验证源代码新鲜度。它不是任意面板配置的语义验证器。

`HardwareSkillContractTest` 使用应用真实 PanelJson/编解码器验证本包 BLE、MQTT、v5/v6 双连接及 v7 设置表单示例、BLE/MQTT 的所有当前组件覆盖、双连接路由与错误路由拒绝、有效向量双向编解码及无效向量拒绝。测试通过不代表真实硬件已实现该协议；发布前仍需用外设逐项联调。规则修改后应审查完整规范；仅更新哈希使检查变绿不能替代审阅。

## 设置面板维护

同步审阅 `references/ble-network-provisioning.md`、`domain/panel/SetupPanel.kt`、`domain/ble/ChunkedBleSettingsProtocol.kt`、`data/ble/BleSetupPanelClient.kt`、`data/ble/TracingBleConnection.kt`、`presentation/panel/SetupPanelView.kt` 和设备会话管理。设置表单使用 v7 setupPanels；同步字段验证、协议适配器、精简及木鱼示例。普通数值组件不自动获得文本/分包能力。运行 `MuyuProvisioningTest`、`HardwareSkillContractTest`、设备会话和日志测试，并在硬件端验证对应 UUID/帧布局/保存回执。
