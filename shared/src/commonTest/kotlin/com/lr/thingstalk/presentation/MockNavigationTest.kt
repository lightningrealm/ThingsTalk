package com.lr.thingstalk.presentation

import com.lr.thingstalk.presentation.mock.MockSession
import com.lr.thingstalk.presentation.navigation.Route
import com.lr.thingstalk.presentation.navigation.Screen
import kotlinx.serialization.json.Json
import kotlin.test.*

class MockNavigationTest {
    @Test fun allDestinationsRoundTripWithIds() {
        Screen.entries.forEach { screen ->
            val route = Route(screen, "设备-01", "0000180f-0000-1000-8000-00805f9b34fb", "00002a19-0000-1000-8000-00805f9b34fb")
            assertEquals(route, Json.decodeFromString<Route>(Json.encodeToString(route)))
        }
    }
    @Test fun commandAcknowledgementIsNotExecutionSuccess() {
        val session = MockSession()
        val id = session.command("闪灯定位")
        assertEquals("排队中", session.commands.first().status)
        session.advanceCommand(id)
        assertEquals("已发送", session.commands.first().status)
        session.advanceCommand(id)
        assertEquals("执行成功", session.commands.first().status)
        session.advanceCommand(id)
        assertEquals("执行成功", session.commands.first().status)
    }
    @Test fun timeoutAndDisconnectAreExplicit() {
        val session = MockSession()
        session.nextCommandFails = true
        val id = session.command("设置阈值")
        repeat(2) { session.advanceCommand(id) }
        assertEquals("执行超时", session.commands.first().status)
        assertEquals(2, session.commands.size) // No automatic retry.
        session.connected = true
        session.observing = true
        session.disconnect()
        assertFalse(session.observing)
        assertFalse(session.connected)
    }
    @Test fun logsRemainBounded() {
        val session = MockSession()
        repeat(120) { session.log("RX $it") }
        assertEquals(100, session.logs.size)
        assertEquals("[DEMO] RX 119", session.logs.first())
    }
}
