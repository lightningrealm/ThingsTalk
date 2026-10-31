package com.lr.thingstalk.panel

import com.lr.thingstalk.data.device.DeviceStorage
import com.lr.thingstalk.data.device.JsonDeviceRepository
import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.data.panel.defaultDemoPanel
import com.lr.thingstalk.domain.panel.*
import com.lr.thingstalk.presentation.panel.editablePanelDefinition
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class PanelMigrationTest {
    @Test fun legacyReadModesAreNormalizedWithoutChangingWritePayloadsOrLayout() {
        val legacy = defaultDemoPanel().let { definition -> definition.copy(bindings = definition.bindings.map { binding ->
            binding.copy(read = binding.read?.copy(writeMode = PanelWriteMode.WITHOUT_RESPONSE),
                notify = binding.notify?.copy(writeMode = PanelWriteMode.WITHOUT_RESPONSE))
        }) }
        val imported = PanelJson.decode(PanelJson.encode(legacy))
        assertEquals(1, imported.schemaVersion)
        val edited = editablePanelDefinition(imported)
        assertEquals(2, edited.schemaVersion)
        assertTrue(validatePanel(edited).isEmpty(), validatePanel(edited).joinToString())
        assertEquals(imported.widgets, edited.widgets)
        assertEquals(imported.capabilities, edited.capabilities)
        imported.bindings.zip(edited.bindings).forEach { (old, new) ->
            assertEquals(old.write, new.write)
            assertEquals(old.actionValue, new.actionValue)
            listOfNotNull(new.read, new.notify).forEach { assertEquals(PanelWriteMode.WITH_RESPONSE, it.writeMode) }
        }
        assertEquals(edited, PanelJson.decode(PanelJson.encode(edited)))
    }

    @Test fun legacyAndAdvancedPanelsSurviveSavingAndReloadingTheSameDeviceArchive() = runTest {
        val storage = object : DeviceStorage {
            var source: String? = null
            override fun load() = source
            override fun save(source: String) { this.source = source }
        }
        var next = 0
        val repository = JsonDeviceRepository(storage) { "device-${++next}" }
        val first = repository.create("Legacy")
        val second = repository.create("Advanced")
        assertEquals(2, second.panel.schemaVersion)
        val legacy = defaultDemoPanel()
        repository.savePanel(first.id, legacy)
        val advanced = editablePanelDefinition(legacy).let { definition -> definition.copy(bindings = definition.bindings.map { binding ->
            if (binding.capabilityId != "temperature") binding else binding.copy(
                read = binding.read?.copy(codec = CodecType.INT16, readOffset = 1, scale = 0.01),
                notify = binding.notify?.copy(codec = CodecType.INT16, readOffset = 1, scale = 0.01),
            )
        }) }
        repository.savePanel(second.id, advanced)
        val reloaded = JsonDeviceRepository(storage).devices.value
        assertEquals(legacy, reloaded.single { it.id == first.id }.panel)
        assertEquals(advanced, reloaded.single { it.id == second.id }.panel)
    }
}
