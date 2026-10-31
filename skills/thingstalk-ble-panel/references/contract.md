# 当前硬件接入契约

此规范描述当前实现，不是未来路线图。兼容快照版本与核对日期见 compatibility.json。

应用内置界面支持简体中文和英文，可跟随系统或手动选择。导入配置中的 `title`、`label`、说明、单位及用户填写内容保持原文，不随应用语言自动翻译。请按硬件用户的目标语言填写这些文案；当前没有 `localizedTitles` 等多语言 JSON 字段。UUID、topic、字段 ID、枚举值和二进制/JSON 报文格式不参与翻译，不因应用语言变化而改变。为其他语言交付配置时只调整显示文案，不修改协议标识或编码。

## 0. 配置层次与版本选择

### 能力边界与扩展提示

硬件和配套配置必须从当前已实现的面板能力出发：先将每项需求映射到下表的 widget，或设置表单规范中已支持的 SetupProtocol、section/field ID 及其固定语义，再设计固件收发。允许修改实例标题、UUID、topic、受支持的报文格式，或组合现有组件生成新设备面板；这不等于新增客户端面板类型。不得仅因 JSON 可表达就宣称客户端支持。

需要新的 widget、设置协议、字段语义、codec 或交互流程时，AI 必须在面向硬件开发者的最终输出正文中给出以下提示，不能只写在文件注释、内部推理或测试日志中：

> **需要客户端扩展，当前不可直接使用**：需求是……；当前面板不能支持的部分是……；可用现有能力实现的替代方案及限制是……（没有则明确说明）；需要 ThingsTalk 增加……并与固件约定……。

可交付已支持部分的配置，但必须明确列出未包含的需求及影响；不得静默删减需求、自造 protocol/widget/字段混入 `.thingstalk.json`，或把 Wi-Fi/MQTT 等固定设置字段重新解释为其他用途。扩展草案只放协议设计说明，明确标注“提案，尚未支持”，不作为可导入配置交付。必须在客户端实现、导入/编解码测试及本契约同步完成后，才可将扩展列为已支持；并提醒硬件开发者需要对应的新客户端版本，旧部署不会因 Skill 更新自动获得支持。

App 导入的是 `设备名.thingstalk.json` 面板配置，不是协议测试向量文件。含 `configuration` 文件名引用和 `valid`/`invalid` 用例的 JSON（例如 `wire-vectors.json`）供开发与测试使用；应选择 `configuration` 指向的配置文件导入。客户端不会自动加载该引用，也不会忽略未知字段来尝试导入测试文件。

| 概念 | 配置位置 | 用途与边界 |
| --- | --- | --- |
| 普通控制面板 | capabilities / widgets / payloadFormats + 通道绑定 | 日常状态展示和操作；BLE read/write/notify 或 MQTT publish/subscribe |
| 设置面板 SetupPanel | 顶层 setupPanels 数组（v7） | 文本/密码表单、字段校验、按组提交；BLE_CHUNKED_SETTINGS_V1 网络设置或 BLE_AI_SETTINGS_V1 AI 设置 |
| App 本机 MQTT 连接配置 | App 连接配置库，不放进设备 JSON | ThingsTalk 自己连接 Broker，统一 WS/WSS；与外设保存的 MQTT 参数独立 |

PanelDefinition 是包含普通组件和可选设置表单的整个设备配置，两者不需要拆成两个文件。SetupPanel 不是新的 widget.type，也不通过 capabilityId 绑定；它按自己的 section/field ID 和命令/状态特征执行。配置只包含定义；SSID、账号密码等运行时输入不导出。

| 交付场景 | 推荐 schemaVersion | JSON 形状 |
| --- | --- | --- |
| 普通 BLE | 3 | transport 默认 BLE，顶层 bindings |
| 普通 MQTT（含 Web） | 4 | transport MQTT，顶层 bindings |
| 普通双连接，标签切换实际通道 | 6 | transport DUAL，defaultChannel，channels 两侧绑定 |
| 设置表单，单 BLE 或与双连接控制共存 | 7 | transport BLE/DUAL，channels 两侧绑定，setupPanels；仅 DUAL 指定 defaultChannel |
| 旧双连接固定混合路由 | 5 | 顶层 bindings 内 controlTransport/stateTransport |

