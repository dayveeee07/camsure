package com.camsure.profiler.phase4

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

/** A complete compressed H.264 access unit; owns a pooled byte array until close(). */
internal class EncodedAccessUnit(
    private val pool: EncodedByteArrayPool,
    val bytes: ByteArray,
    val size: Int,
    val presentationTimeUs: Long,
    val mediaCodecFlags: Int,
    val isKeyFrame: Boolean,
    val queuedAtNs: Long
) : AutoCloseable {
    private val released = AtomicBoolean(false)

    override fun close() {
        if (released.compareAndSet(false, true)) pool.recycle(bytes)
    }
}

data class CodecConfiguration(
    val version: Int,
    val parameterSets: List<ByteArray>,
    val spsCount: Int,
    val ppsCount: Int
) {
    val isComplete: Boolean
        get() = spsCount > 0 && ppsCount > 0
}

data class AccessUnitSinkSnapshot(
    val completedAccessUnits: Long = 0,
    val incompleteUnitsDropped: Long = 0,
    val oversizedUnitsDropped: Long = 0,
    val malformedOutputDropped: Long = 0,
    val codecConfigurationVersion: Int = 0,
    val codecConfigurationSpsCount: Int = 0,
    val codecConfigurationPpsCount: Int = 0,
    val codecConfigurationFailures: Long = 0
)

data class CompletedAccessUnitSummary(
    val presentationTimeUs: Long,
    val sizeBytes: Int,
    val mediaCodecFlags: Int,
    val isKeyFrame: Boolean
)

data class AccessUnitQueueSnapshot(
    val maxQueuedBytes: Int,
    val maxQueueAgeMs: Long,
    val maxQueuedUnits: Int,
    val depth: Int,
    val currentBytes: Long,
    val highWaterDepth: Int,
    val highWaterBytes: Long,
    val oldestItemAgeMs: Long,
    val highWaterAgeMs: Long,
    val queuedPresentationSpanUs: Long,
    val enqueuedUnits: Long,
    val droppedForOverflow: Long,
    val droppedAsStale: Long,
    val droppedWhileWaitingForKeyFrame: Long,
    val droppedOversized: Long,
    val droppedAfterClose: Long,
    val recoveryFlushUnits: Long,
    val waitingForKeyFrame: Boolean
)

/**
 * Owns the MediaCodec output-copy and partial-frame boundary. It never performs
 * socket I/O; complete access units are handed to a bounded queue.
 */
