package com.camsure.profiler.phase4

internal data class SenderPolicy(val queuedBytes: Int, val accessUnitBytes: Int, val burstPackets: Int) {
    companion object {
        fun forSize(width: Int, height: Int): SenderPolicy =
            if (width > 1920 || height > 1080) SenderPolicy(4 * 1024 * 1024, 2 * 1024 * 1024, 32)
            else SenderPolicy(512 * 1024, 512 * 1024, 8)
    }
}
