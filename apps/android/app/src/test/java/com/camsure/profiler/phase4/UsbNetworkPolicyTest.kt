package com.camsure.profiler.phase4

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class UsbNetworkPolicyTest {
    @Test fun usbBurstPacingDoesNotAccumulateIdleCredit() {
        var now = 10_000_000L
        var waited = 0L
        val pacer = UsbPacketPacer({ now }, { delay -> waited += delay; now += delay })
        repeat(8) { pacer.beforePacket() }
        assertEquals(0L, waited)
        pacer.beforePacket()
        assertEquals(1_000_000L, waited)
        now += 1_000_000_000L
        repeat(8) { pacer.beforePacket() }
        assertEquals(1_000_000L, waited)
        pacer.beforePacket()
        assertEquals(2_000_000L, waited)
    }

    @Test fun usbBurstPacingRechecksEarlyWakeups() {
        var now = 10_000_000L
        var wakes = 0
        val pacer = UsbPacketPacer({ now }, { delay -> wakes++; now += if (wakes == 1) delay / 2 else delay })
        repeat(9) { pacer.beforePacket() }
        assertEquals(2, wakes)
        assertEquals(11_000_000L, now)
    }
    @Test fun subnetAndAddressPolicy() {
        val link = UsbNetworkLink("fixture", InetAddress.getByName("192.0.2.1"), 24, 1)
        assertTrue(link.acceptsPeer(InetAddress.getByName("192.0.2.2")))
        listOf("127.0.0.1", "0.0.0.0", "224.0.0.1", "169.254.1.2", "192.0.2.255", "192.0.2.0", "192.0.2.1", "198.51.100.2").forEach {
            assertFalse(it, link.acceptsPeer(InetAddress.getByName(it)))
        }
    }

    @Test fun boundedQueueStallAndEpochAbort() {
        var now = 1_000_000L
        val queue = BoundedAccessUnitQueue(maxQueuedUnits = 4, maxQueueAgeMs = 100, nowNanos = { now })
        val pool = EncodedByteArrayPool()
        fun offer(size: Int = 100, key: Boolean = false) = queue.offer(PooledPayload(pool, pool.obtain(size), size), now / 1000, 0, key)
        offer(key = true); repeat(3) { offer() }
        assertEquals(4, queue.snapshot().depth)
        offer()
        assertEquals(0, queue.snapshot().depth)
        assertTrue(queue.snapshot().waitingForKeyFrame)
        offer(key = true)
        now += 101_000_000L
        assertNull(queue.poll(0))
        assertEquals(1L, queue.snapshot().droppedAsStale)
        offer(512 * 1024 + 1, true)
        assertEquals(1L, queue.snapshot().droppedOversized)
        offer(key = true); queue.abort(); offer(key = true)
        assertEquals(0, queue.snapshot().depth)
        assertEquals(1L, queue.snapshot().droppedAfterClose)
        assertTrue(queue.isClosedAndEmpty())
        val lan = BoundedAccessUnitQueue(nowNanos = { now }).snapshot()
        assertEquals(24, lan.maxQueuedUnits); assertEquals(500L, lan.maxQueueAgeMs)
    }
}
