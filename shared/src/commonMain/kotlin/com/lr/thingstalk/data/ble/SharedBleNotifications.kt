package com.lr.thingstalk.data.ble

import com.lr.thingstalk.domain.ble.BleCharacteristicId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * One platform observation per UUID path. Kable 0.45 only invokes its ready action for the first
 * observer of an already connected characteristic, so readiness must be fanned out locally.
 * Each collector registers before its own ready action, which may safely write a request.
 * Values are not replayed, conflated or dropped. Each collector has a bounded inbox so the first
 * response can arrive while its ready action is still writing; a full inbox applies backpressure.
 */
internal class SharedBleNotifications(
    private val scope: CoroutineScope,
    private val source: (BleCharacteristicId, suspend () -> Unit) -> Flow<ByteArray>,
) {
    private class Subscriber {
        val ready = CompletableDeferred<Boolean>()
        val values = Channel<ByteArray>(64)
    }

    private class Observation(val session: Any, val id: BleCharacteristicId) {
        val subscribers = mutableSetOf<Subscriber>()
        var ready = false
        var stopping = false
        lateinit var job: Job
    }

    private val mutex = Mutex()
    private val observations = mutableMapOf<BleCharacteristicId, Observation>()

    /** [session] is an identity token for one connection, never reused after disconnecting. */
    fun observe(session: Any, id: BleCharacteristicId, onReady: suspend () -> Unit): Flow<ByteArray> = flow {
        val subscriber = Subscriber()
        val observation = acquire(session, id, subscriber)
        try {
            if (!subscriber.ready.await()) return@flow
            onReady()
            for (bytes in subscriber.values) emit(bytes)
        } finally {
            release(observation, subscriber)
        }
    }

    private suspend fun acquire(session: Any, id: BleCharacteristicId, subscriber: Subscriber): Observation {
        while (true) {
            var acquired: Observation? = null
            val stopping = mutex.withLock {
                val previous = observations[id]
                if (previous != null && (previous.stopping || previous.session !== session)) {
                    stop(previous)
                    previous.job
                } else {
                    val observation = previous ?: Observation(session, id).also { created ->
                        observations[id] = created
                        // Enter try/finally before the first suspension, even when scope is cancelled.
                        // ready/finalization take the mutex after this acquisition releases it.
                        created.job = scope.launch(start = CoroutineStart.UNDISPATCHED) { collect(created) }
                    }
                    observation.subscribers += subscriber
                    if (observation.ready) subscriber.ready.complete(true)
                    acquired = observation
                    null
                }
            }
            acquired?.let { return it }
            // Do not overlap the old observation's platform cleanup with a new subscription.
            // This wait is cancellable; a UI setup timeout does not have to wait for CCCD cleanup.
            stopping?.join()
            currentCoroutineContext().ensureActive()
        }
    }

    private suspend fun collect(observation: Observation) {
        var failure: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            source(observation.id) {
                mutex.withLock {
                    if (observations[observation.id] === observation && !observation.stopping) {
                        observation.ready = true
                        observation.subscribers.forEach { it.ready.complete(true) }
                    }
                }
            }.collect { bytes ->
                val subscribers = mutex.withLock {
                    if (observation.stopping) emptyList() else observation.subscribers.toList()
                }
                for (subscriber in subscribers) {
                    try { subscriber.values.send(bytes.copyOf()) }
                    catch (_: ClosedSendChannelException) { /* This local collector has left. */ }
                    catch (cancelled: CancellationException) {
                        // Cancelling one subscriber's channel must not cancel the other subscribers.
                        currentCoroutineContext().ensureActive()
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure = error
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    if (observations[observation.id] === observation) observations.remove(observation.id)
                    observation.stopping = true
                    observation.subscribers.forEach { subscriber ->
                        if (failure == null) subscriber.ready.complete(false)
                        else subscriber.ready.completeExceptionally(failure)
                        subscriber.values.close(failure)
                    }
                    observation.subscribers.clear()
                }
            }
        }
    }

    /** Must be called with [mutex] held. */
    private fun stop(observation: Observation) {
        if (observation.stopping) return
        observation.stopping = true
        observation.job.cancel()
        observation.subscribers.forEach { subscriber ->
            subscriber.ready.complete(false)
            subscriber.values.close()
        }
    }

    private suspend fun release(observation: Observation, subscriber: Subscriber) {
        withContext(NonCancellable) {
            mutex.withLock {
                observation.subscribers -= subscriber
                // Unlike close(), cancel() wakes a sender already suspended on a full inbox.
                subscriber.values.cancel()
                subscriber.ready.cancel()
                if (observation.subscribers.isEmpty()) stop(observation)
            }
        }
    }
}
