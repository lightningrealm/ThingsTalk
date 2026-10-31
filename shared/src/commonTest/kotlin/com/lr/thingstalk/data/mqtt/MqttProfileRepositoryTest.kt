package com.lr.thingstalk.data.mqtt

import com.lr.thingstalk.domain.mqtt.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*

class MqttProfileRepositoryTest {
    private fun profile(id: String = "one") = MqttProfile(id, "Local broker", "wss://broker.example/mqtt", username = "test-user", password = "test-secret")
    @Test fun profilesAndPerOwnerSelectionsSurviveReload() = runTest {
        val storage = MemoryStorage(true)
        val repo = StoredMqttProfileRepository(storage); repo.load()
        repo.save(profile(), "workbench"); repo.save(profile("two"), "device:one")
        val reloaded = StoredMqttProfileRepository(storage); reloaded.load()
        assertEquals("one", reloaded.state.value.selectedProfile("workbench")?.id)
        assertEquals("two", reloaded.state.value.selectedProfile("device:one")?.id)
        assertEquals("test-secret", reloaded.state.value.selectedProfile("workbench")?.connectionConfig()?.password)
        assertEquals("test-user", reloaded.state.value.selectedProfile("workbench")?.connectionConfig()?.username)
    }
    @Test fun webPasswordRemainsInMemoryButNeverReachesPersistentStorage() = runTest {
        val storage = MemoryStorage(false)
        val repo = StoredMqttProfileRepository(storage); repo.load(); repo.save(profile(), "workbench")
        assertFalse(storage.source!!.contains("test-secret"))
        assertEquals("test-secret", repo.state.value.selectedProfile("workbench")?.connectionConfig()?.password)
        val reloaded = StoredMqttProfileRepository(storage); reloaded.load()
        assertFailsWith<MqttException> { reloaded.state.value.selectedProfile("workbench")!!.connectionConfig() }
        val missing = reloaded.state.value.profiles.single()
        reloaded.save(missing.copy(password = "replacement"), "workbench")
        assertEquals("replacement", reloaded.state.value.selectedProfile("workbench")?.connectionConfig()?.password)
        assertFalse(storage.source!!.contains("replacement"))
        reloaded.save(missing.copy(password = "", requiresPassword = false), "workbench")
        assertNull(reloaded.state.value.selectedProfile("workbench")?.connectionConfig()?.password)
    }
    @Test fun browserBoundaryStripsAccidentallyPassedPasswordAndRejectsUrlCredentials() {
        val source = Json.encodeToString(MqttProfileArchive(profiles = listOf(profile())))
        val filtered = mqttProfileMetadataOnly(source)
        assertFalse(filtered.contains("test-secret"))
        val metadata = Json.decodeFromString<MqttProfileArchive>(filtered).profiles.single()
        assertTrue(metadata.requiresPassword)
        assertEquals("test-user", metadata.username)
        assertFailsWith<MqttException> { mqttProfileMetadataOnly(Json.encodeToString(MqttProfileArchive(profiles = listOf(profile().copy(endpoint = "wss://user:secret@broker.example/mqtt"))))) }
    }
    @Test fun failedSaveOrCorruptLoadNeverErasesPreviousData() = runTest {
        val storage = MemoryStorage(true)
        val repo = StoredMqttProfileRepository(storage); repo.load(); repo.save(profile(), "workbench")
        val previous = storage.source; val state = repo.state.value
        storage.fail = true
        assertFailsWith<IllegalStateException> { repo.delete("one") }
        assertEquals(previous, storage.source); assertEquals(state, repo.state.value)
        storage.fail = false; storage.source = "corrupt"
        val broken = StoredMqttProfileRepository(storage)
        assertFails { broken.load() }
        assertFailsWith<IllegalStateException> { broken.save(profile(), "workbench") }
        assertEquals("corrupt", storage.source)
    }
    @Test fun deleteClearsAllReferencesAndInvalidSelectionIsRejected() = runTest {
        val repo = StoredMqttProfileRepository(MemoryStorage(true)); repo.load()
        repo.save(profile(), "workbench"); repo.select("device:a", "one")
        repo.save(profile("two"), "device:b"); repo.delete("one")
        assertNull(repo.state.value.selectedProfile("workbench"))
        assertNull(repo.state.value.selectedProfile("device:a"))
        assertEquals("two", repo.state.value.selectedProfile("device:b")?.id)
        assertFailsWith<IllegalArgumentException> { repo.select("workbench", "deleted") }
    }
    @Test fun automaticIdsAreIndependentAndFixedIdIsPreserved() {
        assertNotEquals(profile().connectionConfig().clientId, profile().connectionConfig().clientId)
        assertEquals("assigned-id", profile().copy(automaticClientId = false, clientId = "assigned-id").connectionConfig().clientId)
        assertFalse(profile().toString().contains("test-secret"))
        assertFalse(profile().connectionConfig().toString().contains("test-secret"))
    }
    @Test fun invalidConnectionProfilesCannotBeSaved() = runTest {
        val repo = StoredMqttProfileRepository(MemoryStorage(true)); repo.load()
        for (invalid in listOf(profile().copy(name = ""), profile().copy(endpoint = "mqtt://broker.example"),
            profile().copy(automaticClientId = false, clientId = ""), profile().copy(endpoint = "wss://broker.example/mqtt?token=test"))) {
            assertFails { repo.save(invalid, "workbench") }
            assertTrue(repo.state.value.profiles.isEmpty())
        }
    }
    private class MemoryStorage(override val passwordsPersisted: Boolean) : MqttProfileStorage {
        var source: String? = null
        var fail = false
        override suspend fun load() = source
        override suspend fun save(source: String) { check(!fail); this.source = source }
    }
}
