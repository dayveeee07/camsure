package com.camsure.profiler

/** Display-only correction for TextureView's already sensor-oriented camera buffer. */
object PreviewGeometry {
    data class Transform(val scaleX: Float, val scaleY: Float, val rotationDegrees: Float)

    fun centerCrop(
        viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int,
        sensorDegrees: Int, displayDegrees: Int
    ): Transform {
        require(viewWidth > 0 && viewHeight > 0 && bufferWidth > 0 && bufferHeight > 0)
        // The camera producer has already applied sensor orientation to this texture.
        val sensorSwapsAxes = sensorDegrees % 180 != 0
        val naturalWidth = (if (sensorSwapsAxes) bufferHeight else bufferWidth).toFloat()
        val naturalHeight = (if (sensorSwapsAxes) bufferWidth else bufferHeight).toFloat()
        val displaySwapsAxes = displayDegrees % 180 != 0
        val uprightWidth = if (displaySwapsAxes) naturalHeight else naturalWidth
        val uprightHeight = if (displaySwapsAxes) naturalWidth else naturalHeight
        val fill = maxOf(viewWidth / uprightWidth, viewHeight / uprightHeight)
        // Undo TextureView's nonuniform stretch, then apply one uniform fill scale.
        // Camera-producer front mirroring is retained; do not add another flip.
        return Transform(
            naturalWidth / viewWidth * fill,
            naturalHeight / viewHeight * fill,
            -displayDegrees.toFloat()
        )
    }
}
