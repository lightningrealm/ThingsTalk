package com.lr.thingstalk.di

import com.lr.thingstalk.data.ble.createBleScanner
import com.lr.thingstalk.data.ble.InMemoryBleTraffic
import com.lr.thingstalk.data.ble.TracingBleConnection
import com.lr.thingstalk.data.device.createDeviceRepository
import com.lr.thingstalk.data.mqtt.createMqttRepository
import com.lr.thingstalk.data.panel.DualPanelOperations
import com.lr.thingstalk.data.panel.MqttPanelOperations
import com.lr.thingstalk.data.panel.BlePanelOperations
import com.lr.thingstalk.data.panel.createPanelDraftRepository
import com.lr.thingstalk.presentation.device.DeviceWorkspaceViewModel
import com.lr.thingstalk.presentation.device.DeviceBleDiagnostics
import com.lr.thingstalk.presentation.device.WorkspaceHostViewModel

fun createWorkspaceHostViewModel(activity: com.lr.thingstalk.domain.activity.ActivityRepository? = null) = WorkspaceHostViewModel {
    val repository = createDeviceRepository()
    DeviceWorkspaceViewModel(
        repository = repository,
        activity = activity,
        drafts = createPanelDraftRepository(),
        mqttFactory = ::createMqttRepository,
        mqttOperationsFactory = ::MqttPanelOperations,
        dualOperationsFactory = ::DualPanelOperations,
        scanner = createBleScanner(),
        panelFactory = { device, save -> createPanelViewModel(device.panel, save) },
        operationsFactory = ::BlePanelOperations,
        diagnosticsFactory = { scope ->
            val traffic = InMemoryBleTraffic()
            DeviceBleDiagnostics(traffic) { connection -> TracingBleConnection(connection, traffic, scope) }.apply { protect = traffic::protect; listen = { traffic.onEntry = it } }
        },
    )
}
