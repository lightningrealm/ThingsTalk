---
name: thingstalk-contract-maintainer
description: 在 ThingsTalk 客户端开发任务中维护面板能力契约、硬件接入 Skill、独立 Prompt 与兼容快照。用于客户端能力变更或接入规范维护，不用于硬件固件适配任务。
---

# ThingsTalk 客户端契约维护

仅在任务本身要求修改 ThingsTalk 客户端能力或维护接入规范时使用。硬件接入中发现缺口不授权修改客户端；应先交付缺口报告，不能自动转为本流程。

按 [维护说明](references/maintenance.md) 核对实际模型、校验器、编解码器和执行路径。只修改任务授权的能力；文档或角色边界调整不需要变更客户端业务代码。保留硬件依照客户端既有标准实现的原则，不将固定设置协议改成任意表单。

客户端实现是契约事实来源。同步硬件包 `../thingstalk-ble-panel` 的规范、示例与必要测试，再执行本 Skill 的 `scripts/sync_contract.py` 刷新和检查快照、生成独立 Prompt。刷新哈希不能替代语义审查，也不能证明已发布 App 支持该能力。

发布给硬件团队的仅是 `thingstalk-ble-panel` 目录或其中的 PROMPT.md；本 Skill、维护说明和刷新脚本留在客户端仓库。维护硬件角色规则时同步 `references/hardware-boundary.md` 与硬件 SKILL.md，并检查生成 Prompt 包含相同边界。目标 App 版本与契约修订号分别记录。