class EncodedAccessUnitSink internal constructor(
    private val queue: BoundedAccessUnitQueue,
    private val onCodecConfiguration: (CodecConfiguration) -> Unit,
    private val onAccessUnitCompleted: (CompletedAccessUnitSummary) -> Unit
) : AutoCloseable {
    private val pool = EncodedByteArrayPool()
    private val frameBuilder = PooledByteBuilder(pool, MAX_ACCESS_UNIT_BYTES)
    private val codecConfigBuilder = PooledByteBuilder(pool, MAX_CODEC_CONFIG_BYTES)
    private var currentFramePtsUs: Long? = null
    private var currentFrameFlags = 0
    private var droppingOversizedFrame = false
    private var droppingOversizedConfig = false
    private var codecConfiguration: CodecConfiguration? = null
    private var completedUnits = 0L
    private var incompleteUnitsDropped = 0L
    private var oversizedUnitsDropped = 0L
    private var malformedOutputDropped = 0L
    private var codecConfigurationFailures = 0L
    private var closed = false

    @Synchronized
    fun onOutputFormatChanged(format: MediaFormat) {
        if (closed) return
        val csd = listOf("csd-0", "csd-1", "csd-2")
            .mapNotNull { key ->
                try {
                    if (!format.containsKey(key)) null
                    else format.getByteBuffer(key)?.let(::copyRemaining)
                } catch (_: Exception) {
                    null
                }
            }
            .filter { it.isNotEmpty() }
        if (csd.isNotEmpty()) installCodecConfiguration(csd)
    }

    /** Called on the serialized MediaCodec callback thread, before releasing index. */
    @Synchronized
    fun onOutputBuffer(buffer: ByteBuffer?, info: MediaCodec.BufferInfo) {
        if (closed) return
        val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val partial = info.flags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME != 0
        val endOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0

        if (info.size > 0) {
            if (buffer == null || info.offset < 0 || info.size > buffer.limit() - info.offset) {
                malformedOutputDropped++
                if (codecConfig) clearCodecConfigAssembly() else clearFrameAssembly()
            } else if (codecConfig) {
                if (!droppingOversizedConfig && !codecConfigBuilder.append(buffer, info.offset, info.size)) {
                    droppingOversizedConfig = true
                    oversizedUnitsDropped++
                    codecConfigBuilder.clear()
                }
                if (!partial) {
                    if (!droppingOversizedConfig) {
                        codecConfigBuilder.detach()?.let { payload ->
                            try {
                                installCodecConfiguration(listOf(payload.copyBytes()))
                            } finally {
                                payload.close()
                            }
                        }
                    }
                    clearCodecConfigAssembly()
                }
            } else {
                val oldPts = currentFramePtsUs
                if (oldPts != null && oldPts != info.presentationTimeUs) {
                    incompleteUnitsDropped++
                    clearFrameAssembly()
                }
                if (!droppingOversizedFrame) {
                    currentFramePtsUs = info.presentationTimeUs
                    currentFrameFlags = currentFrameFlags or info.flags
                    if (!frameBuilder.append(buffer, info.offset, info.size)) {
                        droppingOversizedFrame = true
                        oversizedUnitsDropped++
                        frameBuilder.clear()
                    }
                }
                if (!partial) {
                    if (!droppingOversizedFrame) finishAccessUnit()
                    clearFrameAssembly()
                }
            }
        } else if (!endOfStream && codecConfig && !partial && codecConfigBuilder.size > 0) {
            // A zero-byte non-partial callback can terminate a fragmented codec-config unit.
            if (!droppingOversizedConfig) {
                codecConfigBuilder.detach()?.let { payload ->
                    try {
                        installCodecConfiguration(listOf(payload.copyBytes()))
                    } finally {
                        payload.close()
                    }
                }
            }
            clearCodecConfigAssembly()
        } else if (!endOfStream && !codecConfig && !partial && currentFramePtsUs != null) {
            // Finish an accumulated partial frame even if the final callback carries no bytes.
            if (!droppingOversizedFrame) finishAccessUnit()
            clearFrameAssembly()
        }

        if (endOfStream) {
            if (frameBuilder.size > 0 || currentFramePtsUs != null) incompleteUnitsDropped++
            clearFrameAssembly()
            if (codecConfigBuilder.size > 0) codecConfigurationFailures++
            clearCodecConfigAssembly()
            queue.closeForDrain()
        }
    }

    @Synchronized
    fun snapshot(): AccessUnitSinkSnapshot {
        val config = codecConfiguration
        return AccessUnitSinkSnapshot(
            completedAccessUnits = completedUnits,
            incompleteUnitsDropped = incompleteUnitsDropped,
            oversizedUnitsDropped = oversizedUnitsDropped,
            malformedOutputDropped = malformedOutputDropped,
            codecConfigurationVersion = config?.version ?: 0,
            codecConfigurationSpsCount = config?.spsCount ?: 0,
            codecConfigurationPpsCount = config?.ppsCount ?: 0,
            codecConfigurationFailures = codecConfigurationFailures
        )
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        clearFrameAssembly()
        clearCodecConfigAssembly()
    }

    private fun finishAccessUnit() {
        val ptsUs = currentFramePtsUs ?: return
        val payload = frameBuilder.detach() ?: return
        val flags = currentFrameFlags and MediaCodec.BUFFER_FLAG_PARTIAL_FRAME.inv()
        val containsIdr = H264NalUnits.containsType(H264NalUnits.ranges(payload.bytes, payload.size), NAL_IDR)
        val keyFrame = (flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0 || containsIdr
        completedUnits++
        onAccessUnitCompleted(CompletedAccessUnitSummary(ptsUs, payload.size, flags, keyFrame))
        queue.offer(payload, ptsUs, flags, keyFrame)
    }

    private fun installCodecConfiguration(buffers: List<ByteArray>) {
        val nals = ArrayList<ByteArray>()
        buffers.forEach { bytes ->
            H264NalUnits.ranges(bytes, bytes.size).forEach { range ->
                if (range.nalType == 7 || range.nalType == 8 || range.nalType == 13) {
                    val nal = bytes.copyOfRange(range.offset, range.offset + range.length)
                    if (nals.none { it.contentEquals(nal) }) nals += nal
                }
            }
        }
        val spsCount = nals.count { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 7 }
        val ppsCount = nals.count { it.isNotEmpty() && (it[0].toInt() and 0x1f) == 8 }
        if (nals.isEmpty()) {
            codecConfigurationFailures++
            return
        }
        val existing = codecConfiguration
        if (existing != null && sameNals(existing.parameterSets, nals)) return

        val next = CodecConfiguration(
            version = (existing?.version ?: 0) + 1,
            parameterSets = nals,
            spsCount = spsCount,
            ppsCount = ppsCount
        )
        codecConfiguration = next
        onCodecConfiguration(next)
        if (existing != null) queue.requestKeyFrameRecovery()
    }

    private fun sameNals(left: List<ByteArray>, right: List<ByteArray>): Boolean =
        left.size == right.size && left.indices.all { left[it].contentEquals(right[it]) }

    private fun copyRemaining(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        return ByteArray(duplicate.remaining()).also { duplicate.get(it) }
    }

    private fun clearFrameAssembly() {
        frameBuilder.clear()
        currentFramePtsUs = null
        currentFrameFlags = 0
        droppingOversizedFrame = false
    }

    private fun clearCodecConfigAssembly() {
        codecConfigBuilder.clear()
        droppingOversizedConfig = false
    }

    companion object {
        const val MAX_ACCESS_UNIT_BYTES = 2 * 1024 * 1024
        private const val MAX_CODEC_CONFIG_BYTES = 256 * 1024
        private const val NAL_IDR = 5
    }
}

/** Queue bounds use a 500 ms latency budget and 512 KiB at the measured 8 Mbps baseline. */
internal class BoundedAccessUnitQueue(
    private val maxQueuedBytes: Int = 512 * 1024,
    private val maxQueueAgeMs: Long = 500,
    private val maxQueuedUnits: Int = 24,
    private val nowNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val maxAccessUnitBytes: Int = maxQueuedBytes
) {
    private data class Queued(val payload: EncodedAccessUnit, val queuedAtNs: Long)

    private val lock = java.lang.Object()
    private val items = ArrayDeque<Queued>()
    private var bytes = 0L
    private var closed = false
    private var awaitingKeyFrame = true
    private var highWaterDepth = 0
    private var highWaterBytes = 0L
    private var highWaterAgeMs = 0L
    private var enqueued = 0L
    private var overflowDrops = 0L
    private var staleDrops = 0L
    private var waitingDrops = 0L
    private var oversizedDrops = 0L
    private var closedDrops = 0L
    private var recoveryFlushUnits = 0L

    fun offer(payload: PooledPayload, ptsUs: Long, flags: Int, keyFrame: Boolean) {
        val nowNs = nowNanos()
        synchronized(lock) {
            expireLocked(nowNs)
            if (closed) {
                closedDrops++
                payload.close()
                return
            }
            if (payload.size > maxAccessUnitBytes || payload.size > maxQueuedBytes) {
                oversizedDrops++
                flushLocked { overflowDrops++ }
                awaitingKeyFrame = true
                payload.close()
                lock.notifyAll()
                return
            }
            if (awaitingKeyFrame && !keyFrame) {
                waitingDrops++
                payload.close()
                return
            }
            if (items.size + 1 > maxQueuedUnits || bytes + payload.size > maxQueuedBytes) {
                flushLocked { overflowDrops++ }
                awaitingKeyFrame = true
                if (!keyFrame) {
                    waitingDrops++
                    payload.close()
                    lock.notifyAll()
                    return
                }
            }
            if (keyFrame) awaitingKeyFrame = false
            val unit = EncodedAccessUnit(
                pool = payload.pool,
                bytes = payload.bytes,
                size = payload.size,
                presentationTimeUs = ptsUs,
                mediaCodecFlags = flags,
                isKeyFrame = keyFrame,
                queuedAtNs = nowNs
            )
            payload.transfer()
            items.addLast(Queued(unit, nowNs))
            bytes += unit.size
            enqueued++
            updateHighWaterLocked(nowNs)
            lock.notifyAll()
        }
    }

    fun poll(timeoutMs: Long): EncodedAccessUnit? {
        val deadline = nowNanos() + timeoutMs * 1_000_000L
        synchronized(lock) {
            while (items.isEmpty() && !closed) {
                val remainingNs = deadline - nowNanos()
                if (remainingNs <= 0) return null
                try {
                    lock.wait((remainingNs / 1_000_000L).coerceAtLeast(1L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return null
                }
            }
            expireLocked(nowNanos())
            if (items.isEmpty()) return null
            val item = items.removeFirst()
            bytes -= item.payload.size
            return item.payload
        }
    }

    fun requestKeyFrameRecovery() {
        synchronized(lock) {
            if (items.isNotEmpty()) {
                recoveryFlushUnits += items.size
                flushLocked { }
            }
            awaitingKeyFrame = true
            lock.notifyAll()
        }
    }

    fun closeForDrain() {
        synchronized(lock) {
            closed = true
            lock.notifyAll()
        }
    }

    fun isClosedAndEmpty(): Boolean = synchronized(lock) { closed && items.isEmpty() }

    fun abort() {
        synchronized(lock) {
            closed = true
            recoveryFlushUnits += items.size
            flushLocked { }
            lock.notifyAll()
        }
    }

    fun snapshot(): AccessUnitQueueSnapshot = synchronized(lock) {
        val nowNs = nowNanos()
        updateHighWaterLocked(nowNs)
        val oldest = items.peekFirst()
        val newest = items.peekLast()
        AccessUnitQueueSnapshot(
            maxQueuedBytes = maxQueuedBytes,
            maxQueueAgeMs = maxQueueAgeMs,
            maxQueuedUnits = maxQueuedUnits,
            depth = items.size,
            currentBytes = bytes,
            highWaterDepth = highWaterDepth,
            highWaterBytes = highWaterBytes,
            oldestItemAgeMs = oldest?.let { ageMs(nowNs, it.queuedAtNs) } ?: 0,
            highWaterAgeMs = highWaterAgeMs,
            queuedPresentationSpanUs = if (oldest != null && newest != null) {
                (newest.payload.presentationTimeUs - oldest.payload.presentationTimeUs).coerceAtLeast(0L)
            } else 0L,
            enqueuedUnits = enqueued,
            droppedForOverflow = overflowDrops,
            droppedAsStale = staleDrops,
            droppedWhileWaitingForKeyFrame = waitingDrops,
            droppedOversized = oversizedDrops,
            droppedAfterClose = closedDrops,
            recoveryFlushUnits = recoveryFlushUnits,
            waitingForKeyFrame = awaitingKeyFrame
        )
    }

    private fun expireLocked(nowNs: Long) {
        val oldest = items.peekFirst() ?: return
        updateHighWaterLocked(nowNs)
        val ageLimitNs = maxQueueAgeMs * 1_000_000L
        if (nowNs - oldest.queuedAtNs > ageLimitNs) {
            flushLocked { staleDrops++ }
            awaitingKeyFrame = true
        }
    }

    private fun flushLocked(onDrop: () -> Unit) {
        while (items.isNotEmpty()) {
            val item = items.removeFirst()
            bytes -= item.payload.size
            onDrop()
            item.payload.close()
        }
        bytes = 0L
    }

    private fun updateHighWaterLocked(nowNs: Long) {
        highWaterDepth = maxOf(highWaterDepth, items.size)
        highWaterBytes = maxOf(highWaterBytes, bytes)
        items.peekFirst()?.let { highWaterAgeMs = maxOf(highWaterAgeMs, ageMs(nowNs, it.queuedAtNs)) }
    }

    private fun ageMs(nowNs: Long, queuedAtNs: Long): Long =
        ((nowNs - queuedAtNs).coerceAtLeast(0L)) / 1_000_000L
}

internal class EncodedByteArrayPool(
    private val maxRetainedBytes: Int = 4 * 1024 * 1024,
    private val maxRetainedArrays: Int = 12
) {
    private val lock = Any()
    private val buckets = HashMap<Int, ArrayDeque<ByteArray>>()
    private var retainedBytes = 0
    private var retainedArrays = 0

    fun obtain(minimumCapacity: Int): ByteArray {
        val requested = bucketCapacity(minimumCapacity)
        synchronized(lock) {
            val availableCapacity = buckets.keys.filter { it >= requested }.minOrNull()
            if (availableCapacity != null) {
                val bucket = buckets[availableCapacity]!!
                val result = bucket.removeFirst()
                if (bucket.isEmpty()) buckets.remove(availableCapacity)
                retainedBytes -= result.size
                retainedArrays--
                return result
            }
        }
        return ByteArray(requested)
    }

    fun recycle(bytes: ByteArray) {
        synchronized(lock) {
            if (retainedArrays >= maxRetainedArrays || retainedBytes + bytes.size > maxRetainedBytes) return
            buckets.getOrPut(bytes.size) { ArrayDeque() }.addLast(bytes)
            retainedBytes += bytes.size
            retainedArrays++
        }
    }

    private fun bucketCapacity(minimum: Int): Int {
        var capacity = 4096
        while (capacity < minimum && capacity < EncodedAccessUnitSink.MAX_ACCESS_UNIT_BYTES) capacity *= 2
        return capacity
    }
}

private class PooledByteBuilder(
    private val pool: EncodedByteArrayPool,
    private val maximumBytes: Int
) {
    private var storage: ByteArray? = null
    var size: Int = 0
        private set

    fun append(source: ByteBuffer, offset: Int, length: Int): Boolean {
        if (length < 0 || offset < 0 || length > source.limit() - offset || size + length > maximumBytes) return false
        ensureCapacity(size + length)
        val duplicate = source.duplicate()
        duplicate.position(offset)
        duplicate.limit(offset + length)
        duplicate.get(storage!!, size, length)
        size += length
        return true
    }

    fun append(source: ByteArray): Boolean {
        if (size + source.size > maximumBytes) return false
        ensureCapacity(size + source.size)
        source.copyInto(storage!!, size)
        size += source.size
        return true
    }

    fun detach(): PooledPayload? {
        val bytes = storage ?: return null
        val payload = PooledPayload(pool, bytes, size)
        storage = null
        size = 0
        return payload
    }

    fun clear() {
        storage?.let(pool::recycle)
        storage = null
        size = 0
    }

    private fun ensureCapacity(required: Int) {
        if (required == 0) return
        val current = storage
        if (current != null && current.size >= required) return
        val next = pool.obtain(required)
        if (current != null) {
            current.copyInto(next, 0, 0, size)
            pool.recycle(current)
        }
        storage = next
    }
}

internal class PooledPayload(
    internal val pool: EncodedByteArrayPool,
    internal val bytes: ByteArray,
    val size: Int
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    private val transferred = AtomicBoolean(false)

    fun copyBytes(): ByteArray = bytes.copyOf(size)

    fun transfer() {
        transferred.set(true)
    }

    override fun close() {
        if (!transferred.get() && released.compareAndSet(false, true)) pool.recycle(bytes)
    }
}

internal data class NalRange(val offset: Int, val length: Int, val nalType: Int)

internal object H264NalUnits {
    fun ranges(bytes: ByteArray, size: Int): List<NalRange> {
        if (size <= 0 || size > bytes.size) return emptyList()
        val first = findStartCode(bytes, 0, size)
        if (first == null) {
            val type = bytes[0].toInt() and 0x1f
            return if (type in 1..23) listOf(NalRange(0, size, type)) else emptyList()
        }
        if ((0 until first.offset).any { bytes[it].toInt() != 0 }) return emptyList()
        val ranges = ArrayList<NalRange>()
        var start = first
        while (start != null) {
            val nalOffset = start.offset + start.prefixLength
            val next = findStartCode(bytes, nalOffset, size)
            var nalEnd = next?.offset ?: size
            while (nalEnd > nalOffset && bytes[nalEnd - 1].toInt() == 0) nalEnd--
            if (nalEnd > nalOffset) {
                val type = bytes[nalOffset].toInt() and 0x1f
                if (type !in 1..23) return emptyList()
                ranges += NalRange(nalOffset, nalEnd - nalOffset, type)
            }
            start = next
        }
        return ranges
    }

    fun containsType(ranges: List<NalRange>, type: Int): Boolean = ranges.any { it.nalType == type }

    private data class StartCode(val offset: Int, val prefixLength: Int)

    private fun findStartCode(bytes: ByteArray, from: Int, size: Int): StartCode? {
        var i = from
        while (i + 2 < size) {
            if (bytes[i].toInt() == 0 && bytes[i + 1].toInt() == 0) {
                if (i + 3 < size && bytes[i + 2].toInt() == 0 && bytes[i + 3].toInt() == 1) {
                    return StartCode(i, 4)
                }
                if (bytes[i + 2].toInt() == 1) return StartCode(i, 3)
            }
            i++
        }
        return null
    }
}
