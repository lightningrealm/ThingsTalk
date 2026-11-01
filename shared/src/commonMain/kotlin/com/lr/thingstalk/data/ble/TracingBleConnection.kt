package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.TimeSource

/** Own this store outside a connection to retain diagnostics when replacing or reconnecting it. */
class InMemoryBleTraffic(
    private val limit: Int = 500,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : BleTraffic {
    init { require(limit in 1..10_000) { "Traffic history limit must be between 1 and 10000" } }

    var onEntry: (BleTrafficEntry) -> Unit = {}
    private var sensitiveProvider: () -> List<BleCharacteristicId> = { emptyList() }
    private val protectedPaths = MutableStateFlow<Set<String>>(emptySet())
    fun protect(provider: () -> List<BleCharacteristicId>) { sensitiveProvider = provider }
    private fun path(id: BleCharacteristicId) = "${id.serviceUuid.lowercase()}/${id.characteristicUuid.lowercase()}"

    private val nextId = MutableStateFlow(0L)
    private val mutableEntries = MutableStateFlow<List<BleTrafficEntry>>(emptyList())
    override val entries: StateFlow<List<BleTrafficEntry>> = mutableEntries.asStateFlow()

    override fun clear() { mutableEntries.value = emptyList() }

    internal fun record(
        direction: BleTrafficDirection,
        operation: String,
        characteristic: BleCharacteristicId? = null,
        bytes: ByteArray? = null,
        detail: String = "",
    ) {
        // Store immutable text, never the caller's reusable byte buffer. Keep both memory bounds explicit.
        protectedPaths.update { it + sensitiveProvider().map(::path) }
        val sensitive = characteristic != null && path(characteristic) in protectedPaths.value
        val payload = if (sensitive) null else bytes?.copyOf()
        val entry = BleTrafficEntry(
            id = nextId.updateAndGet { it + 1 },
            timestampMillis = now(),
            direction = direction,
            operation = operation,
            characteristic = characteristic,
            hex = payload?.let(::previewHex),
            byteCount = bytes?.size ?: 0,
            detail = if (sensitive) "设备配置数据已隐藏" else detail.take(2_048),
        )
        mutableEntries.update { (it + entry).takeLast(limit) }
        onEntry(entry)
    }

    private fun previewHex(bytes: ByteArray): String = buildString {
        val count = minOf(bytes.size, 512)
        repeat(count) { index ->
            if (index > 0) append(' ')
            val value = bytes[index].toInt() and 0xff
            append("0123456789ABCDEF"[value ushr 4])
            append("0123456789ABCDEF"[value and 0x0f])
        }
        if (count < bytes.size) append(" … (+${bytes.size - count} bytes)")
    }
}

/**
 * Transparent diagnostic decorator. It adds no retries, sharing, buffering, or reconnection.
 * The owner scope observes state once, independently of the number of UI collectors.
 * Notification entries describe each local subscription, not unique over-the-air packets.
 */
class TracingBleConnection(
    private val delegate: BleConnection,
    private val traffic: InMemoryBleTraffic,
    scope: CoroutineScope,
) : BleConnection {
    private val nextSubscription = MutableStateFlow(0L)
    override val state: Flow<BleConnectionState> = delegate.state

    private val stateJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            delegate.state.distinctUntilChanged().collect {
                traffic.record(BleTrafficDirection.SYSTEM, "connection.state", detail = it.name)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure("connection.state", null, error)
        }
    }

    override suspend fun connect() {
        val started = TimeSource.Monotonic.markNow()
        traffic.record(BleTrafficDirection.SYSTEM, "connect.request")
        trace("connect") {
            delegate.connect()
            traffic.record(BleTrafficDirection.SYSTEM, "connect.completed", detail = "Elapsed ${started.elapsedNow().inWholeMilliseconds} ms")
        }
    }

    override suspend fun disconnect() {
        traffic.record(BleTrafficDirection.SYSTEM, "disconnect.request")
        trace("disconnect") {
            delegate.disconnect()
            traffic.record(BleTrafficDirection.SYSTEM, "disconnect.completed")
        }
    }

    override suspend fun discoverServices(): List<BleService> {
        traffic.record(BleTrafficDirection.SYSTEM, "discover.request")
        return trace("discover") {
            delegate.discoverServices().also { services ->
                val characteristics = services.flatMap { it.characteristics }
                traffic.record(
                    BleTrafficDirection.SYSTEM,
                    "discover.completed",
                    detail = "${services.size} services, ${characteristics.size} characteristics, " +
                        "${characteristics.sumOf { it.descriptors.size }} descriptors",
                )
            }
        }
    }

    override suspend fun read(characteristic: BleCharacteristicId): ByteArray {
        val started = TimeSource.Monotonic.markNow()
        traffic.record(BleTrafficDirection.SYSTEM, "read.request", characteristic)
        return trace("read", characteristic) {
            delegate.read(characteristic).copyOf().also {
                traffic.record(BleTrafficDirection.RX, "read.completed", characteristic, it,
                    "Elapsed ${started.elapsedNow().inWholeMilliseconds} ms")
            }
        }
    }

    override suspend fun write(characteristic: BleCharacteristicId, value: ByteArray, mode: BleWriteMode) {
        val payload = value.copyOf()
        traffic.record(
            BleTrafficDirection.TX, "write.request", characteristic, payload,
            "Attempt ($mode); completion is recorded separately",
        )
        trace("write", characteristic) {
            delegate.write(characteristic, payload, mode)
            when (mode) {
                BleWriteMode.WITH_RESPONSE -> traffic.record(
                    BleTrafficDirection.SYSTEM, "write.gatt_acknowledged", characteristic,
                    detail = "GATT response received; this does not confirm business execution",
                )
                BleWriteMode.WITHOUT_RESPONSE -> traffic.record(
                    BleTrafficDirection.SYSTEM, "write.sent", characteristic,
                    detail = "Submitted without response; no remote acknowledgement",
                )
            }
        }
    }

    override suspend fun maximumWriteLength(mode: BleWriteMode): Int = delegate.maximumWriteLength(mode)

    override fun notifications(characteristic: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow {
        val started = TimeSource.Monotonic.markNow()
        val subscription = nextSubscription.updateAndGet { it + 1 }
        val context = "Local subscription #$subscription"
        traffic.record(BleTrafficDirection.SYSTEM, "notifications.subscribe", characteristic, detail = context)
        try {
            delegate.notifications(characteristic) {
                traffic.record(BleTrafficDirection.SYSTEM, "notifications.ready", characteristic,
                    detail = "$context; ready after ${started.elapsedNow().inWholeMilliseconds} ms")
                onReady()
            }.collect { bytes ->
                val payload = bytes.copyOf()
                traffic.record(BleTrafficDirection.RX, "notifications.value", characteristic, payload, context)
                emit(payload)
            }
            traffic.record(BleTrafficDirection.SYSTEM, "notifications.completed", characteristic, detail = context)
        } catch (cancelled: CancellationException) {
            traffic.record(BleTrafficDirection.SYSTEM, "notifications.cancelled", characteristic, detail = context)
            throw cancelled
        } catch (error: Exception) {
            failure("notifications", characteristic, error, context)
            throw error
        }
    }

    override fun close() {
        traffic.record(BleTrafficDirection.SYSTEM, "close.request")
        try {
            delegate.close()
            traffic.record(BleTrafficDirection.SYSTEM, "close.completed")
        } catch (cancelled: CancellationException) {
            traffic.record(BleTrafficDirection.SYSTEM, "close.cancelled")
            throw cancelled
        } catch (error: Exception) {
            failure("close", null, error)
            throw error
        } finally {
            stateJob.cancel()
        }
    }

    private suspend fun <T> trace(
        operation: String,
        characteristic: BleCharacteristicId? = null,
        block: suspend () -> T,
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        traffic.record(
            BleTrafficDirection.SYSTEM, "$operation.cancelled", characteristic,
            detail = "Cancelled; transport/device outcome may be unknown",
        )
        throw cancelled
    } catch (error: Exception) {
        failure(operation, characteristic, error)
        throw error
    }

    private fun failure(operation: String, characteristic: BleCharacteristicId?, error: Exception, context: String = "") {
        val cause = error.cause?.let { "; cause: ${it.message ?: it::class.simpleName}" }.orEmpty()
        traffic.record(
            BleTrafficDirection.ERROR, "$operation.failed", characteristic,
            detail = listOf(context, "${error::class.simpleName}: ${error.message.orEmpty()}$cause")
                .filter { it.isNotBlank() }.joinToString("; "),
        )
    }
}