旧 BLE v1/v2 可导入。v7 解析也支持不含设置表单的 MQTT，但纯 MQTT 新交付推荐 v4；Web 只运行 MQTT 功能；导入有效 v5/v6/v7 双连接配置时会提示功能可能缺失，并提取 MQTT 部分为 v4：移除 BLE 操作、设置表单及无法通过 MQTT 操作的组件，保留 MQTT Topic、编解码格式和作者文案。仅 BLE 或没有 MQTT 绑定的双连接配置会被拒绝。原文件不变，Web 后续导出的是 MQTT 配置。v6/v7 中没有操作的一侧用 bindings: []，不保留旧顶层 bindings。需要设置表单时整份文件升级 v7，不能仅在 v3/v4/v6 中追加 setupPanels。

普通组件规范如下；MQTT、双连接及本机连接配置见 references/mqtt-dual.md；设置表单见 references/ble-network-provisioning.md。独立 PROMPT.md 已内嵌这些规范与示例。

## 1. 当前可用组件

| widget.type | capability.type | 实际行为 | 硬件映射要求 |
| --- | --- | --- | --- |
| VALUE | NUMBER 或 BOOLEAN | 显示数值和单位，或开启/关闭；选中 BLE 状态来源且有 read 时提供读取按钮 | BLE read/notify 或 MQTT subscribe；适合温度、电量、门状态 |
| SWITCH | BOOLEAN | 输入 true/false 并写入；显示真实回值 | BLE write 或 MQTT publish；推荐相应状态回报 |
| SLIDER | NUMBER | 在范围内调整数值并提交写入 | BLE write 或 MQTT publish；minimum < maximum，范围是 step 的整数倍，1–10000 步 |
| NUMBER_INPUT | NUMBER | 输入数值，校验后提交 | BLE write 或 MQTT publish；适合负数、小数、较大范围，实际精度由 codec 决定 |
| TEXT_INPUT | TEXT | 一个文本输入框加发送按钮；点击才发送，原样保留空白/换行 | 仅 BLE write 或 MQTT publish，UTF8；不支持 read/notify/subscribe |
| BUTTON | ACTION | 发送预定义完整命令，不接受动态参数 | 仅 BLE write 或 MQTT publish；不能绑定 read/notify/subscribe；命名报文所有字段都要 defaultValue |

空白或未绑定组件可以保存，但不能操作硬件。发布可用硬件面板时应完成上述绑定。一个能力可被多个组件引用，例如亮度同时使用 SLIDER 和 NUMBER_INPUT；bindings 中该 capabilityId 只能出现一次。

普通面板目前没有文本状态展示/聊天记录、枚举选择、曲线、颜色选择器、布局分组、拖拽布局或自定义脚本组件。不要生成这些枚举或自造属性。枚举值虽然可以按整数传输，但 App 不提供枚举标签选择器，不能声称等价支持。

## 2. 配置对象与字段

所有枚举区分大小写。BLE v3 顶层示例（MQTT/DUAL 需另加 transport 并使用对应版本）：

```json
{
  "schemaVersion": 3,
  "title": "设备面板标题",
  "payloadFormats": [],
  "capabilities": [],
  "bindings": [],
  "widgets": []
}
```

- capability：`id`, `label`, `type`, `minimum`, `maximum`, `unit`, `step`, `maxBytes`。仅 NUMBER 设置范围；默认 0～255，推荐显式提供真实范围；step 默认 1且必须有限、正数。step 不是通用数值输入的量化器。BOOLEAN/ACTION/TEXT 省略范围和 step。仅 TEXT 可设置 maxBytes（1–512，默认512），按 UTF-8 字节限制输入；不包括帧头尾或 JSON 包装，完整报文仍不能超过512字节。
- widget：`id`, `type`, `label`, `capabilityId`。数组顺序就是展示顺序。
- BLE binding：`capabilityId`, 可选 `read`, `write`, `notify`, 可选旧格式 `actionValue`。MQTT 使用 publish/subscribe；DUAL v5 可同时包含两者并显式指定路由，v6/v7 则按 channels.ble/channels.mqtt 分区，字段见 MQTT/双连接规范。存在 binding 时至少有一种操作。
- format：`id`, `label`, `encoding`（BINARY 默认或 JSON）, `fields`, `prefixHex`, `suffixHex`（默认空）。
- field：`id`, `codec`（默认 UINT8）, `byteOrder`（默认 LITTLE_ENDIAN，另可 BIG_ENDIAN）, `scale`（默认1）, `bias`（默认0）, `falseHex`（默认00）, `trueHex`（默认01）, `defaultValue`（可选字符串）。
- payload 引用：`formatId`, `fieldId`。属性必须指定 fieldId；ACTION 只引用 formatId。

