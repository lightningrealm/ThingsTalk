package com.lr.thingstalk.data.ble

import com.juul.kable.Advertisement
import com.lr.thingstalk.domain.ble.BleException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class KableBleScannerTest {
    @Test
    fun savedIdentifierRestoresWithoutStartingScanAndStillHonorsClose() {
        val expected = "00000000-0000-0000-0000-000000000001"
        val restored = com.lr.thingstalk.data.panel.DemoPanelBleConnection()
        var scans = 0
        var restores = 0
        val scanner = KableBleScanner(restoreConnection = { id ->
            restores++
            if (id == expected) restored else null
        }) { scans++; flow { } }
        assertTrue(scanner.connection(expected) === restored)
        assertEquals(0, scans)
        assertFailsWith<BleException> { scanner.connection("missing") }
        assertEquals(2, restores)
        scanner.close()
        assertFailsWith<IllegalStateException> { scanner.connection(expected) }
        assertEquals(2, restores)
    }

    @Test
    fun startsOnlyWhenCollectedAndCancellationStopsTheNativeSource() = runTest {
        var active = false
        var starts = 0
        val scanner = KableBleScanner {
            flow<Advertisement> {
                starts++
                active = true
                try { awaitCancellation() } finally { active = false }
            }
        }
        val advertisements = scanner.scan()
        assertEquals(0, starts)
        val job = launch { advertisements.collect() }
        runCurrent()
        assertTrue(active)
        job.cancelAndJoin()
        assertFalse(active)
        val second = launch { scanner.scan().collect() }
        runCurrent()
        assertEquals(2, starts)
        second.cancelAndJoin()
        scanner.close()
    }

    @Test
    fun closeStopsAnActiveSourceAndRejectsReuse() = runTest {
        var active = false
        val scanner = KableBleScanner {
            flow<Advertisement> {
                active = true
                try { awaitCancellation() } finally { active = false }
            }
        }
        val job = launch { scanner.scan().collect() }
        runCurrent()
        assertTrue(active)
        scanner.close()
        job.join()
        assertFalse(active)
        assertFailsWith<BleException> { scanner.scan().collect() }
        assertFailsWith<IllegalStateException> { scanner.connection("missing") }
    }

    @Test
    fun concurrentCollectorsAreRejectedWithoutStoppingTheOriginal() = runTest {
        var starts = 0
        val scanner = KableBleScanner { flow<Advertisement> { starts++; awaitCancellation() } }
        val original = launch { scanner.scan().collect() }
        runCurrent()
        var error: Throwable? = null
        scanner.scan().catch { error = it }.collect()
        assertIs<BleException>(error)
        assertEquals(1, starts)
        assertTrue(original.isActive)
        original.cancelAndJoin()
        scanner.close()
    }

    @Test
    fun nativeErrorsAreReportedAndPermitARetry() = runTest {
        var starts = 0
        val scanner = KableBleScanner {
            flow<Advertisement> { starts++; error("radio unavailable") }
        }
        repeat(2) {
            val error = assertFailsWith<BleException> { scanner.scan().collect() }
            assertEquals("radio unavailable", error.cause?.message)
        }
        assertEquals(2, starts)
        scanner.close()
    }
}
