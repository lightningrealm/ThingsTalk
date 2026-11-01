# 语物 · ThingsTalk

语物（ThingsTalk）是使用 Kotlin Multiplatform / Compose Multiplatform 构建的本地设备工作台。Android 和 iOS 支持 BLE 与 MQTT；Web（Kotlin/Wasm）支持 MQTT。

## 功能

- 手动创建 BLE、MQTT 或双连接设备，也可从配置文件自动识别类型并创建。
- 自定义面板组件、报文编解码、BLE 特征读写/通知、MQTT 发布/订阅。
- BLE 扫描筛选、服务/特征/描述符浏览、设备关联和已保存设备重连。
- MQTT 5 工作台及共用连接配置，统一使用 WS/WSS。
- 本地事件、已读/处理状态、通信记录、模板导入导出、外观设置。
- 前台切页保持会话；后台能力受平台约束，未提供系统推送或通用后台常驻监测。

## 构建

需要兼容项目 Gradle 配置的 JDK、Android SDK；iOS 构建需要 macOS 和 Xcode。使用 Android Studio 配置 Android SDK，iOS 打开 `iosApp/iosApp.xcodeproj`。

```sh
./gradlew :androidApp:assembleDebug
./gradlew :shared:testAndroidHostTest
./gradlew :shared:wasmJsBrowserDevelopmentRun
./gradlew :shared:wasmJsBrowserDistribution
```

## 目录

- `shared/`：领域契约、仓库、通信适配器及共享界面。
- `androidApp/`、`iosApp/`：平台入口与应用资源。
- `assets/branding/vector-v2/`：应用图标的可编辑 SVG 源文件。
- `docs/`：使用说明、架构、协议和示例，见[文档索引](docs/README.md)。
- `skills/thingstalk-ble-panel/`：仅供硬件接入、对齐客户端已有标准的[硬件开发 Skill](skills/thingstalk-ble-panel/SKILL.md) 和[独立 Prompt](skills/thingstalk-ble-panel/PROMPT.md)。
- `skills/thingstalk-contract-maintainer/`：[客户端契约维护 Skill](skills/thingstalk-contract-maintainer/SKILL.md)，不随硬件接入包分发。

## 贡献与验收

修改协议或组件时同步校验、示例、Skill 和 Prompt。运行共享测试及受影响平台编译；真实外设、Broker、后台和权限行为需要真机验收。编译或模拟器通过不等于硬件验收通过。

企划、调研草稿、会话交接、排查流水及生成预览统一放在 `docs/local/`，由 `.gitignore` 排除。该目录不是项目运行或构建的依赖。不得提交凭据、本机配置或构建产物。

## 许可证

Copyright 2026 lightningduke。

除另有声明的第三方内容外，本仓库的客户端代码、协议文档、配置示例、硬件开发 Skill/Prompt 和项目原创图标资源均采用 [Apache License 2.0](LICENSE)。允许商业使用、修改和再分发，使用及分发时须遵守许可证中的声明保留等要求。版权及第三方提示见 [NOTICE](NOTICE)。

硬件开发 Skill 目录附有独立的 [LICENSE](skills/thingstalk-ble-panel/LICENSE) 和 [NOTICE](skills/thingstalk-ble-panel/NOTICE)，单独分发该包时请一并保留。

第三方依赖和素材继续遵循各自许可证；特别是 Web 内置字体采用 [SIL Open Font License 1.1](shared/src/wasmJsMain/resources/fonts/OFL.txt)，不改为 Apache-2.0。本项目许可证不授予 ThingsTalk 名称或标识的商标使用权（许可证第 6 条允许的合理描述用途除外），也不代表官方背书。
