package com.lr.thingstalk.presentation.navigation

import com.lr.thingstalk.presentation.activity.*
import com.lr.thingstalk.domain.activity.ActivityRepository
import com.lr.thingstalk.di.createMqttProfilesViewModel
import com.lr.thingstalk.presentation.mqtt.*
import com.lr.thingstalk.di.createAppearanceViewModel
import com.lr.thingstalk.di.createWorkspaceHostViewModel
import com.lr.thingstalk.di.createMqttWorkbenchViewModel
import com.lr.thingstalk.di.createPayloadInspector
import com.lr.thingstalk.platformCapabilities
import com.lr.thingstalk.PlatformCapabilities
import com.lr.thingstalk.presentation.mqtt.MqttWorkbenchPage
import com.lr.thingstalk.presentation.mqtt.MqttWorkbenchViewModel
import com.lr.thingstalk.presentation.device.*
import com.lr.thingstalk.presentation.ble.*
import com.lr.thingstalk.domain.ble.BleCharacteristicId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.lr.thingstalk.presentation.components.*
import com.lr.thingstalk.presentation.mock.*
import com.lr.thingstalk.presentation.panel.*
import com.lr.thingstalk.presentation.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@Composable
fun ThinsTalkApp() {
    val appearance = viewModel { createAppearanceViewModel() }
    val settings by appearance.appearance.collectAsState()
    val profiles = viewModel { createMqttProfilesViewModel() }
    CompositionLocalProvider(LocalMqttProfiles provides profiles) {
        ThinsTalkTheme(settings) { ThemedApp(appearance) }
    }
}

@Composable
private fun ThemedApp(appearance: AppearanceViewModel) {
    val capabilities = remember { platformCapabilities() }
    val activity = viewModel { ActivityViewModel() }
    val mqtt = viewModel { createMqttWorkbenchViewModel(activity.repository) }
    val host = viewModel { createWorkspaceHostViewModel(activity.repository) }
    val workspace by host.workspace.collectAsState()
    val error by host.error.collectAsState()
    val loaded = workspace
    if (loaded == null) {
        Surface(Modifier.fillMaxSize(), color = Paper) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)).imePadding()) {
                Page("LOCAL WORKSPACE", "设备配置暂时无法读取", "已有数据会保留，不会自动清空。") {
                    Text(error ?: "正在准备…")
                    Primary("重新读取") { host.retry() }
                }
            }
        }
    } else DeviceWorkspaceApp(loaded, mqtt, capabilities, appearance, activity.repository)
}

