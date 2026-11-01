# ThingsTalk 硬件开发配套 Prompt

兼容快照：文档修订 34，核对日期 2026-11-01，面板版本 BLE 3 / MQTT 4 / DUAL 6，设置面板 SETUP 7。
这是一份可独立发送的 Prompt，不需要访问原 App 仓库。它描述核对时的实现，不能假设之后新增功能自动可用。

## 任务边界与兼容目标

ThingsTalk 客户端是既定的兼容目标。本任务只实现固件、设备配置、协议说明和测试向量。即使工作区包含客户端源码，也只能只读核对行为；不得修改客户端代码、测试、校验器、Skill 契约或兼容快照来适配硬件，不自动切换为客户端维护任务。

先记录目标 App 版本/构建、目标平台、固件版本和本包 contractRevision。契约修订号、schemaVersion 和 App 版本不是同一个概念；快照不证明某个已发布 App 已包含这些能力。版本未知时标注“目标版本兼容性待验证”，可以按本契约设计，但不能宣称目标 App 已验证可用。发现源码与契约冲突时报告差异，不自行刷新契约。

需求应映射到已有 widget、SetupProtocol、固定字段语义和报文规则。不得添加枚举/JSON 键、重新解释固定字段或放宽校验来绕过限制。缺口须在最终输出以“需要客户端扩展，当前不可直接使用”明确列出需求、限制、已有能力替代方案及未交付部分；仅报告扩展需求，不实施客户端扩展。可交付已支持的子集，但不能静默丢弃需求或将未支持部分混入配置。

交付配置属于硬件项目；本包规范、示例和快照是只读参考。示例 UUID/topic 不是目标设备事实。只有在目标客户端实际验证后，才报告导入和通信通过；修改另一个客户端后通过不构成目标版本兼容证明。


请为我指定的硬件能力，同时交付 BLE、MQTT 或双连接固件协议和 ThingsTalk 可导入的面板配置。按目标平台选择版本：Web 仅 MQTT 功能，推荐 v4；Android/iOS 可用 BLE v3、MQTT v4、DUAL v6（旧 v5 混合路由仍兼容）。需要 BLE 设置表单时使用 v7 setupPanels，按下文规范填写。
先区分：普通控制面板用于日常读值和操作；设置面板 setupPanels 用于文本/密码及分组提交；App 本机 MQTT 连接配置由用户在连接库管理。
普通组件与设置表单可在同一份设备 JSON、同一个展示页共存。不要生成 normalPanels、widget.type=SETUP 或 MQTT 设置提交等未实现结构/能力。
先核对目标客户端已支持的 panel/widget、设置协议、字段语义和版本，再从我提供的固件/GATT/topic/需求中核实硬件能力。硬件实现客户端标准；输出选用的组件/协议与硬件能力映射，区分事实、拟议设计和待确认事项。
硬件接入必须使用下文已实现的 panel/widget 与设置协议。若需求需要新面板能力，严格执行“能力边界与扩展提示”：在最终输出正文明确提示“需要客户端扩展，当前不可直接使用”，说明缺口、替代方案及扩展内容；不将未支持部分混入可导入 JSON，也不静默省略需求。
按下面的当前规范选择组件，产出完整的 `设备名.thingstalk.json`、能力到 GATT/topic/字段与双连接路由的映射表、固件收发说明和字节测试向量。
配置中的 UUID、topic 与命令值必须与我的固件一致；下面示例只用于示范结构，不能当成我的实际设备。
缺少关键协议时提出具体问题或标明待确认设计；不虚构App支持的组件/字段，不把未执行的测试说成通过。
若正在开发新固件，可同时定义固件和配置；若适配既有固件，应保留其协议语义并指出不兼容项。
面板配置作为固件发布附件交付，由用户导入、保存并关联设备；当前不支持BLE自动下载/安装面板。
新需求若超出规范，明确列出需要的固件适配或App扩展，不通过自造JSON键绕过。
MQTT 另交付统一 WS/WSS 的 Broker 部署及本机连接配置说明；地址、账号密码、Client ID 和配置 ID 不放进面板文件。

我的硬件资料（由使用者补充）：目标 App 版本/构建（未知则标注待验证）、芯片/SDK、目标平台、已有 GATT/topic 与协议、Broker WS/WSS 入口与认证要求（不提供真实密码）、需要展示/控制的能力、单位/范围/精度、通知时机、固件版本；是否需要设备设置表单、新接入按 BLE_CHUNKED_SETTINGS_V2 / BLE_AI_SETTINGS_V2 实现统一状态，维护现有 V1 固件则保留对应 V1 声明；不得只改枚举升级固件。固件协议版本、设置窗口/字段限制/配对要求。

---

# 当前硬件接入契约

此规范描述当前实现，不是未来路线图。设置协议新接入使用 V2 的统一状态位图，既有 V1 配置及固件仍兼容；省略 protocol 继续默认网络 V1。V2 需要支持它的新客户端构建，不由 schemaVersion=7 或本包修订号推断。兼容快照版本与核对日期见 compatibility.json。

应用内置界面支持简体中文和英文，可跟随系统或手动选择。导入配置中的 `title`、`label`、说明、单位及用户填写内容保持原文，不随应用语言自动翻译。请按硬件用户的目标语言填写这些文案；当前没有 `localizedTitles` 等多语言 JSON 字段。UUID、topic、字段 ID、枚举值和二进制/JSON 报文格式不参与翻译，不因应用语言变化而改变。为其他语言交付配置时只调整显示文案，不修改协议标识或编码。

## 0. 配置层次与版本选择

### 能力边界与扩展提示

硬件接入任务只实现固件与配套配置，不修改 ThingsTalk 客户端或本契约。硬件差异放在配置及示例中；缺少客户端能力时报告缺口，不自行添加协议适配器或改变校验。

硬件和配套配置必须从当前已实现的面板能力出发：先将每项需求映射到下表的 widget，或设置表单规范中已支持的 SetupProtocol、section/field ID 及其固定语义，再设计固件收发。允许修改实例标题、UUID、topic、受支持的报文格式，或组合现有组件生成新设备面板；这不等于新增客户端面板类型。不得仅因 JSON 可表达就宣称客户端支持。

