package com.camsure.profiler.phase4

/** Bounds USB send bursts without accumulating credit during idle periods. Sender-thread only. */
internal class UsbPacketPacer(
    private val nowNanos: () -> Long = System::nanoTime,
    private val waitNanos: (Long) -> Unit = { Thread.sleep(it / 1_000_000, (it % 1_000_000).toInt()) },
    private val burstPackets: Int = 8
) {
    private var startedAt: Long? = null
    private var packets = 0

    fun beforePacket() {
        var now = nowNanos()
        val start = startedAt
        if (start == null || now - start >= 1_000_000L) {
            startedAt = now
            packets = 0
        } else if (packets >= burstPackets) {
            // Recheck after early wakeups. Interrupts propagate to the sender's shutdown path.
            while (now - start < 1_000_000L) {
                waitNanos(1_000_000L - (now - start))
                now = nowNanos()
            }
            startedAt = now
            packets = 0
        }
        packets++
    }
}
