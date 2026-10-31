package com.lr.thingstalk.device

import com.lr.thingstalk.domain.ble.BleScanDevice
import com.lr.thingstalk.presentation.ble.BleScanFilter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BleScanFilterTest {
    private val device = BleScanDevice(
        id = "AA:BB:CC:DD:EE:FF",
        name = "  Desk Sensor  ",
        rssi = -65,
        serviceUuids = listOf("0000180d-0000-1000-8000-00805f9b34fb", "12345678-1234-5678-9abc-def012345678"),
    )

    @Test fun defaultsAndWhitespaceLeaveAllDevicesVisible() {
        for (filter in listOf(BleScanFilter(), BleScanFilter(query = " \n ", serviceUuid = " \t "))) {
            assertFalse(filter.isActive)
            assertNull(filter.serviceUuidError)
            assertTrue(filter.matches(device))
            assertTrue(filter.matches(device.copy(name = null, serviceUuids = emptyList())))
        }
    }

    @Test fun queryMatchesNameOrPlatformIdentifierWithoutCaseOrOuterWhitespace() {
        assertTrue(BleScanFilter(query = "  dESk  ").matches(device))
        assertTrue(BleScanFilter(query = "bb:cc").matches(device.copy(name = null)))
        assertTrue(BleScanFilter(query = "7c8a").matches(device.copy(id = "  F39A7C8A-99FE-415A-B41E-407C7D074A32 ", name = "")))
        assertFalse(BleScanFilter(query = "lamp").matches(device))
    }

    @Test fun namedOnlyExcludesNullEmptyAndWhitespaceNames() {
        val filter = BleScanFilter(namedOnly = true)
        assertTrue(filter.matches(device))
        for (name in listOf(null, "", " \t \n")) assertFalse(filter.matches(device.copy(name = name)))
    }

    @Test fun rssiThresholdIsInclusiveAndUsesRawReportedValues() {
        val filter = BleScanFilter(minimumRssi = -65)
        assertTrue(filter.matches(device))
        assertTrue(filter.matches(device.copy(rssi = -64)))
        assertFalse(filter.matches(device.copy(rssi = -66)))
        assertTrue(filter.matches(device.copy(rssi = 0)))
        assertTrue(filter.matches(device.copy(rssi = 10)))
    }

    @Test fun sixteenThirtyTwoAndFullUuidsMatchTheSameBluetoothBaseUuid() {
        for (query in listOf(" 180D ", "0000180D", "0000180D-0000-1000-8000-00805F9B34FB", "0000180D00001000800000805F9B34FB")) {
            val filter = BleScanFilter(serviceUuid = query)
            assertTrue(filter.isActive)
            assertNull(filter.serviceUuidError)
            for (advertised in listOf("180d", "0000180d", "0000180d-0000-1000-8000-00805f9b34fb", "0000180d00001000800000805f9b34fb")) {
                assertTrue(filter.matches(device.copy(serviceUuids = listOf(advertised))))
            }
        }
        assertTrue(BleScanFilter(serviceUuid = "1234abcd").matches(device.copy(serviceUuids = listOf("1234ABCD-0000-1000-8000-00805F9B34FB"))))
    }

    @Test fun serviceUuidMatchesFragmentsWithoutCaseOrHyphenRestrictions() {
        for (query in listOf(
            "12345678-1234-5678-9ABC-DEF012345678",
            "12345678123456789abcdef012345678",
            "12345678", "9abc", "DEF012", "5678-9a", "def01", "9", " -9-a--bc- ",
        )) {
            val filter = BleScanFilter(serviceUuid = query)
            assertTrue(filter.isActive)
            assertNull(filter.serviceUuidError)
            assertTrue(filter.matches(device))
        }
        // A short fragment must not be expanded as though it were a complete Bluetooth UUID.
        assertTrue(BleScanFilter(serviceUuid = "180").matches(device))
        assertTrue(BleScanFilter(serviceUuid = "0180d").matches(device))
        assertTrue(BleScanFilter(serviceUuid = "0d-0000-10").matches(device.copy(serviceUuids = listOf("180D"))))
    }

    @Test fun unmatchedOrMissingServicesStayHidden() {
        assertFalse(BleScanFilter(serviceUuid = "180F").matches(device))
        assertFalse(BleScanFilter(serviceUuid = "fffff").matches(device))
        assertFalse(BleScanFilter(serviceUuid = "180D").matches(device.copy(serviceUuids = emptyList())))
    }

    @Test fun invalidAdvertisedUuidsCannotMatchOtherwiseValidFragments() {
        val filter = BleScanFilter(serviceUuid = "def")
        for (advertised in listOf("def", "def01", "not-a-uuid-def", "12345678-1234-5678-9abc-def01234567", "12345678 1234 5678 9abc def012345678")) {
            assertFalse(filter.matches(device.copy(serviceUuids = listOf(advertised))))
        }
        assertTrue(filter.matches(device.copy(serviceUuids = listOf("12345678123456789ABCDEF012345678"))))
    }

    @Test fun invalidServiceUuidProducesAnErrorAndNeverMatches() {
        for (value in listOf("0x180D", "GGGG", "---", "a".repeat(33), "180D,180F", "18 0D", "18\n0d", "18\t0d")) {
            val filter = BleScanFilter(serviceUuid = value)
            assertTrue(filter.isActive)
            assertNotNull(filter.serviceUuidError)
            assertFalse(filter.matches(device.copy(serviceUuids = listOf(value))))
        }
    }

    @Test fun allEnabledConditionsMustMatch() {
        val filter = BleScanFilter(query = "sensor", namedOnly = true, minimumRssi = -65, serviceUuid = "80d")
        assertTrue(filter.matches(device))
        assertFalse(filter.matches(device.copy(name = "Lamp")))
        assertFalse(filter.copy(query = "AA:BB").matches(device.copy(name = null)))
        assertFalse(filter.matches(device.copy(rssi = -66)))
        assertFalse(filter.matches(device.copy(serviceUuids = listOf("180F"))))
    }
}
