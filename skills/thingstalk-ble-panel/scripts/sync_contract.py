#!/usr/bin/env python3
"""Check source/document drift and render the standalone prompt; standard library only."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import sys

BUNDLE = Path(__file__).resolve().parents[1]
BASE = 'shared/src/commonMain/kotlin/com/lr/thingstalk/'
SOURCES = [BASE + p for p in (
    'domain/panel/PanelDefinition.kt', 'domain/panel/PayloadFormat.kt',
    'domain/ble/MuyuProvisioning.kt', 'data/ble/MuyuProvisioningClient.kt',
    'data/ble/TracingBleConnection.kt', 'presentation/panel/SetupPanelView.kt',
    'domain/panel/SetupPanel.kt', 'domain/ble/ChunkedBleSettingsProtocol.kt', 'domain/ble/BleFailureDiagnostic.kt', 'data/ble/BleSetupPanelClient.kt',
    'presentation/device/DeviceWorkspaceViewModel.kt',
    'data/panel/PanelJson.kt', 'data/panel/PanelPayloadCodec.kt', 'data/panel/ChannelPanelJson.kt',
    'data/panel/PanelValueCodec.kt', 'data/panel/BlePanelOperations.kt',
    'presentation/panel/ConfigurableDevicePage.kt', 'presentation/panel/PanelNumberControls.kt',
    'presentation/panel/PanelFileTransfer.kt',
    'domain/panel/MqttPanelBinding.kt', 'domain/panel/DualPanelBinding.kt',
    'data/panel/MqttPanelOperations.kt', 'data/panel/DualPanelOperations.kt',
    'domain/mqtt/MqttProfile.kt', 'data/mqtt/MqttProfileStorage.kt',
    'data/mqtt/MqttValidation.kt', 'data/mqtt/KtorMqttTransport.kt',
    'presentation/mqtt/MqttProfilePages.kt',
    'presentation/panel/DualPanelBindingEditor.kt', 'presentation/panel/PanelEditorPage.kt',
    'presentation/device/DeviceConnectionPanel.kt', 'presentation/components/TransportTabs.kt',
    'presentation/panel/PanelViewModel.kt', 'presentation/panel/PanelStateSlices.kt', 'presentation/panel/PanelEditorViewModel.kt')]
SOURCES += ['shared/src/' + platform + '/kotlin/com/lr/thingstalk/data/mqtt/MqttProfileStorage.' + suffix + '.kt'
            for platform, suffix in [('androidMain', 'android'), ('iosMain', 'ios'), ('wasmJsMain', 'wasmJs')]]
CONTENTS = ['references/ble-network-provisioning.md', 'SKILL.md', 'references/contract.md', 'references/maintenance.md',
            'agents/openai.yaml', 'assets/all-widgets.thingstalk.json', 'assets/wire-vectors.json',
            'scripts/sync_contract.py', 'references/mqtt-dual.md',
            'assets/mqtt-all-widgets.thingstalk.json', 'assets/dual-power.thingstalk.json', 'assets/dual-channels.thingstalk.json', 'assets/setup-panel.thingstalk.json']

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def enums(root):
    source = (root / SOURCES[0]).read_text()
    payload = (root / SOURCES[1]).read_text()
    result = {}
    for name in ['WidgetType', 'CapabilityType', 'CodecType', 'PanelWriteMode', 'PanelByteOrder', 'PayloadEncoding', 'PanelTransport', 'SetupProtocol', 'SetupFieldRule']:
        match = re.search(r'enum class\s+' + name + r'\s*\{([^}]+)\}', source + '\n' + payload + '\n' + (root / (BASE + 'domain/panel/SetupPanel.kt')).read_text())
        if not match:
            raise ValueError('Cannot extract enum ' + name + '; update the synchronizer explicitly')
        result[name] = [v.strip() for v in match.group(1).split(',') if v.strip()]
    return result

def render(manifest):
    contract = (BUNDLE / 'references/contract.md').read_text()
    example = (BUNDLE / 'assets/all-widgets.thingstalk.json').read_text()
    vectors = (BUNDLE / 'assets/wire-vectors.json').read_text()
    mqtt = (BUNDLE / 'references/mqtt-dual.md').read_text()
    provisioning = (BUNDLE / 'references/ble-network-provisioning.md').read_text()
    examples = '\n\n'.join('## ' + label + '\n\n```json\n' + (BUNDLE / path).read_text().rstrip() + '\n```' for label, path in [
        ('MQTT 五组件完整示例', 'assets/mqtt-all-widgets.thingstalk.json'),
        ('旧 v5 混合路由示例', 'assets/dual-power.thingstalk.json'),
        ('v6 按通道定义完整示例', 'assets/dual-channels.thingstalk.json'),
        ('v7 设置表单示例', 'assets/setup-panel.thingstalk.json')])
    return f'''# ThingsTalk 硬件开发配套 Prompt

兼容快照：文档修订 {manifest['contractRevision']}，核对日期 {manifest['reviewedOn']}，面板版本 BLE {manifest['panelSchemaVersions']['BLE']} / MQTT {manifest['panelSchemaVersions']['MQTT']} / DUAL {manifest['panelSchemaVersions']['DUAL']}，设置面板 SETUP {manifest['panelSchemaVersions']['SETUP']}。
这是一份可独立发送的 Prompt，不需要访问原 App 仓库。它描述核对时的实现，不能假设之后新增功能自动可用。

请为我指定的硬件能力，同时交付 BLE、MQTT 或双连接固件协议和 ThingsTalk 可导入的面板配置。按目标平台选择版本：Web 仅 MQTT 功能，推荐 v4；Android/iOS 可用 BLE v3、MQTT v4、DUAL v6（旧 v5 混合路由仍兼容）。需要 BLE 设置表单时使用 v7 setupPanels，按下文规范填写。
先区分：普通控制面板用于日常读值和操作；设置面板 setupPanels 用于文本/密码及分组提交；App 本机 MQTT 连接配置由用户在连接库管理。
普通组件与设置表单可在同一份设备 JSON、同一个展示页共存。不要生成 normalPanels、widget.type=SETUP 或 MQTT 设置提交等未实现结构/能力。
先从我提供的固件/GATT/topic/需求中核实已有能力，区分事实、拟议设计和待确认事项。
按下面的当前规范选择组件，产出完整的 `设备名.thingstalk.json`、能力到 GATT/topic/字段与双连接路由的映射表、固件收发说明和字节测试向量。
配置中的 UUID、topic 与命令值必须与我的固件一致；下面示例只用于示范结构，不能当成我的实际设备。
缺少关键协议时提出具体问题或标明待确认设计；不虚构App支持的组件/字段，不把未执行的测试说成通过。
若正在开发新固件，可同时定义固件和配置；若适配既有固件，应保留其协议语义并指出不兼容项。
面板配置作为固件发布附件交付，由用户导入、保存并关联设备；当前不支持BLE自动下载/安装面板。
新需求若超出规范，明确列出需要的固件适配或App扩展，不通过自造JSON键绕过。
MQTT 另交付统一 WS/WSS 的 Broker 部署及本机连接配置说明；地址、账号密码、Client ID 和配置 ID 不放进面板文件。

我的硬件资料（由使用者补充）：芯片/SDK、目标平台、已有 GATT/topic 与协议、Broker WS/WSS 入口与认证要求（不提供真实密码）、需要展示/控制的能力、单位/范围/精度、通知时机、固件版本；是否需要设备设置表单、固件是否实现 BLE_CHUNKED_SETTINGS_V1、设置窗口/字段限制/配对要求。

---

{contract}

{mqtt}

{provisioning}

## BLE 五组件完整可导入示例

```json
{example.rstrip()}
```

## 示例收发测试向量

以下是独立于面板文件的测试数据，不要把 valid/invalid 等测试字段加入面板配置。

```json
{vectors.rstrip()}
```

{examples}
'''

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--refresh', action='store_true')
    parser.add_argument('--repo', type=Path, default=BUNDLE.parents[1])
    parser.add_argument('--revision', type=int)
    parser.add_argument('--reviewed-on')
    args = parser.parse_args()
    root = args.repo.resolve()
    has_source = (root / SOURCES[0]).is_file()
    target = BUNDLE / 'references/compatibility.json'
    if args.refresh:
        if not has_source:
            parser.error('--refresh requires the ThingsTalk source repository (--repo)')
        if not args.revision or args.revision < 1 or not args.reviewed_on:
            parser.error('--refresh requires positive --revision and --reviewed-on YYYY-MM-DD after review')
        datetime.date.fromisoformat(args.reviewed_on)
        manifest = {'contractRevision': args.revision, 'reviewedOn': args.reviewed_on,
                    'panelSchemaVersions': {'BLE': 3, 'MQTT': 4, 'DUAL': 6, 'SETUP': 7},
                    'platforms': {'Android': ['BLE', 'MQTT', 'DUAL'], 'iOS': ['BLE', 'MQTT', 'DUAL'], 'Web': ['MQTT']},
                    'enums': enums(root),
                    'sourceHashes': {p: digest(root / p) for p in SOURCES},
                    'bundleHashes': {p: digest(BUNDLE / p) for p in CONTENTS}}
        target.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n')
        (BUNDLE / 'PROMPT.md').write_text(render(manifest))
        print('Compatibility snapshot and standalone prompt refreshed; semantic review is still required.')
        return 0
    manifest = json.loads(target.read_text())
    errors = []
    for p, expected in manifest['bundleHashes'].items():
        if not (BUNDLE / p).is_file() or digest(BUNDLE / p) != expected:
            errors.append('Bundle changed: ' + p)
    if has_source:
        for p, expected in manifest['sourceHashes'].items():
            if not (root / p).is_file() or digest(root / p) != expected:
                errors.append('App source changed; review contract: ' + p)
        if enums(root) != manifest['enums']:
            errors.append('Supported enum catalog differs from App')
    else:
        print('Standalone package: App source unavailable; only package consistency is checked.')
    if (BUNDLE / 'PROMPT.md').read_text() != render(manifest):
        errors.append('PROMPT.md is out of sync')
    if errors:
        print('\n'.join(errors), file=sys.stderr)
        return 1
    print('Contract package is consistent' + (' with tracked App source.' if has_source else '.'))
    return 0

if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError) as exc:
        print('Contract check failed: ' + str(exc), file=sys.stderr)
        sys.exit(1)