ID 使用 `[A-Za-z][A-Za-z0-9_.-]{0,63}`。能力、组件、格式各自 ID 唯一，每个格式内字段 ID 唯一。引用必须存在。title/label 为1～80字符非空，unit 最多16字符。能力、组件、绑定、格式各最多32项，每格式1～32字段。配置为 UTF-8 JSON，文件建议 `.thingstalk.json`；文件不超过512 KiB，解析另限制128×1024字符。BLE v3 配置不添加 transport；不要加入 model、version、description、layout 等未定义顶层键；固件版本/说明放配套协议文档，App 配置解析会拒绝未知键。

## 3. BLE 操作绑定

```json
{
  "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
  "characteristicUuid": "0000fff1-0000-1000-8000-00805f9b34fb",
  "payload": {"formatId": "status", "fieldId": "temperature"}
}
```

这里 UUID 仅为示例。配置必须写完整128位 UUID，不能写 FFF0/FFF1 短码。应用在当前关联外设的连接中寻找服务及特征；重复服务/特征 UUID 路径无法唯一定位时拒绝执行。

| 绑定 | 固件 GATT 属性 | 格式要求 |
| --- | --- | --- |
| read | READ | 返回完整报文，解析后选 fieldId |
| notify | NOTIFY 或 INDICATE | 每次回调必须是完整报文；多个能力可从同一帧取不同字段 |
| write，WITH_RESPONSE（默认） | WRITE | 接受指定报文；GATT 响应不是设备业务执行回执 |
| write，WITHOUT_RESPONSE | WRITE_WITHOUT_RESPONSE | App提交后不等于设备已执行 |

read/notify 不设置非默认 writeMode。启用 payload 引用后，操作对象内省略旧 codec/readOffset/scale/bias/byteOrder/writePrefixHex/writeSuffixHex/falseHex/trueHex，避免与格式定义重复；倍率等只写在 format.fields 中。

应用负责通过订阅接口启停通知（含 CCCD），固件需按所用 BLE 栈正确提供对应能力。描述符目前只有发现/展示，不能在面板中绑定描述符读写。设备名称和广播 UUID 可辅助扫描，但不能自动推断字节协议或作为跨平台设备身份。

连接/应用配置后会初次读取并建立已配置的通知订阅；操作写入后，如该能力还有 read，App会读回。固件状态有延迟时，应在实际执行后发送新状态通知。不要依赖组件先显示用户目标值作为成功确认。主动读取与通知不得要求未声明的特殊先后顺序。

## 4. 报文编解码

### BINARY

`prefixHex + fields 按顺序紧密排列 + suffixHex`，无隐式对齐/填充。需要保留字节时定义明确字段与写入默认值，不能依赖 C struct 内存布局。长度、帧头、帧尾和所有字段均需匹配；不支持一个通知携带多帧或将一帧分成多次通知。

| codec | 字节 | 原始值范围 |
| --- | --- | --- |
| UINT8 / INT8 | 1 | 0～255 / −128～127 |
| UINT16 / INT16 | 2 | 0～65535 / −32768～32767 |
| UINT32 / INT32 | 4 | 0～4294967295 / −2147483648～2147483647 |
| FLOAT32 | 4 | 有限 IEEE 754 单精度，存在量化误差 |
| BOOLEAN | 默认1 | falseHex / trueHex，等长、不同、各1～32字节 |

