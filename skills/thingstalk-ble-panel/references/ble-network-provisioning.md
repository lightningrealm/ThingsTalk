# v7 可配置设备设置面板

Wi-Fi 和设备端 MQTT 参数由 `setupPanels` 声明，App 不再根据木鱼 UUID 自动插入专用页面。现有 v1–v6 仍兼容；需要设置表单时使用 v7。Android/iOS 支持，Web 不提供 BLE 配网。

## 配置结构与入口

v7 保留 v6 的 `channels.ble.bindings` / `channels.mqtt.bindings`、共享 capabilities/widgets/payloadFormats，增加可选顶层 `setupPanels`。支持 BLE、MQTT、DUAL；只有 BLE/DUAL 可包含设置表单。DUAL 必须指定 defaultChannel，单通道不得指定；无绑定的一侧填写 `bindings: []`。不得出现旧顶层 bindings。导入仍走开发者设置里的原入口，保存后在设备展示页 BLE 通道显示折叠表单。编辑器可查看表单摘要，字段定义目前通过 JSON 编辑/导入，不提供表单可视化搭建器。

每个 SetupPanel 必须给出 id、title、instructions、protocol、serviceUuid、commandUuid、statusUuid、sections。新硬件必须显式声明 `BLE_CHUNKED_SETTINGS_V2`（网络）或 `BLE_AI_SETTINGS_V2`（AI）。为兼容旧配置，省略 protocol 仍按 `BLE_CHUNKED_SETTINGS_V1` 处理，绝不自动升级或探测切换。最多 4 个表单，ID 唯一，格式 `[A-Za-z][A-Za-z0-9_.-]{0,63}`；title 1–80 字符，instructions 最多 500 字符。UUID 使用完整格式，命令与状态不能相同。

sections 包含 1–2 个分组，每组 id/title/fields。协议固定分组 1（字段 1/2）、分组 2（字段 3/4/5/6），组内 ID 唯一；字段标签和顺序由配置决定。每字段包括 id、label（1–80 字符）、maxBytes（1–512），可选 minBytes（默认 0）、secret（默认 false）、readUuid、rule（默认 TEXT）。minBytes 不得大于 maxBytes。字节长度按 UTF-8 计算，所有值拒绝 NUL。

字段 2/5 必须 secret=true，secret 字段禁止 readUuid。普通字段可省略 readUuid，由用户输入；提供时需不同于命令/状态 UUID。所有读取路径必须具有 READ，命令需要 WRITE with response。服务/特征路径不唯一或属性不满足时表单不可操作。

规则：TEXT 仅校验长度；WIFI_PSK 允许空值、8–63 UTF-8 字节或 64 个十六进制字符；MQTT_URI 要求 ws/wss/mqtt/mqtts 前缀且不含空白、@、?、#（具体 URI 合法性仍由固件校验）；IDENTIFIER 仅 ASCII 字母、数字、下划线、连字符。字段长度需与目标固件一致，不能直接照搬上限。

App 展开后由用户点击“准备设备设置”，读取状态和非密码配置，成功后开放编辑；不自动持续轮询。准备时不发送 BEGIN 或任何配置写入，最多等待 60 秒。密码留空保留旧值，明确勾选清除才发送空值（字段规则仍须允许空值）。输入只保留在当前表单内存，结束操作后清除密码，不写入配置、草稿、导出文件或持久存储。配置的命令与读取特征在 BLE 日志中隐藏内容，包括调试页访问。关闭表单/切换通道取消未完成任务，已发出的 COMMIT 不能撤回。

参考自包含示例 `assets/setup-panel.thingstalk.json`，其中 UUID、字段长度与长按 UP 提示是木鱼固件的示例，不是 App 的固定逻辑。必须按目标硬件定义填写；该线协议固定编号/事务语义，其他线协议需要新增适配器，不可随意声明未实现的 protocol，也不执行任意脚本。普通组件不会因此获得设置事务分包能力；普通 TEXT_INPUT/UTF8 仍使用自己的单条消息规则。

## 首次配置与读取结果

