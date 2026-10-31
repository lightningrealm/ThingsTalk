package com.lr.thingstalk

import android.Manifest
import com.lr.thingstalk.data.ble.savedAndroidBleConnection
import com.lr.thingstalk.data.panel.DemoPanelBleConnection
import com.lr.thingstalk.presentation.ble.bleRuntimePermissions
import com.lr.thingstalk.presentation.ble.requiresScanLocationService
import kotlin.test.*

class SavedAndroidBleConnectionTest {
    @Test fun savedAddressCreatesConnectionWithoutAdvertisement() {
        val expected = DemoPanelBleConnection()
        var received: String? = null
        val connection = savedAndroidBleConnection("a1:b2:c3:d4:e5:f6") { received = it; expected }
        assertSame(expected, connection)
        assertEquals("A1:B2:C3:D4:E5:F6", received)
    }

    @Test fun invalidAddressOrIosIdentifierNeverTouchesBluetoothStack() {
        listOf("", "lamp", "A1:B2:C3:D4:E5", "GG:00:00:00:00:00", "00000000-0000-0000-0000-000000000001").forEach { id ->
            assertNull(savedAndroidBleConnection(id) { error("must not allocate native peripheral") })
        }
    }

    @Test fun platformPermissionFailureRemainsVisibleInsteadOfSuggestingDeviceIsMissing() {
        assertFailsWith<SecurityException> {
            savedAndroidBleConnection("A1:B2:C3:D4:E5:F6") { throw SecurityException("connection permission revoked") }
        }
    }

    @Test fun directConnectionDoesNotRequireScanningOrLegacyLocation() {
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), bleRuntimePermissions(31, true))
        assertEquals(listOf(Manifest.permission.BLUETOOTH_CONNECT), bleRuntimePermissions(37, true))
        listOf(24, 30).forEach { api ->
            assertTrue(bleRuntimePermissions(api, true).isEmpty())
            assertFalse(requiresScanLocationService(api, true))
            assertEquals(listOf(Manifest.permission.ACCESS_FINE_LOCATION), bleRuntimePermissions(api, false))
            assertTrue(requiresScanLocationService(api, false))
        }
        assertEquals(listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), bleRuntimePermissions(31, false))
        assertFalse(requiresScanLocationService(31, false))
    }
}