多字节数字可选大小端；工程值 = raw × scale + bias，写入逆向计算。scale 有限且非零，bias 有限；整数不可表达或溢出会拒绝，不截断。布尔仅用 true/false，不应用倍率、偏置或大端参数。HEX 不是命名报文字段类型；它仅用于旧的固定 HEX 动作。

### JSON

字段 ID 对应顶层键，值为数值/布尔，或 codec=UTF8 对应的 JSON 字符串。例如 `{"temperature":25.5,"power":true}`。必须包含所有声明字段，不能增加字段；拒绝重复键、字符串数字、null、数组、嵌套对象、非法UTF-8。JSON不使用帧头尾、大小端、scale/bias、自定义布尔字节；这些参数保持默认。整数 codec 仍限定整数与范围，FLOAT32 可用小数且需满足其数值范围；JSON输出是文本数值，不能按二进制 FLOAT32 字节理解。

### 写入与动作

属性写入时，所绑定 fieldId 使用用户输入，其他字段全部使用显式 defaultValue。defaultValue 是工程值字符串，例如 `"2"`, `"12.5"`, `"true"`，不是裸数字、裸布尔或HEX。App不会把其他组件最新值拼进命令；固件不应将模板中的默认值误当作用户对其他属性的新设置。

ACTION 使用独立能力与 BUTTON，只有 BLE write 或 MQTT publish，payload 只填 formatId，格式所有字段具有默认值；不同时设置 actionValue。动作执行后的状态反馈通过另一 NUMBER/BOOLEAN 能力的 read/notify 或 subscribe 呈现。旧的固定动作可使用 `codec:"HEX"` 与 binding.actionValue，但新硬件优先使用可解释的命名格式。

### 普通文本输入（修订 30）

TEXT_INPUT + TEXT 需要 schemaVersion ≥3，BLE 推荐 v3、MQTT v4、双连接 v6，含设置表单则 v7；需要支持修订 30 的新客户端，旧客户端无法解析新增枚举。编辑器可直接添加文本输入组件、设置 maxBytes，并配置发送绑定。输入草稿只保留在当前组件内，不写入面板配置；重组或操作失败不清空输入，发送成功也不自动清空。普通文本可能出现在 BLE/MQTT 调试日志中，不用于 API Key 或密码；凭据继续使用相应 SetupPanel。

BLE 可直接使用 `codec:"UTF8"`，发送原始 UTF-8 字节，可配置 writePrefixHex/writeSuffixHex；无自动 NUL 结尾、换行、长度字段、校验和或分包。若引用命名格式，BINARY 只允许一个 UTF8 字段，可有 prefixHex/suffixHex，禁止与其他字段混排。JSON 可以在数值/布尔字段之外增加 UTF8 字符串字段，客户端负责 JSON 转义；仍为严格平面对象。MQTT 必须引用命名报文格式和 fieldId，BINARY 用于原始 UTF-8，JSON 用于字符串字段。其他命令字段须有 defaultValue。

输入不自动 trim、不截断，拒绝空字符串、NUL、非法 Unicode 和超出 maxBytes 的文本；中文/emoji 按 UTF-8 字节计算。完整发送报文仍为1–512字节，BLE 还检查当前连接最大写入长度，超限拒绝而不拆包。文本组件仅发送，不建立文本状态订阅、不做请求与回复匹配、不自动重发；界面的完成提示只确认传输层执行完成，不证明硬件业务成功。需要显示回复或长文本分包时必须按“能力边界与扩展提示”说明当前缺口。

## 5. 长度、语义与当前边界

完整报文为1～512字节；这是应用格式上限，不保证某条 BLE 连接能一次写入512字节。实际写入还检查连接协商后的上限。当前 App 不自动分包/重组，也不计算校验和或动态长度字段。新硬件建议优先使用较短的固定帧（例如不超过20字节），再在两端实测所需 MTU/通知长度；20字节是设计建议，不是配置硬限制。

没有任意脚本、条件命令、依次多特征写入事务、请求ID与业务应答自动匹配或离线事件补齐。固件若依赖这些能力，说明缺口后另行设计；不能仅生成新 JSON 字段宣称 App 已支持。