编辑前仍需成功读取设备状态，并确认设置窗口开启。V2 用 savedSections 的 bit(sectionId - 1) 表示该分组已保存：某组为 0 时代表正常的“未配置”，客户端仍读取该组已声明的 readUuid，以便由固件的加密读取权限触发系统安全协商；读取成功后将该组非密码字段置空并允许填写。例如初次配置且窗口已开启，flags 可为 0x01；状态仍需返回完整合法的 16 字节，不可返回空状态或 GATT 错误。

某组已保存时，客户端读取该组已声明 readUuid 的非密码字段；零长度字段值可以正常读入，输入最小长度在提交时校验。读取错误、非法状态帧、断线不能当成“未配置”。两组可分别处于已配置/未配置状态；现有 UI 对整个表单完成读取后才开放编辑，任一需读取字段失败会提供重试提示。窗口关闭时提示开启设置窗口，不开放编辑。

未配置不等于缺少特征：固件仍需暴露 JSON 声明的命令、状态和读取特征及相应属性。未配置特征必须能正常读取（允许零长度），不可用通信错误表示无初始值。已有配置不得错误清除已保存标志，否则客户端会展示空表单。密码仍不读回。为在保存前准备加密访问，面板至少声明一个非密码 readUuid，固件应使其读取安全要求与配置写入相容；无 readUuid 时客户端提示无法准备，不开放编辑。App 无法从普通 GATT READ 属性推断特征是否要求加密，因此读取成功只表示该读取路径可访问，不等同于独立验证了配对等级；写入权限仍由 BLE 层和固件检查。

## 写入事务

所有报文最多 20 字节，头部为 `opcode UINT8 | requestId UINT16 LE | field UINT8 | offset UINT16 LE`，之后是 0–14 字节原始 UTF-8 数据。客户端按当前写入上限分包，不依赖较大 MTU。此分包仅适用于已声明的设置协议，不扩展普通面板的分包能力。

- `B1` BEGIN：field=1 配置 Wi-Fi，field=2 配置 MQTT；offset=0，无数据。设备复制已保存配置为事务候选值。
- `B2` APPEND：field 1=SSID、2=Wi-Fi 密码、3=URI、4=用户名、5=MQTT 密码、6=设备名。offset 必须是该字段已收到的字节数；零长度首包表示清空字段。Wi-Fi 事务只允许字段 1/2，MQTT 只允许 3/4/5/6。
- `B3` COMMIT：field=0，offset=0，无数据。Wi-Fi 需要显式写字段 1；MQTT 需要显式写字段 3/4/6；省略密码字段保留旧密码。整组字段通过范围/格式校验后才写 NVS。
- `B4` ABORT：field=0，offset=0，无数据，清除候选值。

事务绑定当前 BLE 连接与配置版本，60 秒无配置报文、断开连接、关闭设置窗口或网页更新配置都会令事务失效。新 BEGIN 替换旧候选值。中途退出不会保存部分字段；已发出的 COMMIT 无法撤销。客户端不重试或补发，超时需读回核实后再保存。

### V2 统一状态回执（16 字节）

网络与 AI 使用完全相同的状态结构和位语义，所有多字节整数为小端无符号数：

| 偏移 | 长度 | 内容 |
| --- | --- | --- |
| 0 | 1 | D1 |
| 1 | 1 | version=02 |
| 2 | 1 | flags：bit0=设置窗口开启，bit1–7 必须为 0 |
| 3 | 2 | requestId |
| 5 | 1 | opcode：初始 0，或 B1/B2/B3/B4 |
| 6 | 1 | result |
| 7 | 1 | field（BEGIN 为 section ID；COMMIT/ABORT 为 0） |
| 8 | 2 | nextOffset |
| 10 | 4 | 配置 revision |
| 14 | 1 | savedSections：bit(id-1)=该 section 已持久化 |
| 15 | 1 | savedSecrets：bit(id-1)=该 secret field 已有非空凭据 |

当前 section ID 为 1–2、field ID 为 1–6；其他位必须为 0。网络允许 savedSections=00/01/02/03，savedSecrets 仅 bit1（字段2 Wi-Fi 密码）、bit4（字段5 MQTT 密码）；AI 仅 section1（01）和字段2（02）。这两个位图的定义始终是 ID，不得把某一位重新解释成联网/服务商认证成功。位图报告整个设置服务的持久化状态，不受面板只展示部分字段影响；断电恢复后也应准确。省略秘密字段保留原值及位，清空字段清除对应位，成功保存设置组设置该组位。