需要新的 widget、设置协议、字段语义、codec 或交互流程时，AI 必须在面向硬件开发者的最终输出正文中给出以下提示，不能只写在文件注释、内部推理或测试日志中：

> **需要客户端扩展，当前不可直接使用**：需求是……；当前面板不能支持的部分是……；可用现有能力实现的替代方案及限制是……（没有则明确说明）；需要 ThingsTalk 增加……并与固件约定……。

可交付已支持部分的配置，但必须明确列出未包含的需求及影响；不得静默删减需求、自造 protocol/widget/字段混入 `.thingstalk.json`，或把 Wi-Fi/MQTT 等固定设置字段重新解释为其他用途。扩展草案只放协议设计说明，明确标注“提案，尚未支持”，不作为可导入配置交付。只有客户端维护者另行发布实现、验证并更新契约后，硬件接入任务才可将扩展列为已支持；并提醒硬件开发者需要对应的新客户端版本，旧部署不会因 Skill 更新自动获得支持。

App 导入的是 `设备名.thingstalk.json` 面板配置，不是协议测试向量文件。含 `configuration` 文件名引用和 `valid`/`invalid` 用例的 JSON（例如 `wire-vectors.json`）供开发与测试使用；应选择 `configuration` 指向的配置文件导入。客户端不会自动加载该引用，也不会忽略未知字段来尝试导入测试文件。

| 概念 | 配置位置 | 用途与边界 |
| --- | --- | --- |
| 普通控制面板 | capabilities / widgets / payloadFormats + 通道绑定 | 日常状态展示和操作；BLE read/write/notify 或 MQTT publish/subscribe |
| 设置面板 SetupPanel | 顶层 setupPanels 数组（v7） | 文本/密码表单、字段校验、按组提交；BLE_CHUNKED_SETTINGS_V2 网络设置或 BLE_AI_SETTINGS_V2 AI 设置 |
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

## 配置驱动的 BLE 设置

客户端仅通过导入的 v7 setupPanels 创建设置表单：设备名称、提示文案、服务/特征 UUID、字段长度和可选默认值来自 JSON，执行由 BleSetupPanelClient 和已实现的协议适配规则负责。不按厂商、型号、广播名或固定 UUID 插入业务页面。木鱼和 AI 鲸鱼配置仅为硬件接入示例，不是客户端内置设备类型。

