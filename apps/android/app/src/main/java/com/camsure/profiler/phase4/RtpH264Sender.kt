package com.camsure.profiler.phase4

import android.media.MediaCodec
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToLong

data class FixedReceiverEndpoint(
    val address: InetAddress,
    val port: Int = DEFAULT_PORT
) {
    val display: String
        get() = (address.hostAddress ?: "unknown") + ":" + port

    companion object {
        const val DEFAULT_PORT = 5004

        /** Fixed LAN trials deliberately accept numeric IPv4 addresses only. */
        fun parseIPv4(value: String): FixedReceiverEndpoint? {
            val parts = value.trim().split('.')
            if (parts.size != 4) return null
            val bytes = ByteArray(4)
            parts.forEachIndexed { index, part ->
                if (part.isEmpty() || part.length > 3 || part.any { !it.isDigit() }) return null
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                bytes[index] = octet.toByte()
            }
            if (bytes.all { it.toInt() == 0 }) return null
            val first = bytes[0].toInt() and 0xff
            if (first in 224..239) return null
            return try {
                FixedReceiverEndpoint(InetAddress.getByAddress(bytes))
            } catch (_: Exception) {
                null
            }
        }
    }
}

data class RtpH264TransportSnapshot(
    val state: String,
    val destination: String,
    val payloadType: Int,
    val packetizationMode: Int,
    val ssrc: Long,
    val timestampClockHz: Int,
    val rtpDatagramLimitBytes: Int,
    val accessUnitsSent: Long,
    val packetsSent: Long,
    val sendFailures: Long,
    val malformedAccessUnitsDropped: Long,
    val missingCodecConfigurationDrops: Long,
    val lastSentPresentationTimeUs: Long?,
    val timestampOriginPresentationTimeUs: Long?,
    val sink: AccessUnitSinkSnapshot,
    val queue: AccessUnitQueueSnapshot,
    val transportMode: String,
    val localAddress: String?,
    val localInterface: String?,
    val effectiveSendBufferBytes: Int,
    val linkState: String,
    val inFlightStaleDrops: Long,
    val currentSenderFps: Double,
    val currentRtpBitrateBps: Double
)