result：0 初始，1 接收成功，2 持久化保存成功，3 参数错误，4 窗口关闭，5 会话/版本冲突，6 存储失败。初始回执 request/opcode/result/field/nextOffset 为 0，revision 和状态位图来自设备实际状态。保存成功后 revision 增加（UINT32 回绕），失败不变。

每次 WRITE 响应仅表示传输完成；客户端读 statusUuid，匹配 requestId、opcode、field、nextOffset。APPEND 的 nextOffset=请求 offset+本包负载字节数，其他操作为 0。COMMIT 必须 result=2 且目标分组已保存位为 1，才显示已保存。禁止把入队、发起联网或未经持久化的候选值报告成保存完成。

Wi-Fi/MQTT连接和 AI 服务认证不属于该回执。需要展示时，另外暴露普通状态特征或 MQTT topic，并绑定普通 VALUE 组件；不能占用保留位。设备端 Broker 配置与 App 本机连接库独立，配置文件不包含运行时凭据。

### V1 兼容边界

已有 `BLE_CHUNKED_SETTINGS_V1` / `BLE_AI_SETTINGS_V1` 继续使用原 14 字节布局：`D1 01 flags requestId(2) opcode result field nextOffset(2) revision(4)`。网络 flags bit0=窗口、bit1=Wi-Fi连接、bit2=MQTT连接、bit3/4=分组1/2已保存、bit5/6=字段2/5秘密已存；AI bit0=窗口、bit3=分组1已保存、bit5=字段2秘密已存。客户端在独立兼容解析器中转换为统一表单状态，网络连接位不进入通用表单状态。

V1 配置必须配 V1 固件，V2 配置必须配 V2 固件。版本/长度不匹配立即失败，保存前状态检查失败时不发送配置命令；不靠改配置枚举“升级”旧固件，也不自动降级或重放命令。新接入使用 V2；维护现有 V1 硬件时保留其协议。新 V2 配置需使用支持 V2 的 ThingsTalk 构建，Skill 修订号不能代替 App 版本。JSON 仍为 schemaVersion=7，这与线协议 version=02 是不同层级。

独立向量见 `assets/setup-status-vectors.json`，它是测试数据，不可作为面板导入。

## 设备端与 App 本机 MQTT 的区别

setupPanels 的 URI/用户名/密码配置的是外设，由 BLE 下发；真实输入值不进入面板文件。App 本机仍使用独立的连接配置库，统一 WS/WSS，用户自行选用；设备端 mqtt/mqtts 支持不代表 App 新增 TCP 支持。更改设备名时须同步对应精确 topic；当前不自动改写 topic 或自动创建本机连接配置。

硬件包 assets 中的设置示例明确使用 V2；客户端仓库 docs/examples 中既有木鱼示例保留 V1，供原固件使用。新示例不会自动升级旧设备；只有固件已实现 V2 时才改用 V2 配置。固件仍负责加密链路、配对、设置窗口、事务归属、存储和网络重连；提示文字本身不能实现这些保证。木鱼原固件使用物理设置窗口与加密 Just Works，不能视为 MITM 身份认证。

自动测试验证配置导入/往返、独立 UUID、UTF-8 分包、密码保留/清空、提交失败不重试及日志隐藏。真实配对、分包、NVS 断电恢复及网络连通需真机联调，不把编译或模拟连接测试视为硬件验收。

## 保存失败诊断

客户端区分 BLE 写入失败/超时、状态读取失败、无效状态帧、匹配回执超时、固件拒绝及回执字段/偏移不匹配。固件拒绝时保留 result 对应原因，页面可显示请求号、操作码和回执元数据；不显示配置负载或底层异常原文。单包写入最多等待 30 秒，回执最多 15 秒，整个保存事务最多 90 秒，不自动重发。

当前第一次配置也会在编辑前读取配置特征；修订 16 及以前跳过未保存组字段读取，可能将首次加密访问推迟到提交命令。BLE 写入失败本身不能证明 iOS 配对失败；也不能把无匹配回执当作固件明确拒绝。系统配对、GATT 权限和设备连接状态仍需真机核对。当前保存回执与网络连通确认保持独立，尚无可配置的“发送即结束/等待连网成功”两种策略。