@Composable
private fun DeviceWorkspaceApp(
    workspace: DeviceWorkspaceViewModel,
    mqtt: MqttWorkbenchViewModel,
    capabilities: PlatformCapabilities,
    appearance: AppearanceViewModel,
    activity: ActivityRepository,
) {
    val stack = rememberNavBackStack(navigationConfig, Route(Screen.DEVICES))
    val session = viewModel { MockSession() }
    val inspector = remember { createPayloadInspector() }
    var pendingNavigation by remember { mutableStateOf<(() -> Unit)?>(null) }
    var pendingEditor by remember { mutableStateOf<PanelEditorViewModel?>(null) }
    val devices by workspace.devices.collectAsState()
    LaunchedEffect(session.scanning) {
        if (session.scanning) { delay(900); session.scanFinished = true; session.scanning = false }
    }
    LaunchedEffect(session.observing, session.connected) {
        while (session.observing && session.connected) {
            delay(1500)
            session.notificationCount++
            session.lastValue = if (session.notificationTarget == "battery") "64" else
                "01 18 ${(50 + session.notificationCount % 10).toString(16).uppercase()}"
            session.log("RX notify ${session.lastValue}")
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val message: (String) -> Unit = { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) } }
    val destinations = remember(capabilities) {
        buildList {
            add(AppDestination(Screen.DEVICES, "设备", "device"))
            if (capabilities.ble) {
                add(AppDestination(Screen.NEARBY, "发现", "bluetooth"))
            }
            if (capabilities.mqtt) add(AppDestination(Screen.MQTT, "MQTT", "mqtt"))
            add(AppDestination(Screen.EVENTS, "消息", "events"))
            add(AppDestination(Screen.MORE, "我的", "more"))
        }
    }
    val roots = destinations.map { it.screen }
    val requestNavigation: (() -> Unit) -> Unit = { action ->
        val current = stack.lastOrNull() as? Route
        val editor = current?.takeIf { it.screen == Screen.PANEL_EDITOR }?.let { workspace.editor(it.id) }
        val panelSaving = current?.takeIf { it.screen == Screen.PANEL_EDITOR }?.let { workspace.panel(it.id)?.state?.value?.saving } == true
        when {
            panelSaving || editor?.state?.value?.processingExit == true -> message("请等待保存完成后再离开")
            editor?.state?.value?.let { it.dirty || it.recovery != null } == true -> {
                pendingEditor = editor
                pendingNavigation = action
            }
            else -> action()
        }
    }
    val go: (Route) -> Unit = { destination ->
        if (stack.lastOrNull() != destination) requestNavigation {
            if (destination.screen in roots) stack.clear()
            stack.add(destination)
        }
    }
    val back: () -> Unit = { if (stack.size > 1) requestNavigation { stack.removeAt(stack.lastIndex) } }
    val returnToDevice: (String) -> Unit = { id ->
        val existing = stack.indexOfLast { (it as? Route) == Route(Screen.DEVICE, id) }
        if (existing >= 0) while (stack.lastIndex > existing) stack.removeAt(stack.lastIndex)
        else go(Route(Screen.DEVICE, id))
    }
    val route = stack.lastOrNull() as? Route ?: Route(Screen.DEVICES)
    val root = (stack.firstOrNull() as? Route)?.screen ?: Screen.DEVICES
    val deviceScreens = listOf(Screen.DEVICE, Screen.DEVICE_DEVELOPER, Screen.PANEL_EDITOR, Screen.DESCRIPTION,
        Screen.GATT_SERVICES, Screen.GATT_CHARACTERISTIC, Screen.BLE_TRAFFIC, Screen.MQTT_PROFILES, Screen.MQTT_PROFILE_EDITOR)
    // Device sessions are app-owned; navigating away must not disconnect them.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, workspace, capabilities.backgroundBleSession) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                workspace.enterBackground(keepBleSession = capabilities.backgroundBleSession)
                (stack.lastOrNull() as? Route)?.takeIf { it.screen == Screen.PANEL_EDITOR }?.let { workspace.editor(it.id)?.checkpointNow() }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); workspace.scan.stop(); workspace.disconnectAll() }
    }
    AdaptiveAppFrame(
        badge = when (route.screen) {
            Screen.SETTINGS -> "本地 · 外观"
            Screen.MQTT -> "MQTT 5 · 实时"
            Screen.MQTT_PROFILES, Screen.MQTT_PROFILE_EDITOR -> "MQTT · 连接配置"
            Screen.NEARBY, Screen.SCAN_BINDING, Screen.GATT_SERVICES, Screen.GATT_CHARACTERISTIC, Screen.BLE_TRAFFIC -> "BLE · 实时"
            Screen.DEVICES, Screen.DEVICE, Screen.DEVELOPER_SETTINGS, Screen.DEVICE_DEVELOPER, Screen.PANEL_EDITOR, Screen.DESCRIPTION, Screen.BLE_LOG_DEVICES -> "本地 · 自定义"
            Screen.EVENTS, Screen.EVENT, Screen.ACTIVITY_RECORD -> "本地 · 真实记录"
            Screen.MORE, Screen.SUBSCRIPTIONS, Screen.SUBSCRIPTION, Screen.DATA_SETTINGS, Screen.PRESETS, Screen.PRESET, Screen.TRANSFER, Screen.API, Screen.NOTIFICATIONS, Screen.ABOUT, Screen.LICENSES -> "本地 · 工作台"
            else -> "MOCK · 演示"
        },
        destinations = destinations,
        selected = root,
        showBottomNavigation = route.screen in roots,
        back = back.takeIf { stack.size > 1 },
        navigate = { go(Route(it)) },
        snackbar = snackbar,
        contentMaxWidth = if (route.screen == Screen.MQTT) 1280.dp else 800.dp,
    ) { usesRail ->
        // NavDisplay retains entry content by key; read the current layout inside retained entries.
        val currentUsesRail = rememberUpdatedState(usesRail)
        NavDisplay(
            backStack = stack, onBack = back,
            transitionSpec = {
                val fromRoot = initialState.entries.singleOrNull()?.metadata?.get("rootDestination") == true
                val toRoot = targetState.entries.singleOrNull()?.metadata?.get("rootDestination") == true
                if (fromRoot && toRoot) NavigationMotion.crossfade() else NavigationMotion.forward()
            },
            popTransitionSpec = {
                val fromRoot = initialState.entries.singleOrNull()?.metadata?.get("rootDestination") == true
                val toRoot = targetState.entries.singleOrNull()?.metadata?.get("rootDestination") == true
                if (fromRoot && toRoot) NavigationMotion.crossfade() else NavigationMotion.back()
            },
            predictivePopTransitionSpec = { NavigationMotion.back() },
            modifier = Modifier.fillMaxSize(),
            entryProvider = { key ->
                val destination = key as Route
                NavEntry(key, metadata = mapOf("rootDestination" to (destination.screen in roots))) {
                    val rootInsets = if (destination.screen in roots && !currentUsesRail.value) {
                        Modifier.padding(bottom = 80.dp)
                            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    } else Modifier
                    Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                        Box(Modifier.widthIn(max = when (destination.screen) { Screen.PANEL_EDITOR -> androidx.compose.ui.unit.Dp.Infinity; Screen.MQTT -> 1280.dp; else -> 800.dp }).fillMaxSize().then(rootInsets)) {
                            when {
                                destination.screen in setOf(Screen.EVENTS, Screen.EVENT, Screen.ACTIVITY_RECORD, Screen.MORE, Screen.SUBSCRIPTIONS, Screen.SUBSCRIPTION,
                                    Screen.NOTIFICATIONS, Screen.DATA_SETTINGS, Screen.PRESETS, Screen.PRESET, Screen.TRANSFER, Screen.API, Screen.ABOUT, Screen.LICENSES) ->
                                    ActivityPages(destination, activity, workspace, go, message)
                                destination.screen == Screen.SETTINGS -> AppearancePage(appearance)
                                destination.screen == Screen.MQTT -> MqttWorkbenchPage(mqtt, onProfiles = { go(Route(Screen.MQTT_PROFILES)) })
                                destination.screen == Screen.MQTT_PROFILES -> MqttProfilesPage(
                                    owner = if (destination.id.isEmpty()) "workbench" else "device:${destination.id}",
                                    onEdit = { go(Route(Screen.MQTT_PROFILE_EDITOR, id = destination.id, profileId = it)) })
                                destination.screen == Screen.MQTT_PROFILE_EDITOR -> MqttProfileEditorPage(
                                    owner = if (destination.id.isEmpty()) "workbench" else "device:${destination.id}",
                                    profileId = destination.profileId,
                                    initialEndpoint = devices.firstOrNull { it.id == destination.id }?.mqttEndpoint.orEmpty(),
                                    onSaved = back)
                                destination.screen in listOf(Screen.DEMO_DEVICE, Screen.DEMO_DESCRIPTION) -> DevicePages(
                                    destination.copy(screen = if (destination.screen == Screen.DEMO_DEVICE) Screen.DEVICE else Screen.DESCRIPTION),
                                    session,
                                    go = { target ->
                                        go(when (target.screen) {
                                            Screen.DEVICE -> target.copy(screen = Screen.DEMO_DEVICE)
                                            Screen.DESCRIPTION -> target.copy(screen = Screen.DEMO_DESCRIPTION)
                                            else -> target
                                        })
                                    }, message = message,
                                )
                                destination.screen == Screen.DEVICES -> DeviceListPage(workspace, bleAvailable = capabilities.ble, initiallyCreating = destination.id == "create",
                                    onOpen = { go(Route(Screen.DEVICE, it)) }, onScan = { go(Route(Screen.NEARBY)) })
                                destination.screen == Screen.DEVELOPER_SETTINGS -> Page("DEVELOPER SETTINGS", "协议与面板", "选择设备，定义收发报文、绑定组件，或导入导出配置文件。") {
                                    if (devices.isEmpty()) Panel {
                                        Text("先创建一台自定义设备，再为它配置协议与面板。", color = Muted)
                                        Primary("前往我的设备") { go(Route(Screen.DEVICES)) }
                                    }
                                    devices.forEach { device ->
                                        Item(device.name, "${device.panel.widgets.size} 个组件 · ${device.panel.payloadFormats.size} 个数据格式") {
                                            go(Route(Screen.DEVICE_DEVELOPER, device.id))
                                        }
                                    }
                                }
                                destination.screen in listOf(Screen.NEARBY, Screen.SCAN_BINDING) -> BleScanPage(
                                    workspace.scan, workspace,
                                    destination.id.takeIf { destination.screen == Screen.SCAN_BINDING },
                                    onCreateManual = { go(Route(Screen.DEVICES, "create")) },
                                    onAssociated = { id ->
                                        if ((stack.lastOrNull() as? Route)?.screen == Screen.SCAN_BINDING) back()
                                        go(Route(Screen.DEVICE, id))
                                    },
                                )
                                destination.screen == Screen.BLE_LOG_DEVICES -> Page("BLE TRAFFIC", "设备通信日志", "面板和调试台的操作按设备分别记录。") {
                                    if (devices.isEmpty()) Panel {
                                        Text("还没有自定义设备。创建并连接外设后即可查看日志。", color = Muted)
                                        Primary("前往我的设备") { go(Route(Screen.DEVICES)) }
                                    }
                                    devices.forEach { device ->
                                        Item(device.name, device.bleTarget?.let { it.name ?: "未命名 BLE 外设" } ?: "尚未关联外设") { go(Route(Screen.BLE_TRAFFIC, device.id)) }
                                    }
                                }
                                destination.screen in deviceScreens -> {
                                    val device = devices.firstOrNull { it.id == destination.id }
                                    val panel = workspace.panel(destination.id)
                                    if (device == null || panel == null) Page("DEVICE", "设备不存在", "设备可能已删除，或此链接来自旧版演示页面。") {
                                        Primary("返回我的设备") { go(Route(Screen.DEVICES)) }
                                    } else when (destination.screen) {
                                        Screen.DEVICE_DEVELOPER -> Page("DEVICE DEVELOPER", device.name, "收发协议与面板配置集中在这里，保存后展示页会按配置运行。") {
                                            Item("协议与面板编辑", "定义报文字段、绑定通信操作、配置组件与导入导出文件") { go(Route(Screen.PANEL_EDITOR, device.id)) }
                                            Item("查看当前配置", "查看已保存的能力、数据格式和通信绑定") { go(Route(Screen.DESCRIPTION, device.id)) }
                                            Item("打开设备面板", "使用当前已保存的面板") { go(Route(Screen.DEVICE, device.id)) }
                                            DeviceConnectionPanel(device, panel, workspace,
                                                onMqttProfiles = { go(Route(Screen.MQTT_PROFILES, device.id)) },
                                                onScan = { go(Route(Screen.SCAN_BINDING, device.id)) },
                                                onDebug = { go(Route(Screen.GATT_SERVICES, device.id)) },
                                                onLogs = { go(Route(Screen.BLE_TRAFFIC, device.id)) },
                                            )
                                        }
                                        Screen.PANEL_EDITOR -> workspace.editor(device.id)?.let { editor ->
                                            PanelEditorPage(panel, editor, inspector, onFeedback = message)
                                        }
                                        Screen.DESCRIPTION -> PanelDescriptionPage(panel)
                                        Screen.GATT_SERVICES -> BleServicesPage(
                                            device.name, workspace.debug(device.id)!!,
                                            onCharacteristic = { id -> go(Route(Screen.GATT_CHARACTERISTIC, device.id, id.serviceUuid, id.characteristicUuid)) },
                                            onLogs = { go(Route(Screen.BLE_TRAFFIC, device.id)) },
                                            onConnect = { returnToDevice(device.id) },
                                        )
                                        Screen.GATT_CHARACTERISTIC -> BleCharacteristicPage(
                                            device.name, BleCharacteristicId(destination.serviceUuid, destination.characteristicUuid), workspace.debug(device.id)!!,
                                            onLogs = { go(Route(Screen.BLE_TRAFFIC, device.id)) },
                                            onConnect = { returnToDevice(device.id) },
                                        )
                                        Screen.BLE_TRAFFIC -> BleTrafficPage(device.name, workspace.traffic(device.id)!!)
                                        else -> ConfigurableDevicePage(
                                            panel, onEdit = { go(Route(Screen.DEVICE_DEVELOPER, device.id)) },
                                            setupSession = { workspace.setupPanelSession(device.id, it) },
                                            transportContent = { DeviceConnectionPanel(device, panel, workspace,
                                                onMqttProfiles = { go(Route(Screen.MQTT_PROFILES, device.id)) },
                                                onScan = { go(Route(Screen.SCAN_BINDING, device.id)) },
                                                onDebug = { go(Route(Screen.GATT_SERVICES, device.id)) },
                                                onLogs = { go(Route(Screen.BLE_TRAFFIC, device.id)) },
                                                showDeveloperTools = false,
                                            ) },
                                        )
                                    }
                                }
                                else -> when (destination.screen) {
                                    Screen.DEVICES, Screen.DEVICE, Screen.DESCRIPTION, Screen.HISTORY, Screen.COMMANDS, Screen.COMMAND -> DevicePages(destination, session, go, message)
                                    Screen.NEARBY, Screen.BLE, Screen.SERVICES, Screen.CHARACTERISTIC, Screen.LOGS -> BlePages(destination, session, go, message)
                                    else -> WorkspacePages(destination, session, go, message)
                                }
                            }
                        }
                    }
                }
            },
        )
    }
    val leavingEditor = pendingEditor
    if (pendingNavigation != null && leavingEditor != null) {
        val editorState by leavingEditor.state.collectAsState()
        fun finishNavigation() {
            val action = pendingNavigation
            pendingNavigation = null
            pendingEditor = null
            action?.invoke()
        }
        AlertDialog(
            onDismissRequest = { if (!editorState.processingExit) { pendingNavigation = null; pendingEditor = null } },
            title = { Text("还有未应用的草稿") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("可以保留在本机，下次打开此设备继续编辑；草稿不会自动应用到设备。")
                    if (editorState.recovery != null) Text("保留会留下原有旧草稿，不覆盖当前设备配置。", color = Muted)
                    editorState.checkpointError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { leavingEditor.keepAndLeave(::finishNavigation) },
                    enabled = !editorState.processingExit) {
                    Text(if (editorState.processingExit) "正在处理…" else "保留草稿并离开")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leavingEditor.discard(::finishNavigation) }, enabled = !editorState.processingExit) { Text("丢弃并离开") }
                    TextButton(onClick = { pendingNavigation = null; pendingEditor = null }, enabled = !editorState.processingExit) { Text("继续编辑") }
                }
            },
        )
    }

}
