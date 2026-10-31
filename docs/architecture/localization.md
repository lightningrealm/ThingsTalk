# 三端中英文文案

`shared/src/commonMain/localization/strings.zh.xml` 与 `strings.en.xml` 是唯一维护入口。Gradle 的 `generateTranslations` 生成类型安全的 `AppStrings`，只输出到 `shared/build/`，检查键唯一性、中英文覆盖与参数占位符一致性。新增文案需要同时提供两种语言，保留稳定键；使用 `%1$s` 等参数，不拼接可翻译的句子。

共享 `UiString` 支持同步回调及 Compose 文案，无需异步资源加载、修改系统全局 Locale 或使用 Compose 内部 API。`tr()` 读取显示层的语言快照，让消费文本的组合范围重新计算。语言偏好使用现有外观仓库持久化，领域层只定义语言枚举与解析规则，不依赖 Compose。根节点保持组合身份，不以语言作为 `key` 重建会话。

筛选与编码选择使用稳定 ID，`ChoiceLabels` 负责展示翻译。设备/面板中作者填写的文本直接展示，不能将通用 Text 组件替换成自动翻译器。协议的 decimal / HEX / JSON 编码继续使用原有区域无关逻辑。

现有仓库错误与事件摘要仍使用字符串。`DiagnosticTranslations` 是兼容适配器，只在明确的应用诊断展示位置匹配完整已知文案，保留参数和未知消息，不扫描用户内容或通信负载。不改变历史存储格式；后续新增复杂事件可逐步使用事件代码与参数模型。

Android 读取 Compose 的系统配置；iOS 前台恢复时读取 NSLocale 首选语言；Web 监听 languagechange。原生系统界面遵循平台自己的语言设置，iOS 的蓝牙权限说明另由 en/zh-Hans InfoPlist.strings 提供。内置 Web 字体维护脚本也读取翻译 XML。

验证包括旧设置兼容、三种语言偏好持久化、参数顺序与原文保留、历史诊断匹配、资源完整性，以及 iOS 模拟器上的 Compose 重组/草稿与会话身份保留测试。新增文案后还需检查窄屏、横屏与大屏排版。