BLE 写入失败时，客户端从 Kable 包装异常中仅提取允许的 CBATTErrorDomain/CBErrorDomain 与数字 code，或已识别的本地连接/写入前置错误。没有可识别代码时明确提示未知，不展示 NSError 描述或 userInfo。0xB1 是无凭据的 BEGIN 包；该阶段写入失败不能归因于用户输入的密码格式，也不能仅凭它断定配对失败。需结合实际系统错误码、配对提示及固件日志定位。

准备阶段若加密特征读取返回 CBATTErrorDomain code=5（认证不足）或 15（加密不足），客户端每隔 2 秒重新检查设置窗口并尝试读取，每个字段最多 15 次，整个配置读取准备仍受 60 秒超时限制。只对这两类读取错误重验，不重发任何配置写入；其他错误立即报告。页面显示等待验证、提供取消入口。用户确认系统配对后，下一次读取成功才开放编辑；持续失败提示访问权限仍未通过，不将“确认配对”当作可写入证明。

## 设备名称默认值与空密码确认（修订 20）

字段 6（设备名称，rule=IDENTIFIER）可声明 `defaultValue: "muyu-01"`。网络设置协议中仅此字段支持默认值，不允许将 SSID、用户名或密码放进默认值。默认名称必须通过长度和标识符校验，并由配置作者保证与所有 MQTT topic 中的设备标识一致；客户端不会猜测任意 topic 的结构或自动替换 topic。

导入时显示建议名称及主题一致性提示，不直接写入硬件。准备完成后，仅当设备报告 MQTT 分组未保存时预填默认名称；已有名称（包括空值）保留不覆盖。实际值与建议值不同时显示提醒和“使用面板建议值”按钮，用户明确选择后再保存。此字段是 v7 的新增可选扩展，需要修订 20 对应的新客户端；旧严格解析客户端可能拒绝包含 defaultValue 的配置，升级客户端后导入。未声明默认值的旧文件仍可用，但不会自动推导名称。

保存前，secret 字段留空必须弹窗确认：设备报告已保存密码时说明“省略该字段并保留旧密码”；没有报告已保存时说明“本次不设置密码，需要认证的连接可能失败”。勾选清除时明确确认“发送空值清空密码”。取消返回填写，不发送配置。仅非空密码且未选择清除时可直接提交；绝不将密码内容放进弹窗或日志。保存回执更新密码是否已存标志，后续提示基于最新已知状态，最终校验仍由固件负责。

## AI 设置适配器（BLE_AI_SETTINGS_V2）

此适配器独立于 Wi-Fi/MQTT 配网表单，仍使用 v7 setupPanels。必须使用固件专门提供的命令、状态及读取 UUID，不得指向网络配置特征。普通组件、自由文本聊天和 MQTT 参数配置行为不变。

只允许一个 section（id=1），字段 id=1 为 HTTPS Chat Completions 完整地址，id=2 为 API Key（secret=true，无 readUuid），id=3 为模型名称。字段 1/3 使用 TEXT 规则，允许非秘密 defaultValue，固件仍须验证 HTTPS 地址和字段格式；Key 禁止默认值。最大字节数由固件规定，鲸鱼示例分别为 192/192/64。

复用 B1/B2/B3/B4 六字节头、16 字节 ACK 状态和 20 字节分包：BEGIN field=1；APPEND field=1/2/3；COMMIT 要求显式字段 1/3，省略字段 2 保留 Key，空字段 2 清除 Key。状态帧 version=2，flags bit0=窗口；savedSections bit0=分组1已保存，savedSecrets bit1=字段2 Key 已保存，其他位为 0。与网络设置遵循相同的 section/field ID 位图规则。首次准备仍通过加密 readUuid 触发配对。Key 不读回、不出现在导出面板或日志中；响应中的 Key 已存标志不能证明服务商认证成功。

### Fixed provider settings

`BLE_AI_SETTINGS_V2` 的非秘密字段 1/3 可声明 `readOnly: true`，必须同时提供有效 `defaultValue`。客户端仍进行加密读取以完成访问准备，但显示为说明文字并固定提交默认值，用户只编辑 Key。其他字段和网络协议禁止 readOnly。旧配置省略该字段时行为不变；旧客户端可能拒绝新字段，需使用同步更新的客户端。固件必须独立校验固定服务参数，不能信任 UI 限制。
