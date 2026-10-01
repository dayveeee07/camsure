package com.camsure.profiler.session

/** Preview and operator streaming have no deadline; diagnostics retain their old gates. */
object CameraRunPolicy {
    fun deadlineMs(previewOnly: Boolean, continuous: Boolean, hasReceiver: Boolean): Long? =
        if (previewOnly || continuous) null else if (hasReceiver) 600_000L else 300_000L

    fun active(state: String): Boolean = state in setOf("preparing", "opening_camera", "running", "stopping")

    fun restoredIndex(keys: List<String>, saved: String?): Int? =
        keys.indexOf(saved).takeIf { it >= 0 }
}
