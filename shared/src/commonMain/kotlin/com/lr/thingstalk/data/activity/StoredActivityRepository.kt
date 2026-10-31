package com.lr.thingstalk.data.activity

import com.lr.thingstalk.domain.activity.*
import com.lr.thingstalk.data.panel.PanelJson
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlin.time.Clock
import kotlin.uuid.Uuid

interface ActivityStorage { fun load(): String?; suspend fun save(source: String) }
expect fun createActivityStorage(): ActivityStorage

/** Persist only bounded event summaries/templates. Raw traffic is memory-only and opt-in. */
class StoredActivityRepository(private val storage: ActivityStorage, scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() }) : ActivityRepository {
    private val json = Json { encodeDefaults = true }
    private val mutableError = MutableStateFlow<String?>(null)
    override val error = mutableError.asStateFlow()
    private var readFailed = false
    private val mutable = MutableStateFlow(try {
        storage.load()?.let { json.decodeFromString<ActivityArchive>(it).also { a ->
            require(a.version == 1 && a.retentionDays in listOf(7, 30, 90))
        } } ?: ActivityArchive()
    } catch (_: Exception) { readFailed = true; mutableError.value = "消息档案读取失败，已有数据未覆盖；请重启后重试。"; ActivityArchive() })
    override val archive = mutable.asStateFlow()
    private val traffic = MutableStateFlow<List<CommunicationRecord>>(emptyList())
    override val records = traffic.asStateFlow()
    private val capture = MutableStateFlow(false)
    override val capturePayloads = capture.asStateFlow()
    private val pending = Channel<Unit>(Channel.CONFLATED)
    private val writing = MutableStateFlow(false)
    override val saving = writing.asStateFlow()
    init {
        if (!readFailed) mutable.update { prune(it) }
        scope.launch {
            for (signal in pending) {
                delay(250) // Batch cumulative archive snapshots, never persist every raw packet.
                while (pending.tryReceive().isSuccess) { }
                if (readFailed) continue
                writing.value = true
                try { storage.save(json.encodeToString(mutable.value)); mutableError.value = null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutableError.value = "消息或模板尚未保存到本机，请检查存储后重试。" }
                finally { writing.value = false }
            }
        }
    }
    private fun prune(a: ActivityArchive) = a.copy(events = a.events.filter { it.timestamp >= now() - a.retentionDays * 86_400_000L }.take(1000))
    private fun change(f: (ActivityArchive) -> ActivityArchive) {
        if (readFailed) return
        mutable.update { prune(f(it)) }; writing.value = true; pending.trySend(Unit)
    }
    override fun retrySave() { if (!readFailed) { writing.value = true; pending.trySend(Unit) } }
    override fun record(deviceId: String, deviceName: String, transport: String, operation: String, address: String, detail: String, payload: String?, byteCount: Int): String {
        val id = Uuid.random().toString()
        val r = CommunicationRecord(id, deviceId, deviceName.take(80), transport, operation.take(80), address.take(512), now(), detail.take(2048), if (capture.value) payload?.take(4096) else null, byteCount)
        traffic.update { (listOf(r) + it).take(500) }
        return id
    }
    override fun event(deviceId: String, deviceName: String, transport: String, title: String, detail: String, warning: Boolean, recordId: String?) {
        val at = now()
        change { a ->
            // A read event is an immutable reading boundary: later occurrences get a new ID.
            val same = a.events.firstOrNull { it.deviceId == deviceId && it.transport == transport && it.title == title && it.detail == detail && at - it.timestamp in 0..30_000 && !it.read && !it.acknowledged && it.warning == warning }
            val e = if (same == null) DeviceEvent(Uuid.random().toString(), deviceId, deviceName.take(80), transport, title.take(100), detail.take(512), at, warning, recordId = recordId)
                else same.copy(timestamp = at, count = same.count + 1, recordId = recordId)
            a.copy(events = listOf(e) + a.events.filterNot { it.id == e.id })
        }
    }
    override fun markRead(id: String, read: Boolean): ReadUpdateResult = updateRead(id, read)
    override fun markAllRead(): ReadUpdateResult = updateRead(null, true)

    private fun updateRead(id: String?, read: Boolean): ReadUpdateResult {
        while (true) {
            val before = mutable.value
            if (readFailed) return ReadUpdateResult(0, before.events.count { !it.read },
                "消息档案无法读取，已读状态未修改；请先处理数据读取错误")
            if (id != null && before.events.none { it.id == id }) return ReadUpdateResult(0,
                before.events.count { !it.read }, "这条事件已清理，无法修改已读状态")
            val changed = before.events.count { (id == null || it.id == id) && it.read != read }
            val after = before.copy(events = before.events.map {
                if (id == null || it.id == id) it.copy(read = read) else it
            })
            // Retry against incoming events; report only the snapshot actually committed.
            if (!mutable.compareAndSet(before, after)) continue
            if (changed > 0) { writing.value = true; pending.trySend(Unit) }
            return ReadUpdateResult(changed, after.events.count { !it.read })
        }
    }
    override fun acknowledge(id: String) = change { a -> a.copy(events = a.events.map { if (it.id == id && it.warning) it.copy(read = true, acknowledged = true) else it }) }
    override fun clearEvents() = change { it.copy(events = emptyList()) }
    override fun clearRecords() { traffic.value = emptyList() }
    override fun setCapturePayloads(enabled: Boolean) { capture.value = enabled; if (!enabled) traffic.update { rows -> rows.map { it.copy(payload = null) } } }
    override fun setRetention(days: Int) { require(days in listOf(7,30,90)); change { it.copy(retentionDays = days) } }
    override fun setConnectionEvents(enabled: Boolean) = change { it.copy(recordConnectionEvents = enabled) }
    override fun saveTemplate(title: String, json: String) {
        check(!readFailed) { "本地档案读取失败，不能覆盖已有模板" }
        val definition = PanelJson.decode(json)
        require(title.isNotBlank() && title.length <= 80 && archive.value.templates.size < 20) { "模板名称需为 1–80 字，最多保存 20 个模板" }
        require(json.length <= 50_000) { "模板超过本地库单项 50,000 字符上限，可使用文件导入导出" }
        change { it.copy(templates = it.templates + PanelTemplate(Uuid.random().toString(), title, PanelJson.encode(definition))) }
    }
    override fun deleteTemplate(id: String) = change { it.copy(templates = it.templates.filterNot { t -> t.id == id }) }
}