日志脱敏路径取自 SetupPanel 的命令及读取特征，并在当前诊断会话中保留已标记路径；不再按某款设备的 UUID 猜测敏感数据。必须先导入完整设置配置，再通过设置面板读写凭据；没有配置的原始 GATT 调试无法自动判断哪些特征包含秘密。通用设置的分包、保存回执、物理窗口与加密要求见 [BLE 设置规范](#v7-可配置设备设置面板)。Web 不执行 BLE 设置。

## v7 设置表单扩展

需要设备 Wi-Fi / MQTT 参数表单时使用 schemaVersion 7，定义和限制见 [设置面板规范](#v7-可配置设备设置面板)。它在 v6 分通道结构上增加 setupPanels；普通不含表单的配置继续按上文 v3/v4/v6 交付。真实输入、密码和本机连接配置不进入面板文件。

Android 文件导入不依赖文件管理器提供正确的 MIME 类型，选中文件后仍校验 UTF-8、大小及完整配置结构。文件选择返回早于本机草稿恢复时，编辑器会等待加载；恢复冲突或读取错误需先处理，不能声称已导入。导入成功仅载入草稿，仍需保存应用。

用户可从设备列表或发现页“从配置文件创建设备”，客户端先用真实解析器校验文件，按解析后的 transport 自动创建 BLE/MQTT/DUAL 设备，名称预填 title，确认后一次保存完整面板，无需先手选连接类型。声明 DUAL 但一侧尚无绑定仍保持双连接。扫描外设后的“从配置创建并关联”仅接收 BLE/DUAL；Web 会提示并将双连接配置提取为 MQTT 副本，只有蓝牙的配置会拒绝。已有设备的编辑器导入不会自动改变该设备类型，类型不符时引导创建新设备。导入创建不会自动连接或写入硬件。


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


## BLE 六组件完整可导入示例

```json
{
  "schemaVersion": 3,
  "title": "温度、电源与亮度",
  "payloadFormats": [
    {
      "id": "status",
      "label": "设备状态报文",
      "encoding": "BINARY",
      "prefixHex": "AA",
      "suffixHex": "55",
      "fields": [
        {
          "id": "temperature",
          "codec": "INT16",
          "byteOrder": "LITTLE_ENDIAN",
          "scale": 0.01
        },
        {
          "id": "power",
          "codec": "BOOLEAN"
        },
        {
          "id": "brightness",
          "codec": "UINT8"
        }
      ]
    },
    {
      "id": "setPower",
      "label": "电源控制报文",
      "encoding": "BINARY",
      "prefixHex": "A1",
      "suffixHex": "EE",
      "fields": [
        {
          "id": "command",
          "codec": "UINT8",
          "defaultValue": "2"
        },
        {
          "id": "power",
          "codec": "BOOLEAN"
        }
      ]
    },
    {
      "id": "setBrightness",
      "label": "亮度控制",
      "encoding": "BINARY",
      "prefixHex": "A1",
      "suffixHex": "EE",
      "fields": [
        {
          "id": "command",
          "codec": "UINT8",
          "defaultValue": "3"
        },
        {
          "id": "brightness",
          "codec": "UINT8"
        }
      ]
    },
    {
      "id": "locate",
      "label": "定位动作",
      "encoding": "BINARY",
      "prefixHex": "A1",
      "suffixHex": "EE",
      "fields": [
        {
          "id": "command",
          "codec": "UINT8",
          "defaultValue": "4"
        },
        {
          "id": "duration",
          "codec": "UINT8",
          "defaultValue": "1"
        }
      ]
    }
  ],
  "capabilities": [
    {
      "id": "temperature",
      "label": "温度",
      "type": "NUMBER",
      "minimum": -100,
      "maximum": 200,
      "unit": "℃"
    },
    {
      "id": "power",
      "label": "电源",
      "type": "BOOLEAN"
    },
    {
      "id": "brightness",
      "label": "亮度",
      "type": "NUMBER",
      "minimum": 0,
      "maximum": 100,
      "step": 1,
      "unit": "%"
    },
    {
      "id": "locate",
      "label": "定位一次",
      "type": "ACTION"
    },
    {
      "id": "message",
      "label": "发送文本",
      "type": "TEXT",
      "maxBytes": 64
    }
  ],
  "bindings": [
    {
      "capabilityId": "temperature",
      "read": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff1-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "status",
          "fieldId": "temperature"
        }
      },
      "notify": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff1-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "status",
          "fieldId": "temperature"
        }
      }
    },
    {
      "capabilityId": "power",
      "read": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff1-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "status",
          "fieldId": "power"
        }
      },
      "notify": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff1-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "status",
          "fieldId": "power"
        }
      },
      "write": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff2-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "setPower",
          "fieldId": "power"
        }
      }
    },
    {
      "capabilityId": "brightness",
      "read": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff1-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "status",
          "fieldId": "brightness"
        }
      },
      "notify": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff1-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "status",
          "fieldId": "brightness"
        }
      },
      "write": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff2-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "setBrightness",
          "fieldId": "brightness"
        }
      }
    },
    {
      "capabilityId": "locate",
      "write": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff2-0000-1000-8000-00805f9b34fb",
        "payload": {
          "formatId": "locate"
        }
      }
    },
    {
      "capabilityId": "message",
      "write": {
        "serviceUuid": "0000fff0-0000-1000-8000-00805f9b34fb",
        "characteristicUuid": "0000fff4-0000-1000-8000-00805f9b34fb",
        "codec": "UTF8"
      }
    }
  ],
  "widgets": [
    {
      "id": "temperatureCard",
      "type": "VALUE",
      "label": "温度",
      "capabilityId": "temperature"
    },
    {
      "id": "powerSwitch",
      "type": "SWITCH",
      "label": "电源",
      "capabilityId": "power"
    },
    {
      "id": "brightnessSlider",
      "type": "SLIDER",
      "label": "亮度调节",
      "capabilityId": "brightness"
    },
    {
      "id": "brightnessInput",
      "type": "NUMBER_INPUT",
      "label": "输入亮度",
      "capabilityId": "brightness"
    },
    {
      "id": "locateButton",
      "type": "BUTTON",
      "label": "定位一次",
      "capabilityId": "locate"
    },
    {
      "id": "messageInput",
      "type": "TEXT_INPUT",
      "label": "发送文本",
      "capabilityId": "message"
    }
  ]
}
```

## 示例收发测试向量

以下是独立于面板文件的测试数据，不要把 valid/invalid 等测试字段加入面板配置。

```json
{
  "configuration": "all-widgets.thingstalk.json",
  "valid": [
    {
      "formatId": "status",
      "hex": "AA C4 09 01 32 55",
      "values": {
        "temperature": "25",
        "power": "true",
        "brightness": "50"
      }
    },
    {
      "formatId": "status",
      "hex": "AA 2E FB 00 00 55",
      "values": {
        "temperature": "-12.34",
        "power": "false",
        "brightness": "0"
      }
    },
    {
      "formatId": "status",
      "hex": "AA 20 4E 01 64 55",
      "values": {
        "temperature": "200",
        "power": "true",
        "brightness": "100"
      }
    },
    {
      "formatId": "setPower",
      "hex": "A1 02 01 EE",
      "values": {
        "command": "2",
        "power": "true"
      }
    },
    {
      "formatId": "setPower",
      "hex": "A1 02 00 EE",
      "values": {
        "command": "2",
        "power": "false"
      }
    },
    {
      "formatId": "setBrightness",
      "hex": "A1 03 00 EE",
      "values": {
        "command": "3",
        "brightness": "0"
      }
    },
    {
      "formatId": "setBrightness",
      "hex": "A1 03 64 EE",
      "values": {
        "command": "3",
        "brightness": "100"
      }
    },
    {
      "formatId": "locate",
      "hex": "A1 04 01 EE",
      "values": {
        "command": "4",
        "duration": "1"
      }
    }
  ],
  "invalid": [
    {
      "formatId": "status",
      "hex": "AA C4 09 01 55",
      "reason": "缺少亮度字节，整帧长度错误"
    },
    {
      "formatId": "status",
      "hex": "AB C4 09 01 32 55",
      "reason": "帧头不符"
    },
    {
      "formatId": "status",
      "hex": "AA C4 09 02 32 55",
      "reason": "未知布尔编码"
    },
    {
      "formatId": "setBrightness",
      "values": {
        "command": "3",
        "brightness": "12.5"
      },
      "reason": "UINT8不能表达小数"
    }
  ]
}
```

## MQTT 六组件完整示例

```json
{
  "schemaVersion": 4,
  "transport": "MQTT",
  "title": "MQTT 温度与灯光",
  "capabilities": [
    {
      "id": "temperature",
      "label": "温度",
      "type": "NUMBER",
      "minimum": -100,
      "maximum": 200,
      "unit": "℃"
    },
    {
      "id": "power",
      "label": "电源",
      "type": "BOOLEAN"
    },
    {
      "id": "brightness",
      "label": "亮度",
      "type": "NUMBER",
      "minimum": 0,
      "maximum": 100,
      "step": 1,
      "unit": "%"
    },
    {
      "id": "locate",
      "label": "定位一次",
      "type": "ACTION"
    },
    {
      "id": "message",
      "label": "发送文本",
      "type": "TEXT",
      "maxBytes": 64
    }
  ],
  "widgets": [
    {
      "id": "temperatureCard",
      "type": "VALUE",
      "label": "温度",
      "capabilityId": "temperature"
    },
    {
      "id": "powerSwitch",
      "type": "SWITCH",
      "label": "电源",
      "capabilityId": "power"
    },
    {
      "id": "brightnessSlider",
      "type": "SLIDER",
      "label": "亮度调节",
      "capabilityId": "brightness"
    },
    {
      "id": "brightnessInput",
      "type": "NUMBER_INPUT",
      "label": "输入亮度",
      "capabilityId": "brightness"
    },
    {
      "id": "locateButton",
      "type": "BUTTON",
      "label": "定位一次",
      "capabilityId": "locate"
    },
    {
      "id": "messageInput",
      "type": "TEXT_INPUT",
      "label": "发送文本",
      "capabilityId": "message"
    }
  ],
  "payloadFormats": [
    {
      "id": "state",
      "label": "设备状态",
      "encoding": "JSON",
      "fields": [
        {
          "id": "temperature",
          "codec": "FLOAT32"
        },
        {
          "id": "power",
          "codec": "BOOLEAN"
        },
        {
          "id": "brightness",
          "codec": "UINT8"
        }
      ]
    },
    {
      "id": "powerCommand",
      "label": "电源命令",
      "encoding": "JSON",
      "fields": [
        {
          "id": "power",
          "codec": "BOOLEAN"
        }
      ]
    },
    {
      "id": "brightnessCommand",
      "label": "亮度命令",
      "encoding": "JSON",
      "fields": [
        {
          "id": "brightness",
          "codec": "UINT8"
        }
      ]
    },
    {
      "id": "locateCommand",
      "label": "定位一次命令",
      "encoding": "JSON",
      "fields": [
        {
          "id": "locate",
          "codec": "BOOLEAN",
          "defaultValue": "true"
        }
      ]
    },
    {
      "id": "messageCommand",
      "label": "文本命令",
      "encoding": "JSON",
      "fields": [
        {
          "id": "text",
          "codec": "UTF8"
        }
      ]
    }
  ],
  "bindings": [
    {
      "capabilityId": "temperature",
      "subscribe": {
        "topic": "thingstalk/demo/state",
        "qos": 1,
        "payload": {
          "formatId": "state",
          "fieldId": "temperature"
        }
      }
    },
    {
      "capabilityId": "power",
      "subscribe": {
        "topic": "thingstalk/demo/state",
        "qos": 1,
        "payload": {
          "formatId": "state",
          "fieldId": "power"
        }
      },
      "publish": {
        "topic": "thingstalk/demo/set/power",
        "qos": 1,
        "payload": {
          "formatId": "powerCommand",
          "fieldId": "power"
        }
      }
    },
    {
      "capabilityId": "brightness",
      "subscribe": {
        "topic": "thingstalk/demo/state",
        "qos": 1,
        "payload": {
          "formatId": "state",
          "fieldId": "brightness"
        }
      },
      "publish": {
        "topic": "thingstalk/demo/set/brightness",
        "qos": 1,
        "payload": {
          "formatId": "brightnessCommand",
          "fieldId": "brightness"
        }
      }
    },
    {
      "capabilityId": "locate",
      "publish": {
        "topic": "thingstalk/demo/set/locate",
        "qos": 1,
        "payload": {
          "formatId": "locateCommand"
        }
      }
    },
    {
      "capabilityId": "message",
      "publish": {
        "topic": "thingstalk/lamp-01/message",
        "payload": {
          "formatId": "messageCommand",
          "fieldId": "text"
        }
      }
    }
  ]
}
```

## 旧 v5 混合路由示例

```json
{
  "schemaVersion": 5,
  "transport": "DUAL",
  "title": "双连接电源",
  "capabilities": [
    {
      "id": "power",
      "label": "电源",
      "type": "BOOLEAN"
    }
  ],
  "widgets": [
    {
      "id": "powerSwitch",
      "type": "SWITCH",
      "label": "电源",
      "capabilityId": "power"
    }
  ],
  "payloadFormats": [
    {
      "id": "blePower",
      "label": "BLE 电源状态与命令",
      "encoding": "BINARY",
      "fields": [
        {
          "id": "power",
          "codec": "BOOLEAN"
        }
      ]
    },
    {
      "id": "mqttPower",
      "label": "MQTT 电源状态与命令",
      "encoding": "JSON",
      "fields": [
        {
          "id": "power",
          "codec": "BOOLEAN"
        }
      ]
    }
  ],
  "bindings": [
    {
      "capabilityId": "power",
      "controlTransport": "BLE",
      "stateTransport": "MQTT",
      "read": {
        "serviceUuid": "edb10000-7d17-4c5b-bb5a-2c78b0a10001",
        "characteristicUuid": "edb10001-7d17-4c5b-bb5a-2c78b0a10001",
        "payload": {
          "formatId": "blePower",
          "fieldId": "power"
        }
      },
      "write": {
        "serviceUuid": "edb10000-7d17-4c5b-bb5a-2c78b0a10001",
        "characteristicUuid": "edb10001-7d17-4c5b-bb5a-2c78b0a10001",
        "payload": {
          "formatId": "blePower",
          "fieldId": "power"
        }
      },
      "notify": {
        "serviceUuid": "edb10000-7d17-4c5b-bb5a-2c78b0a10001",
        "characteristicUuid": "edb10001-7d17-4c5b-bb5a-2c78b0a10001",
        "payload": {
          "formatId": "blePower",
          "fieldId": "power"
        }
      },
      "publish": {
        "topic": "devices/lamp-01/set",
        "payload": {
          "formatId": "mqttPower",
          "fieldId": "power"
        },
        "qos": 1
      },
      "subscribe": {
        "topic": "devices/lamp-01/state",
        "payload": {
          "formatId": "mqttPower",
          "fieldId": "power"
        },
        "qos": 1
      }
    }
  ]
}
```

## v6 按通道定义完整示例

```json
{
  "schemaVersion": 6,
  "transport": "DUAL",
  "title": "双连接电源",
  "capabilities": [
    {
      "id": "power",
      "label": "电源",
      "type": "BOOLEAN"
    }
  ],
  "widgets": [
    {
      "id": "powerSwitch",
      "type": "SWITCH",
      "label": "电源",
      "capabilityId": "power"
    }
  ],
  "payloadFormats": [
    {
      "id": "blePower",
      "label": "BLE 电源状态与命令",
      "encoding": "BINARY",
      "fields": [
        {
          "id": "power",
          "codec": "BOOLEAN"
        }
      ]
    },
    {
      "id": "mqttPower",
      "label": "MQTT 电源状态与命令",
      "encoding": "JSON",
      "fields": [
        {
          "id": "power",
          "codec": "BOOLEAN"
        }
      ]
    }
  ],
  "defaultChannel": "BLE",
  "channels": {
    "ble": {
      "bindings": [
        {
          "capabilityId": "power",
          "read": {
            "serviceUuid": "edb10000-7d17-4c5b-bb5a-2c78b0a10001",
            "characteristicUuid": "edb10001-7d17-4c5b-bb5a-2c78b0a10001",
            "payload": {
              "formatId": "blePower",
              "fieldId": "power"
            }
          },
          "write": {
            "serviceUuid": "edb10000-7d17-4c5b-bb5a-2c78b0a10001",
            "characteristicUuid": "edb10001-7d17-4c5b-bb5a-2c78b0a10001",
            "payload": {
              "formatId": "blePower",
              "fieldId": "power"
            }
          },
          "notify": {
            "serviceUuid": "edb10000-7d17-4c5b-bb5a-2c78b0a10001",
            "characteristicUuid": "edb10001-7d17-4c5b-bb5a-2c78b0a10001",
            "payload": {
              "formatId": "blePower",
              "fieldId": "power"
            }
          }
        }
      ]
    },
    "mqtt": {
      "bindings": [
        {
          "capabilityId": "power",
          "publish": {
            "topic": "devices/lamp-01/set",
            "payload": {
              "formatId": "mqttPower",
              "fieldId": "power"
            },
            "qos": 1
          },
          "subscribe": {
            "topic": "devices/lamp-01/state",
            "payload": {
              "formatId": "mqttPower",
              "fieldId": "power"
            },
            "qos": 1
          }
        }
      ]
    }
  }
}
```

## v7 设置表单示例

```json
{
  "schemaVersion": 7,
  "title": "设备网络设置示例",
  "transport": "BLE",
  "channels": {
    "ble": {
      "bindings": []
    },
    "mqtt": {
      "bindings": []
    }
  },
  "setupPanels": [
    {
      "id": "networkSetup",
      "title": "设备网络设置",
      "instructions": "长按设备 UP 键开启设置窗口，然后点击准备设备设置；如系统提示配对，请完成配对。读取成功后填写，保存后请检查设备网络状态。",
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "serviceUuid": "5c52a001-7d8a-4b19-9c63-414950415353",
      "commandUuid": "5c52a005-7d8a-4b19-9c63-414950415353",
      "statusUuid": "5c52a006-7d8a-4b19-9c63-414950415353",
      "sections": [
        {
          "id": 1,
          "title": "Wi-Fi",
          "fields": [
            {
              "id": 1,
              "label": "SSID",
              "maxBytes": 32,
              "minBytes": 1,
              "readUuid": "5c52a007-7d8a-4b19-9c63-414950415353"
            },
            {
              "id": 2,
              "label": "Wi-Fi 密码",
              "maxBytes": 64,
              "secret": true,
              "rule": "WIFI_PSK"
            }
          ]
        },
        {
          "id": 2,
          "title": "MQTT",
          "fields": [
            {
              "id": 3,
              "label": "Broker URI",
              "maxBytes": 192,
              "minBytes": 1,
              "readUuid": "5c52a008-7d8a-4b19-9c63-414950415353",
              "rule": "MQTT_URI"
            },
            {
              "id": 4,
              "label": "用户名",
              "maxBytes": 64,
              "readUuid": "5c52a009-7d8a-4b19-9c63-414950415353"
            },
            {
              "id": 5,
              "label": "MQTT 密码",
              "maxBytes": 128,
              "secret": true
            },
            {
              "id": 6,
              "label": "设备名称",
              "maxBytes": 32,
              "minBytes": 1,
              "readUuid": "5c52a00a-7d8a-4b19-9c63-414950415353",
              "rule": "IDENTIFIER",
              "defaultValue": "muyu-01"
            }
          ]
        }
      ]
    }
  ]
}
```

## v7 AI 设置表单示例

```json
{
  "schemaVersion": 7,
  "title": "蓝色大肥鱼",
  "transport": "DUAL",
  "defaultChannel": "BLE",
  "payloadFormats": [
    {
      "id": "status",
      "label": "蓝色大肥鱼状态",
      "prefixHex": "AA",
      "suffixHex": "55",
      "fields": [
        {
          "id": "request",
          "codec": "UINT32"
        },
        {
          "id": "busy",
          "codec": "BOOLEAN"
        },
        {
          "id": "mood",
          "codec": "UINT8"
        },
        {
          "id": "contextBytes",
          "codec": "UINT16"
        },
        {
          "id": "error",
          "codec": "UINT8"
        },
        {
          "id": "battery",
          "codec": "UINT8"
        },
        {
          "id": "batteryValid",
          "codec": "BOOLEAN"
        }
      ]
    },
    {
      "id": "brightness",
      "label": "屏幕亮度",
      "fields": [
        {
          "id": "brightness",
          "codec": "UINT8"
        }
      ]
    },
    {
      "id": "network",
      "label": "网络连接",
      "fields": [
        {
          "id": "wifi",
          "codec": "BOOLEAN"
        },
        {
          "id": "mqtt",
          "codec": "BOOLEAN"
        }
      ]
    },
    {
      "id": "mqttState",
      "label": "MQTT 状态",
      "encoding": "JSON",
      "fields": [
        {
          "id": "request",
          "codec": "UINT32"
        },
        {
          "id": "busy",
          "codec": "BOOLEAN"
        },
        {
          "id": "mood",
          "codec": "UINT8"
        },
        {
          "id": "contextBytes",
          "codec": "UINT16"
        },
        {
          "id": "error",
          "codec": "UINT8"
        },
        {
          "id": "battery",
          "codec": "UINT8"
        },
        {
          "id": "batteryValid",
          "codec": "BOOLEAN"
        },
        {
          "id": "brightness",
          "codec": "UINT8"
        }
      ]
    },
    {
      "id": "greetCommand",
      "label": "打个招呼",
      "prefixHex": "A2",
      "fields": [
        {
          "id": "opcode",
          "codec": "UINT8",
          "defaultValue": "1"
        },
        {
          "id": "reserved",
          "codec": "UINT16",
          "defaultValue": "0"
        }
      ]
    },
    {
      "id": "greetMqtt",
      "label": "打个招呼 MQTT",
      "encoding": "JSON",
      "fields": [
        {
          "id": "greet",
          "codec": "BOOLEAN",
          "defaultValue": "true"
        }
      ]
    },
    {
      "id": "jokeCommand",
      "label": "讲个笑话",
      "prefixHex": "A2",
      "fields": [
        {
          "id": "opcode",
          "codec": "UINT8",
          "defaultValue": "2"
        },
        {
          "id": "reserved",
          "codec": "UINT16",
          "defaultValue": "0"
        }
      ]
    },
    {
      "id": "jokeMqtt",
      "label": "讲个笑话 MQTT",
      "encoding": "JSON",
      "fields": [
        {
          "id": "joke",
          "codec": "BOOLEAN",
          "defaultValue": "true"
        }
      ]
    },
    {
      "id": "encourageCommand",
      "label": "鼓励我",
      "prefixHex": "A2",
      "fields": [
        {
          "id": "opcode",
          "codec": "UINT8",
          "defaultValue": "3"
        },
        {
          "id": "reserved",
          "codec": "UINT16",
          "defaultValue": "0"
        }
      ]
    },
    {
      "id": "encourageMqtt",
      "label": "鼓励我 MQTT",
      "encoding": "JSON",
      "fields": [
        {
          "id": "encourage",
          "codec": "BOOLEAN",
          "defaultValue": "true"
        }
      ]
    },
    {
      "id": "continueCommand",
      "label": "继续聊",
      "prefixHex": "A2",
      "fields": [
        {
          "id": "opcode",
          "codec": "UINT8",
          "defaultValue": "4"
        },
        {
          "id": "reserved",
          "codec": "UINT16",
          "defaultValue": "0"
        }
      ]
    },
    {
      "id": "continueMqtt",
      "label": "继续聊 MQTT",
      "encoding": "JSON",
      "fields": [
        {
          "id": "continue",
          "codec": "BOOLEAN",
          "defaultValue": "true"
        }
      ]
    },
    {
      "id": "compressCommand",
      "label": "整理记忆",
      "prefixHex": "A2",
      "fields": [
        {
          "id": "opcode",
          "codec": "UINT8",
          "defaultValue": "5"
        },
        {
          "id": "reserved",
          "codec": "UINT16",
          "defaultValue": "0"
        }
      ]
    },
    {
      "id": "compressMqtt",
      "label": "整理记忆 MQTT",
      "encoding": "JSON",
      "fields": [
        {
          "id": "compress",
          "codec": "BOOLEAN",
          "defaultValue": "true"
        }
      ]
    },
    {
      "id": "forgetCommand",
      "label": "清空记忆",
      "prefixHex": "A2",
      "fields": [
        {
          "id": "opcode",
          "codec": "UINT8",
          "defaultValue": "6"
        },
        {
          "id": "reserved",
          "codec": "UINT16",
          "defaultValue": "0"
        }
      ]
    },
    {
      "id": "forgetMqtt",
      "label": "清空记忆 MQTT",
      "encoding": "JSON",
      "fields": [
        {
          "id": "forget",
          "codec": "BOOLEAN",
          "defaultValue": "true"
        }
      ]
    }
  ],
  "capabilities": [
    {
      "id": "request",
      "label": "请求编号",
      "type": "NUMBER",
      "minimum": 0,
      "maximum": 4294967295,
      "step": 1
    },
    {
      "id": "busy",
      "label": "正在思考",
      "type": "BOOLEAN"
    },
    {
      "id": "contextBytes",
      "label": "记忆占用",
      "type": "NUMBER",
      "minimum": 0,
      "maximum": 10000,
      "step": 1,
      "unit": "B"
    },
    {
      "id": "error",
      "label": "请求错误（0 正常）",
      "type": "NUMBER",
      "minimum": 0,
      "maximum": 7,
      "step": 1
    },
    {
      "id": "battery",
      "label": "电量",
      "type": "NUMBER",
      "minimum": 0,
      "maximum": 100,
      "step": 1
    },
    {
      "id": "brightness",
      "label": "屏幕亮度",
      "type": "NUMBER",
      "minimum": 10,
      "maximum": 100,
      "step": 1
    },
    {
      "id": "wifi",
      "label": "Wi-Fi 已连接",
      "type": "BOOLEAN"
    },
    {
      "id": "mqtt",
      "label": "MQTT 已就绪",
      "type": "BOOLEAN"
    },
    {
      "id": "greet",
      "label": "打个招呼",
      "type": "ACTION"
    },
    {
      "id": "joke",
      "label": "讲个笑话",
      "type": "ACTION"
    },
    {
      "id": "encourage",
      "label": "鼓励我",
      "type": "ACTION"
    },
    {
      "id": "continue",
      "label": "继续聊",
      "type": "ACTION"
    },
    {
      "id": "compress",
      "label": "整理记忆",
      "type": "ACTION"
    },
    {
      "id": "forget",
      "label": "清空记忆",
      "type": "ACTION"
    }
  ],
  "widgets": [
    {
      "id": "requestCard",
      "label": "请求编号",
      "type": "VALUE",
      "capabilityId": "request"
    },
    {
      "id": "busyCard",
      "label": "正在思考",
      "type": "VALUE",
      "capabilityId": "busy"
    },
    {
      "id": "contextBytesCard",
      "label": "记忆占用",
      "type": "VALUE",
      "capabilityId": "contextBytes"
    },
    {
      "id": "errorCard",
      "label": "请求错误（0 正常）",
      "type": "VALUE",
      "capabilityId": "error"
    },
    {
      "id": "batteryCard",
      "label": "电量",
      "type": "VALUE",
      "capabilityId": "battery"
    },
    {
      "id": "brightnessCard",
      "label": "屏幕亮度",
      "type": "SLIDER",
      "capabilityId": "brightness"
    },
    {
      "id": "wifiCard",
      "label": "Wi-Fi 已连接",
      "type": "VALUE",
      "capabilityId": "wifi"
    },
    {
      "id": "mqttCard",
      "label": "MQTT 已就绪",
      "type": "VALUE",
      "capabilityId": "mqtt"
    },
    {
      "id": "greetButton",
      "label": "打个招呼",
      "type": "BUTTON",
      "capabilityId": "greet"
    },
    {
      "id": "jokeButton",
      "label": "讲个笑话",
      "type": "BUTTON",
      "capabilityId": "joke"
    },
    {
      "id": "encourageButton",
      "label": "鼓励我",
      "type": "BUTTON",
      "capabilityId": "encourage"
    },
    {
      "id": "continueButton",
      "label": "继续聊",
      "type": "BUTTON",
      "capabilityId": "continue"
    },
    {
      "id": "compressButton",
      "label": "整理记忆",
      "type": "BUTTON",
      "capabilityId": "compress"
    },
    {
      "id": "forgetButton",
      "label": "清空记忆",
      "type": "BUTTON",
      "capabilityId": "forget"
    }
  ],
  "channels": {
    "ble": {
      "bindings": [
        {
          "capabilityId": "request",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "request"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "request"
            }
          }
        },
        {
          "capabilityId": "busy",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "busy"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "busy"
            }
          }
        },
        {
          "capabilityId": "contextBytes",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "contextBytes"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "contextBytes"
            }
          }
        },
        {
          "capabilityId": "error",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "error"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "error"
            }
          }
        },
        {
          "capabilityId": "battery",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "battery"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a003-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "status",
              "fieldId": "battery"
            }
          }
        },
        {
          "capabilityId": "brightness",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a002-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "brightness",
              "fieldId": "brightness"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a002-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "brightness",
              "fieldId": "brightness"
            }
          },
          "write": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a002-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "brightness",
              "fieldId": "brightness"
            }
          }
        },
        {
          "capabilityId": "wifi",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a00b-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "network",
              "fieldId": "wifi"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a00b-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "network",
              "fieldId": "wifi"
            }
          }
        },
        {
          "capabilityId": "mqtt",
          "read": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a00b-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "network",
              "fieldId": "mqtt"
            }
          },
          "notify": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a00b-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "network",
              "fieldId": "mqtt"
            }
          }
        },
        {
          "capabilityId": "greet",
          "write": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a004-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "greetCommand"
            }
          }
        },
        {
          "capabilityId": "joke",
          "write": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a004-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "jokeCommand"
            }
          }
        },
        {
          "capabilityId": "encourage",
          "write": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a004-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "encourageCommand"
            }
          }
        },
        {
          "capabilityId": "continue",
          "write": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a004-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "continueCommand"
            }
          }
        },
        {
          "capabilityId": "compress",
          "write": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a004-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "compressCommand"
            }
          }
        },
        {
          "capabilityId": "forget",
          "write": {
            "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
            "characteristicUuid": "5c53a004-7d8a-4b19-9c63-414950415353",
            "payload": {
              "formatId": "forgetCommand"
            }
          }
        }
      ]
    },
    "mqtt": {
      "bindings": [
        {
          "capabilityId": "request",
          "subscribe": {
            "topic": "thingstalk/whale/whale-01/state",
            "qos": 1,
            "payload": {
              "formatId": "mqttState",
              "fieldId": "request"
            }
          }
        },
        {
          "capabilityId": "busy",
          "subscribe": {
            "topic": "thingstalk/whale/whale-01/state",
            "qos": 1,
            "payload": {
              "formatId": "mqttState",
              "fieldId": "busy"
            }
          }
        },
        {
          "capabilityId": "contextBytes",
          "subscribe": {
            "topic": "thingstalk/whale/whale-01/state",
            "qos": 1,
            "payload": {
              "formatId": "mqttState",
              "fieldId": "contextBytes"
            }
          }
        },
        {
          "capabilityId": "error",
          "subscribe": {
            "topic": "thingstalk/whale/whale-01/state",
            "qos": 1,
            "payload": {
              "formatId": "mqttState",
              "fieldId": "error"
            }
          }
        },
        {
          "capabilityId": "battery",
          "subscribe": {
            "topic": "thingstalk/whale/whale-01/state",
            "qos": 1,
            "payload": {
              "formatId": "mqttState",
              "fieldId": "battery"
            }
          }
        },
        {
          "capabilityId": "greet",
          "publish": {
            "topic": "thingstalk/whale/whale-01/action/greet",
            "qos": 0,
            "retain": false,
            "payload": {
              "formatId": "greetMqtt"
            }
          }
        },
        {
          "capabilityId": "joke",
          "publish": {
            "topic": "thingstalk/whale/whale-01/action/joke",
            "qos": 0,
            "retain": false,
            "payload": {
              "formatId": "jokeMqtt"
            }
          }
        },
        {
          "capabilityId": "encourage",
          "publish": {
            "topic": "thingstalk/whale/whale-01/action/encourage",
            "qos": 0,
            "retain": false,
            "payload": {
              "formatId": "encourageMqtt"
            }
          }
        },
        {
          "capabilityId": "continue",
          "publish": {
            "topic": "thingstalk/whale/whale-01/action/continue",
            "qos": 0,
            "retain": false,
            "payload": {
              "formatId": "continueMqtt"
            }
          }
        },
        {
          "capabilityId": "compress",
          "publish": {
            "topic": "thingstalk/whale/whale-01/action/compress",
            "qos": 0,
            "retain": false,
            "payload": {
              "formatId": "compressMqtt"
            }
          }
        },
        {
          "capabilityId": "forget",
          "publish": {
            "topic": "thingstalk/whale/whale-01/action/forget",
            "qos": 0,
            "retain": false,
            "payload": {
              "formatId": "forgetMqtt"
            }
          }
        }
      ]
    }
  },
  "setupPanels": [
    {
      "id": "wifiSetup",
      "title": "Wi-Fi 设置",
      "instructions": "长按设备 UP 键开启设置窗口，完成蓝牙配对后读取配置。保存后请检查设备网络状态。",
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
      "commandUuid": "5c53a005-7d8a-4b19-9c63-414950415353",
      "statusUuid": "5c53a006-7d8a-4b19-9c63-414950415353",
      "sections": [
        {
          "id": 1,
          "title": "Wi-Fi",
          "fields": [
            {
              "id": 1,
              "label": "SSID",
              "maxBytes": 32,
              "minBytes": 1,
              "readUuid": "5c53a007-7d8a-4b19-9c63-414950415353"
            },
            {
              "id": 2,
              "label": "Wi-Fi 密码",
              "maxBytes": 64,
              "secret": true,
              "rule": "WIFI_PSK"
            }
          ]
        }
      ]
    },
    {
      "id": "mqttSetup",
      "title": "MQTT 设置",
      "instructions": "长按设备 UP 键开启设置窗口，完成蓝牙配对后读取配置。保存后请检查设备网络状态。",
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
      "commandUuid": "5c53a005-7d8a-4b19-9c63-414950415353",
      "statusUuid": "5c53a006-7d8a-4b19-9c63-414950415353",
      "sections": [
        {
          "id": 2,
          "title": "MQTT",
          "fields": [
            {
              "id": 3,
              "label": "Broker URI",
              "maxBytes": 192,
              "minBytes": 1,
              "readUuid": "5c53a008-7d8a-4b19-9c63-414950415353",
              "rule": "MQTT_URI"
            },
            {
              "id": 4,
              "label": "用户名",
              "maxBytes": 64,
              "readUuid": "5c53a009-7d8a-4b19-9c63-414950415353"
            },
            {
              "id": 5,
              "label": "MQTT 密码",
              "maxBytes": 128,
              "secret": true
            },
            {
              "id": 6,
              "label": "设备名称",
              "maxBytes": 32,
              "minBytes": 1,
              "readUuid": "5c53a00a-7d8a-4b19-9c63-414950415353",
              "rule": "IDENTIFIER",
              "defaultValue": "whale-01"
            }
          ]
        }
      ]
    },
    {
      "id": "aiSetup",
      "title": "DeepSeek API Key",
      "instructions": "长按硬件 UP 开启设置窗口并完成配对。只需填写 DeepSeek 官方 API Key；接口、模型由固件固定。保存后不会自动发起付费请求。",
      "protocol": "BLE_AI_SETTINGS_V2",
      "serviceUuid": "5c53a001-7d8a-4b19-9c63-414950415353",
      "commandUuid": "5c53a00c-7d8a-4b19-9c63-414950415353",
      "statusUuid": "5c53a00d-7d8a-4b19-9c63-414950415353",
      "sections": [
        {
          "id": 1,
          "title": "AI",
          "fields": [
            {
              "id": 1,
              "label": "HTTPS 完整接口地址",
              "minBytes": 1,
              "maxBytes": 192,
              "readUuid": "5c53a00e-7d8a-4b19-9c63-414950415353",
              "defaultValue": "https://api.deepseek.com/chat/completions",
              "readOnly": true
            },
            {
              "id": 2,
              "label": "API Key",
              "maxBytes": 192,
              "secret": true
            },
            {
              "id": 3,
              "label": "模型名称",
              "minBytes": 1,
              "maxBytes": 64,
              "readUuid": "5c53a00f-7d8a-4b19-9c63-414950415353",
              "defaultValue": "deepseek-flash",
              "readOnly": true
            }
          ]
        }
      ]
    }
  ]
}
```

## 设置状态协议测试向量（不可导入）

```json
{
  "valid": [
    {
      "name": "v2 unconfigured",
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "hex": "D1 02 01 00 00 00 00 00 00 00 00 00 00 00 00 00",
      "unlocked": true,
      "sections": [],
      "secrets": []
    },
    {
      "name": "v2 network saved",
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "hex": "D1 02 01 34 12 B3 02 00 00 00 04 03 02 01 03 12",
      "unlocked": true,
      "sections": [
        1,
        2
      ],
      "secrets": [
        2,
        5
      ]
    },
    {
      "name": "v2 ai saved",
      "protocol": "BLE_AI_SETTINGS_V2",
      "hex": "D1 02 01 34 12 B3 02 00 00 00 04 03 02 01 01 02",
      "unlocked": true,
      "sections": [
        1
      ],
      "secrets": [
        2
      ]
    },
    {
      "name": "v1 network",
      "protocol": "BLE_CHUNKED_SETTINGS_V1",
      "hex": "D1 01 7F 34 12 B3 02 00 00 00 04 03 02 01",
      "unlocked": true,
      "sections": [
        1,
        2
      ],
      "secrets": [
        2,
        5
      ]
    },
    {
      "name": "v1 ai",
      "protocol": "BLE_AI_SETTINGS_V1",
      "hex": "D1 01 29 34 12 B3 02 00 00 00 04 03 02 01",
      "unlocked": true,
      "sections": [
        1
      ],
      "secrets": [
        2
      ]
    }
  ],
  "invalid": [
    {
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "hex": "D1 01 79 00 00 00 00 00 00 00 00 00 00 00",
      "reason": "V1 cannot be used by V2"
    },
    {
      "protocol": "BLE_CHUNKED_SETTINGS_V1",
      "hex": "D1 02 01 00 00 00 00 00 00 00 00 00 00 00 00 00",
      "reason": "V2 cannot be used by V1"
    },
    {
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "hex": "D1 02 03 00 00 00 00 00 00 00 00 00 00 00 00 00",
      "reason": "reserved flags"
    },
    {
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "hex": "D1 02 01 00 00 00 00 00 00 00 00 00 00 00 04 00",
      "reason": "unknown section"
    },
    {
      "protocol": "BLE_CHUNKED_SETTINGS_V2",
      "hex": "D1 02 01 00 00 00 00 00 00 00 00 00 00 00 00 80",
      "reason": "unknown field"
    }
  ]
}
```
