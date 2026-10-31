package com.lr.thingstalk.presentation.navigation

import com.lr.thingstalk.presentation.i18n.*
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
    LanguageEnvironment(settings.language) {
        CompositionLocalProvider(LocalMqttProfiles provides profiles) {
            ThinsTalkTheme(settings) { ThemedApp(appearance) }
        }
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
                Page("LOCAL WORKSPACE", tr(AppStrings.device_configuration_unavailable_2ab358), tr(AppStrings.existing_data_is_kept_and_will_not_be_cleared_automatic_4a5426)) {
                    Text(error?.let(::diagnosticText) ?: tr(AppStrings.preparing_572335))
                    Primary(tr(AppStrings.reload_778497)) { host.retry() }
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
    val destinations = remember(capabilities, UiLanguage.current) {
        buildList {
            add(AppDestination(Screen.DEVICES, tr(AppStrings.devices_01f2c1), "device"))
            if (capabilities.ble) {
                add(AppDestination(Screen.NEARBY, tr(AppStrings.discover_523e40), "bluetooth"))
            }
            if (capabilities.mqtt) add(AppDestination(Screen.MQTT, "MQTT", "mqtt"))
            add(AppDestination(Screen.EVENTS, tr(AppStrings.messages_dc6de3), "events"))
            add(AppDestination(Screen.MORE, tr(AppStrings.my_workspace_a82c99), "more"))
        }
    }
    val roots = destinations.map { it.screen }
    val requestNavigation: (() -> Unit) -> Unit = { action ->
        val current = stack.lastOrNull() as? Route
        val editor = current?.takeIf { it.screen == Screen.PANEL_EDITOR }?.let { workspace.editor(it.id) }
        val panelSaving = current?.takeIf { it.screen == Screen.PANEL_EDITOR }?.let { workspace.panel(it.id)?.state?.value?.saving } == true
        when {
            panelSaving || editor?.state?.value?.processingExit == true -> message(tr(AppStrings.wait_for_saving_to_finish_before_leaving_38bde6))
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
            Screen.SETTINGS -> tr(AppStrings.local_appearance_4cd2c7)
            Screen.MQTT -> tr(AppStrings.mqtt_5_live_08e89a)
            Screen.MQTT_PROFILES, Screen.MQTT_PROFILE_EDITOR -> tr(AppStrings.mqtt_profiles_c6bfc9)
            Screen.NEARBY, Screen.SCAN_BINDING, Screen.GATT_SERVICES, Screen.GATT_CHARACTERISTIC, Screen.BLE_TRAFFIC -> tr(AppStrings.ble_live_81f6ef)
            Screen.DEVICES, Screen.DEVICE, Screen.DEVELOPER_SETTINGS, Screen.DEVICE_DEVELOPER, Screen.PANEL_EDITOR, Screen.DESCRIPTION, Screen.BLE_LOG_DEVICES -> tr(AppStrings.local_custom_89a99d)
            Screen.EVENTS, Screen.EVENT, Screen.ACTIVITY_RECORD -> tr(AppStrings.local_activity_32af60)
            Screen.MORE, Screen.SUBSCRIPTIONS, Screen.SUBSCRIPTION, Screen.DATA_SETTINGS, Screen.PRESETS, Screen.PRESET, Screen.TRANSFER, Screen.API, Screen.NOTIFICATIONS, Screen.ABOUT, Screen.LICENSES -> tr(AppStrings.local_workspace_82cc95)
            else -> tr(AppStrings.mock_demo_7379fe)
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
                                destination.screen == Screen.DEVELOPER_SETTINGS -> Page("DEVELOPER SETTINGS", tr(AppStrings.protocol_and_panel_623472), tr(AppStrings.choose_a_device_to_define_payloads_bind_widgets_or_impo_92aad8)) {
                                    if (devices.isEmpty()) Panel {
                                        Text(tr(AppStrings.create_a_custom_device_before_configuring_its_protocol_28c6da), color = Muted)
                                        Primary(tr(AppStrings.go_to_my_devices_735f81)) { go(Route(Screen.DEVICES)) }
                                    }
                                    devices.forEach { device ->
                                        Item(device.name, tr(AppStrings.widgets_payload_formats_050a0e, device.panel.widgets.size, device.panel.payloadFormats.size)) {
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
                                destination.screen == Screen.BLE_LOG_DEVICES -> Page("BLE TRAFFIC", tr(AppStrings.device_traffic_logs_5d7e9f), tr(AppStrings.panel_and_debugger_operations_are_recorded_per_device_ee82ea)) {
                                    if (devices.isEmpty()) Panel {
                                        Text(tr(AppStrings.no_custom_devices_yet_create_and_connect_a_peripheral_t_dc4233), color = Muted)
                                        Primary(tr(AppStrings.go_to_my_devices_735f81)) { go(Route(Screen.DEVICES)) }
                                    }
                                    devices.forEach { device ->
                                        Item(device.name, device.bleTarget?.let { it.name ?: tr(AppStrings.unnamed_ble_peripheral_39b9cd) } ?: tr(AppStrings.no_peripheral_linked_9c8499)) { go(Route(Screen.BLE_TRAFFIC, device.id)) }
                                    }
                                }
                                destination.screen in deviceScreens -> {
                                    val device = devices.firstOrNull { it.id == destination.id }
                                    val panel = workspace.panel(destination.id)
                                    if (device == null || panel == null) Page("DEVICE", tr(AppStrings.device_not_found_a578d8), tr(AppStrings.the_device_may_have_been_deleted_or_this_link_belongs_t_76838e)) {
                                        Primary(tr(AppStrings.back_to_my_devices_9058be)) { go(Route(Screen.DEVICES)) }
                                    } else when (destination.screen) {
                                        Screen.DEVICE_DEVELOPER -> Page("DEVICE DEVELOPER", device.name, tr(AppStrings.configure_protocols_and_panels_here_the_device_page_use_c3fb39)) {
                                            Item(tr(AppStrings.protocol_and_panel_editor_2d6ba1), tr(AppStrings.define_payloads_bind_operations_configure_widgets_and_i_681b3c)) { go(Route(Screen.PANEL_EDITOR, device.id)) }
                                            Item(tr(AppStrings.view_current_configuration_4e3a97), tr(AppStrings.view_saved_capabilities_payload_formats_and_bindings_01ea38)) { go(Route(Screen.DESCRIPTION, device.id)) }
                                            Item(tr(AppStrings.open_device_panel_bc3735), tr(AppStrings.use_the_currently_saved_panel_529d02)) { go(Route(Screen.DEVICE, device.id)) }
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
            title = { Text(tr(AppStrings.unapplied_draft_0bb22c)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(tr(AppStrings.keep_it_locally_to_continue_editing_later_drafts_are_no_f04a7e))
                    if (editorState.recovery != null) Text(tr(AppStrings.keeping_preserves_the_older_draft_without_replacing_the_981c68), color = Muted)
                    editorState.checkpointError?.let { Text(diagnosticText(it), color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = {
                TextButton(onClick = { leavingEditor.keepAndLeave(::finishNavigation) },
                    enabled = !editorState.processingExit) {
                    Text(if (editorState.processingExit) tr(AppStrings.processing_ce9fd5) else tr(AppStrings.keep_draft_and_leave_6a93a2))
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { leavingEditor.discard(::finishNavigation) }, enabled = !editorState.processingExit) { Text(tr(AppStrings.discard_and_leave_bfcffa)) }
                    TextButton(onClick = { pendingNavigation = null; pendingEditor = null }, enabled = !editorState.processingExit) { Text(tr(AppStrings.continue_editing_3a481b)) }
                }
            },
        )
    }

}