/** RTP/UDP experiment sender, owned by exactly one camera/encoder run. */
class RtpH264Sender(
    private val endpoint: FixedReceiverEndpoint,
    private val usbLink: UsbNetworkLink? = null,
    width: Int = 1280,
    height: Int = 720,
    private val onLinkLost: () -> Unit = {}
) : AutoCloseable {
    private val policy = SenderPolicy.forSize(width, height)
    internal val queue = BoundedAccessUnitQueue(maxQueuedBytes = policy.queuedBytes,
        maxAccessUnitBytes = policy.accessUnitBytes, maxQueuedUnits = if (usbLink == null) 24 else 4,
        maxQueueAgeMs = if (usbLink == null) 500 else 100)
    @Volatile private var effectiveSendBufferBytes = 0
    private var nextLinkCheckNs = 0L
    @Volatile private var linkState = if (usbLink == null) "not_applicable" else "checking"
    private val secureRandom = SecureRandom()
    private val ssrc = secureRandom.nextInt().toLong() and UINT32_MASK
    private val sequence = AtomicLong(secureRandom.nextInt(0x10000).toLong())
    private val packetCount = AtomicLong()
    private val sentByteCount = AtomicLong()
    private val usbPacketPacer = if (usbLink == null) null else UsbPacketPacer(burstPackets = policy.burstPackets)
    private var rateSampleAtNs = android.os.SystemClock.elapsedRealtimeNanos()
    private var rateSampleUnits = 0L
    private var rateSampleBytes = 0L
    private var senderFps = 0.0
    private var rtpBitrateBps = 0.0
    private val accessUnitCount = AtomicLong()
    private val sendFailureCount = AtomicLong()
    private val inFlightStaleCount = AtomicLong()
    private class StaleUsbAccessUnit : Exception()
    private var sendingQueuedAtNs = 0L
    private val malformedUnitCount = AtomicLong()
    private val missingConfigCount = AtomicLong()
    private val lock = Any()
    private var codecConfiguration: CodecConfiguration? = null
    @Volatile private var socket: DatagramSocket? = null
    private var timestampOriginPtsUs: Long? = null
    private val timestampBase = secureRandom.nextInt().toLong() and UINT32_MASK
    @Volatile private var state = "starting"
    @Volatile private var lastSentPtsUs: Long? = null
    @Volatile private var disposed = false
    private val worker = Thread(::sendLoop, "camsure-phase4-rtp-sender").apply {
        isDaemon = true
    }
    private val linkMonitor = if (usbLink == null) null else Thread({
        while (!disposed && worker.isAlive) {
            if (!usbLink.hasExclusivePeerRoute(endpoint.address)) {
                linkState = "lost_or_failed"
                queue.abort()
                socket?.close() // releases a worker blocked in send, independently of that worker
                worker.interrupt()
                onLinkLost()
                break
            }
            try { Thread.sleep(100) } catch (_: InterruptedException) { break }
        }
    }, "camsure-usb-link-monitor").apply { isDaemon = true }
    init { worker.start(); linkMonitor?.start() }

    internal fun setCodecConfiguration(configuration: CodecConfiguration) {
        synchronized(lock) { codecConfiguration = configuration }
    }

    fun snapshot(sink: AccessUnitSinkSnapshot): RtpH264TransportSnapshot {
        val origin = synchronized(lock) { timestampOriginPtsUs }
        val rates = synchronized(lock) {
            val now = android.os.SystemClock.elapsedRealtimeNanos()
            val seconds = (now - rateSampleAtNs) / 1_000_000_000.0
            if (seconds >= 0.5) {
                val units = accessUnitCount.get(); val bytes = sentByteCount.get()
                senderFps = (units - rateSampleUnits) / seconds
                rtpBitrateBps = (bytes - rateSampleBytes) * 8.0 / seconds
                rateSampleUnits = units; rateSampleBytes = bytes; rateSampleAtNs = now
            }
            Pair(senderFps, rtpBitrateBps)
        }
        return RtpH264TransportSnapshot(
            state = state,
            destination = endpoint.display,
            payloadType = PAYLOAD_TYPE,
            packetizationMode = PACKETIZATION_MODE,
            ssrc = ssrc,
            timestampClockHz = RTP_CLOCK_HZ,
            rtpDatagramLimitBytes = MAX_RTP_PACKET_BYTES,
            accessUnitsSent = accessUnitCount.get(),
            packetsSent = packetCount.get(),
            sendFailures = sendFailureCount.get(),
            malformedAccessUnitsDropped = malformedUnitCount.get(),
            missingCodecConfigurationDrops = missingConfigCount.get(),
            lastSentPresentationTimeUs = lastSentPtsUs,
            timestampOriginPresentationTimeUs = origin,
            sink = sink,
            queue = queue.snapshot(),
            transportMode = (if (usbLink == null) TransportMode.LAN else TransportMode.USB_NETWORK).name,
            localAddress = usbLink?.address?.hostAddress,
            localInterface = usbLink?.interfaceName,
            effectiveSendBufferBytes = effectiveSendBufferBytes,
            linkState = linkState,
            inFlightStaleDrops = inFlightStaleCount.get(),
            currentSenderFps = rates.first,
            currentRtpBitrateBps = rates.second
        )
    }

    fun finishAndDrain(timeoutMs: Long = 2_000) {
        queue.closeForDrain()
        joinWorker(timeoutMs)
    }

    override fun close() {
        if (disposed) return
        disposed = true
        queue.abort()
        try { socket?.close() } catch (_: Exception) {}
        worker.interrupt()
        linkMonitor?.interrupt()
        joinWorker(500)
        if (Thread.currentThread() !== linkMonitor) {
            try { linkMonitor?.join(500) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        state = "stopped"
    }

    private fun joinWorker(timeoutMs: Long) {
        if (Thread.currentThread() === worker) return
        try { worker.join(timeoutMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (worker.isAlive) close()
    }

    private fun checkUsbLink() {
        if (usbLink == null) return
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        if (now >= nextLinkCheckNs) {
            check(usbLink.hasExclusivePeerRoute(endpoint.address)) { "USB link lost" }
            nextLinkCheckNs = now + 100_000_000L
        }
    }

    private fun sendLoop() {
        val packetBytes = ByteArray(MAX_RTP_PACKET_BYTES)
        try {
            socket = if (usbLink == null) DatagramSocket() else DatagramSocket(null)
            if (usbLink != null) {
                check(usbLink.hasExclusivePeerRoute(endpoint.address)) { "Selected USB link/peer is unavailable" }
                socket!!.sendBufferSize = 64 * 1024
                socket!!.bind(InetSocketAddress(usbLink.address, 0))
                socket!!.connect(endpoint.address, endpoint.port)
            }
            effectiveSendBufferBytes = socket!!.sendBufferSize
            if (usbLink != null) linkState = "up"
            state = "streaming"
            while (!disposed) {
                checkUsbLink()
                val unit = queue.poll(100)
                if (unit == null) {
                    if (queue.isClosedAndEmpty()) break
                    continue
                }
                unit.use {
                    try {
                        sendingQueuedAtNs = it.queuedAtNs
                        sendAccessUnit(it, packetBytes)
                        state = "streaming"
                    } catch (_: StaleUsbAccessUnit) {
                        inFlightStaleCount.incrementAndGet()
                        queue.requestKeyFrameRecovery()
                    } catch (sendError: Exception) {
                        if (usbLink != null) throw sendError
                        sendFailureCount.incrementAndGet()
                        state = "send_error"
                        queue.requestKeyFrameRecovery()
                    }
                }
            }
            if (!disposed) state = "drained"
        } catch (error: Exception) {
            if (!disposed) {
                state = "failed"
                sendFailureCount.incrementAndGet()
                queue.abort()
                linkState = "lost_or_failed"
                onLinkLost()
            }
        } finally {
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            if (disposed) state = "stopped"
        }
    }

    private fun sendAccessUnit(unit: EncodedAccessUnit, packetBytes: ByteArray) {
        val nals = H264NalUnits.ranges(unit.bytes, unit.size)
        if (nals.isEmpty()) {
            malformedUnitCount.incrementAndGet()
            queue.requestKeyFrameRecovery()
            return
        }
        val containsIdr = H264NalUnits.containsType(nals, NAL_IDR)
        val isKeyFrame = unit.isKeyFrame || containsIdr
        val configuration = synchronized(lock) { codecConfiguration }
        val containsSps = H264NalUnits.containsType(nals, NAL_SPS)
        val containsPps = H264NalUnits.containsType(nals, NAL_PPS)
        val sendConfiguration = isKeyFrame && configuration?.isComplete == true
        if (isKeyFrame && !sendConfiguration && !(containsSps && containsPps)) {
            missingConfigCount.incrementAndGet()
            queue.requestKeyFrameRecovery()
            return
        }

        val sources = ArrayList<NalSource>()
        var configurationIncluded = containsSps && containsPps
        if (sendConfiguration) {
            configuration!!.parameterSets
                .filter { it.isNotEmpty() && ((it[0].toInt() and 0x1f) == NAL_SPS || (it[0].toInt() and 0x1f) == NAL_PPS) }
                .forEach { configNal ->
                    val type = configNal[0].toInt() and 0x1f
                    if ((type == NAL_SPS && !containsSps) || (type == NAL_PPS && !containsPps)) {
                        sources += NalSource(configNal, 0, configNal.size)
                    }
                }
            configurationIncluded = configurationIncluded || sources.isNotEmpty()
        }
        nals.forEach { sources += NalSource(unit.bytes, it.offset, it.length) }
        if (sources.isEmpty()) {
            malformedUnitCount.incrementAndGet()
            queue.requestKeyFrameRecovery()
            return
        }

        val timestamp = rtpTimestamp(unit.presentationTimeUs)
        val extensionFlags = (if (isKeyFrame) FLAG_KEY_FRAME else 0) or
            (if (configurationIncluded) FLAG_CODEC_CONFIGURATION_INCLUDED else 0)
        for (nalIndex in sources.indices) {
            val nal = sources[nalIndex]
            packetizeNal(
                source = nal,
                packetBytes = packetBytes,
                ptsUs = unit.presentationTimeUs,
                rtpTimestamp = timestamp,
                codecFlags = unit.mediaCodecFlags,
                extensionFlags = extensionFlags,
                finalNal = nalIndex == sources.lastIndex
            )
        }
        accessUnitCount.incrementAndGet()
        lastSentPtsUs = unit.presentationTimeUs
    }

    private fun packetizeNal(
        source: NalSource,
        packetBytes: ByteArray,
        ptsUs: Long,
        rtpTimestamp: Long,
        codecFlags: Int,
        extensionFlags: Int,
        finalNal: Boolean
    ) {
        val payloadCapacity = MAX_RTP_PACKET_BYTES - RTP_HEADER_BYTES
        if (source.length <= payloadCapacity) {
            sendPacket(
                packetBytes, source.bytes, source.offset, source.length, ptsUs,
                rtpTimestamp, codecFlags, extensionFlags, finalNal
            )
            return
        }
        if (source.length < 2) throw IllegalArgumentException("H.264 NAL unit is too short to fragment.")
        val nalHeader = source.bytes[source.offset].toInt() and 0xff
        val fuIndicator = (nalHeader and 0xe0) or NAL_FU_A
        val nalType = nalHeader and 0x1f
        val fragmentCapacity = payloadCapacity - 2
        var consumed = 1
        var first = true
        while (consumed < source.length) {
            val fragmentLength = minOf(fragmentCapacity, source.length - consumed)
            val last = consumed + fragmentLength == source.length
            packetBytes[RTP_HEADER_BYTES] = fuIndicator.toByte()
            packetBytes[RTP_HEADER_BYTES + 1] = (
                (if (first) FU_START else 0) or (if (last) FU_END else 0) or nalType
            ).toByte()
            source.bytes.copyInto(
                destination = packetBytes,
                destinationOffset = RTP_HEADER_BYTES + 2,
                startIndex = source.offset + consumed,
                endIndex = source.offset + consumed + fragmentLength
            )
            sendPacket(
                packetBytes = packetBytes,
                payloadBytes = packetBytes,
                payloadOffset = RTP_HEADER_BYTES,
                payloadLength = fragmentLength + 2,
                ptsUs = ptsUs,
                rtpTimestamp = rtpTimestamp,
                codecFlags = codecFlags,
                extensionFlags = extensionFlags,
                marker = finalNal && last
            )
            consumed += fragmentLength
            first = false
        }
    }

    private fun sendPacket(
        packetBytes: ByteArray,
        payloadBytes: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
        ptsUs: Long,
        rtpTimestamp: Long,
        codecFlags: Int,
        extensionFlags: Int,
        marker: Boolean
    ) {
        usbPacketPacer?.beforePacket()
        checkUsbLink()
        if (usbLink != null && android.os.SystemClock.elapsedRealtimeNanos() - sendingQueuedAtNs > 100_000_000L) throw StaleUsbAccessUnit()
        packetBytes[0] = RTP_VERSION_AND_EXTENSION.toByte()
        packetBytes[1] = (PAYLOAD_TYPE or if (marker) 0x80 else 0).toByte()
        val seq = sequence.getAndIncrement() and 0xffffL
        put16(packetBytes, 2, seq.toInt())
        put32(packetBytes, 4, rtpTimestamp)
        put32(packetBytes, 8, ssrc)
        put16(packetBytes, 12, RFC8285_ONE_BYTE_PROFILE)
        put16(packetBytes, 14, RTP_EXTENSION_WORDS)
        var ext = RTP_FIXED_HEADER_BYTES + RTP_EXTENSION_HEADER_BYTES
        packetBytes[ext++] = EXT_PTS_HEADER.toByte()
        put64(packetBytes, ext, ptsUs)
        ext += 8
        packetBytes[ext++] = EXT_CODEC_FLAGS_HEADER.toByte()
        put32(packetBytes, ext, codecFlags.toLong() and UINT32_MASK)
        ext += 4
        packetBytes[ext++] = EXT_UNIT_FLAGS_HEADER.toByte()
        packetBytes[ext] = extensionFlags.toByte()

        if (payloadBytes !== packetBytes || payloadOffset != RTP_HEADER_BYTES) {
            payloadBytes.copyInto(
                destination = packetBytes,
                destinationOffset = RTP_HEADER_BYTES,
                startIndex = payloadOffset,
                endIndex = payloadOffset + payloadLength
            )
        }
        try {
            val datagram = DatagramPacket(packetBytes, RTP_HEADER_BYTES + payloadLength, endpoint.address, endpoint.port)
            checkUsbLink()
            if (usbLink != null && android.os.SystemClock.elapsedRealtimeNanos() - sendingQueuedAtNs > 100_000_000L) throw StaleUsbAccessUnit()
            socket?.send(datagram) ?: throw IllegalStateException("RTP socket is not available.")
            packetCount.incrementAndGet()
            sentByteCount.addAndGet(datagram.length.toLong())
        } catch (error: Exception) {
            throw error
        }
    }

    private fun rtpTimestamp(ptsUs: Long): Long = synchronized(lock) {
        val origin = timestampOriginPtsUs ?: ptsUs.also { timestampOriginPtsUs = it }
        val ticks = ((ptsUs - origin).toDouble() * RTP_CLOCK_HZ.toDouble() / 1_000_000.0).roundToLong()
        (timestampBase + ticks) and UINT32_MASK
    }

    private data class NalSource(val bytes: ByteArray, val offset: Int, val length: Int)

    private fun put16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun put32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }

    private fun put64(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0..7) bytes[offset + index] = (value ushr (56 - index * 8)).toByte()
    }

    companion object {
        const val RTP_CLOCK_HZ = 90_000
        const val MAX_RTP_PACKET_BYTES = 1_200
        const val PAYLOAD_TYPE = 96
        const val PACKETIZATION_MODE = 1
        private const val NAL_SPS = 7
        private const val NAL_PPS = 8
        private const val NAL_IDR = 5
        private const val NAL_FU_A = 28
        private const val FU_START = 0x80
        private const val FU_END = 0x40
        private const val FLAG_KEY_FRAME = 0x01
        private const val FLAG_CODEC_CONFIGURATION_INCLUDED = 0x02
        private const val RTP_VERSION_AND_EXTENSION = 0x90
        private const val RTP_FIXED_HEADER_BYTES = 12
        private const val RTP_EXTENSION_HEADER_BYTES = 4
        private const val RTP_HEADER_BYTES = 32
        private const val RFC8285_ONE_BYTE_PROFILE = 0xbede
        private const val RTP_EXTENSION_WORDS = 4
        // RFC 8285 one-byte elements: ID 1 = 64-bit source PTS; ID 2 = original
        // MediaCodec BufferInfo flags; ID 3 = prototype keyframe/configuration flags.
        private const val EXT_PTS_HEADER = 0x17
        private const val EXT_CODEC_FLAGS_HEADER = 0x23
        private const val EXT_UNIT_FLAGS_HEADER = 0x30
        private const val UINT32_MASK = 0xffff_ffffL
    }
}