GATT发现不携带本配置格式的自动安装约定。当前发布方式是固件配套文件→用户导入→保存→关联外设→连接。App保留具体设备关联，便携配置只描述服务/特征路径；配置格式版本与固件协议版本分别管理。

## 6. 示例的固件约定与验收

`assets/all-widgets.thingstalk.json` 使用示例服务 FFF0，状态特征 FFF1（READ/NOTIFY）、命令特征 FFF2（WRITE）、文本特征 FFF4（WRITE，原始 UTF-8，最多64字节并受连接写入长度限制）。发布到实际产品前须确认或替换这些 UUID。

- 状态：`AA + temperature INT16 LE ×0.01 + power BOOL + brightness UINT8 + 55`。
- 设置电源：`A1 02 + power BOOL + EE`；只更改电源。
- 设置亮度：`A1 03 + brightness UINT8 + EE`；只更改亮度。
- 定位动作：`A1 04 01 EE`；执行一次定位动作，duration=1 的单位由此示例约定为秒。
- 温度用 VALUE；电源用 SWITCH；亮度用 SLIDER 与 NUMBER_INPUT；定位用 BUTTON；文本发送用 TEXT_INPUT，共六种组件。
- 固件须对未知命令/长度/范围拒绝或忽略，并在配套文档说明。示例不表示某块现有硬件已经实现它。

验收至少包括：配置导入成功、初始读回、同帧多个字段通知更新、开关两个方向、亮度最小/最大与非整数拒绝、定位动作、畸形帧、断开/重连。对照 wire-vectors.json 逐字节核对；实际硬件验收由开发者执行并记录结果。

## 运行与编辑行为补充（2026-10-31，修订 3）

组件读写的等待与结果在组件内显示，同一设备的用户命令串行执行；同一能力执行中不重复接受点击，离开设备会取消尚未执行的命令。配置通过编辑器固定顶栏的“保存”应用，报文格式不变。iOS 可通过保存的系统外设 UUID 尝试找回已知设备，找不到或连接失败时仍需扫描；UUID 不是跨平台硬件身份，不改变固件协议或面板文件。

修订 4 补充：Android 也可按本机保存的设备地址直接尝试连接，无需本次扫描记录。地址变化或设备不可达时仍可能需要扫描；直接连接不要求扫描权限或旧版定位开关，蓝牙连接权限与蓝牙开启状态仍必须满足。固件报文与面板格式保持不变。

## 专用 BLE 网络配置

Muyu v1 外设在 BLE 连接后可通过专用“设备网络设置”表单输入 Wi-Fi 和硬件 MQTT 参数；它不扩展组件枚举、普通报文字符串能力或面板 schema。完整 UUID、分包、保存回执、物理窗口与加密要求见 [BLE 网络配置](ble-network-provisioning.md)。Web 不支持。需要兼容新固件和新客户端；旧版木鱼固件不会因导入面板而获得配置特征。

## v7 设置表单扩展

需要设备 Wi-Fi / MQTT 参数表单时使用 schemaVersion 7，定义和限制见 [设置面板规范](ble-network-provisioning.md)。它在 v6 分通道结构上增加 setupPanels；普通不含表单的配置继续按上文 v3/v4/v6 交付。真实输入、密码和本机连接配置不进入面板文件。

Android 文件导入不依赖文件管理器提供正确的 MIME 类型，选中文件后仍校验 UTF-8、大小及完整配置结构。文件选择返回早于本机草稿恢复时，编辑器会等待加载；恢复冲突或读取错误需先处理，不能声称已导入。导入成功仅载入草稿，仍需保存应用。

用户可从设备列表或发现页“从配置文件创建设备”，客户端先用真实解析器校验文件，按解析后的 transport 自动创建 BLE/MQTT/DUAL 设备，名称预填 title，确认后一次保存完整面板，无需先手选连接类型。声明 DUAL 但一侧尚无绑定仍保持双连接。扫描外设后的“从配置创建并关联”仅接收 BLE/DUAL；Web 会提示并将双连接配置提取为 MQTT 副本，只有蓝牙的配置会拒绝。已有设备的编辑器导入不会自动改变该设备类型，类型不符时引导创建新设备。导入创建不会自动连接或写入硬件。
