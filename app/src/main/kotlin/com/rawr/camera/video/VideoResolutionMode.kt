package com.rawr.camera.video

import org.json.JSONObject

data class VideoFrameSize(val width: Int, val height: Int)

enum class VideoResolutionMode(val label: String) {
    HD1080("1080p"), UHD4K("4K"), OPEN_GATE("Open Gate");

    fun next(): VideoResolutionMode = entries[(ordinal + 1) % entries.size]

    fun resolve(
        cameraSnapshot: JSONObject,
        aspect: VideoAspect = VideoAspect.Native,
        highFps: Boolean = false
    ): VideoFrameSize {
        val rawWidth = cameraSnapshot.optInt("rawWidth")
        val rawHeight = cameraSnapshot.optInt("rawHeight")
        require(rawWidth > 0 && rawHeight > 0) { "Active RAW camera geometry is unavailable" }
        // Image area without padded raw edges (lens profile cropEdges): Open Gate records just that.
        // Above 30 fps the sensor mode can pad more edges (image.cropEdgesHighFps).
        val keptWidth = (if (highFps) cameraSnapshot.optInt("keptWidthHighFps") else 0).takeIf { it in 1..rawWidth }
            ?: cameraSnapshot.optInt("keptWidth").takeIf { it in 1..rawWidth } ?: rawWidth
        val keptHeight = (if (highFps) cameraSnapshot.optInt("keptHeightHighFps") else 0).takeIf { it in 1..rawHeight }
            ?: cameraSnapshot.optInt("keptHeight").takeIf { it in 1..rawHeight } ?: rawHeight
        val size = videoCropRequest(this, aspect)?.resolve(keptWidth, keptHeight)
            ?: openGateSize(cameraSnapshot, rawWidth, rawHeight, keptWidth, keptHeight, highFps)
        require(size.width > 0 && size.height > 0 &&
                size.width <= rawWidth && size.height <= rawHeight) {
            "$label needs at least ${size.width}×${size.height} RAW; active camera is ${rawWidth}×${rawHeight}"
        }
        return size
    }

    /**
     * Open Gate: the whole image area. Above 30 fps the sensor mode has fewer
     * rows, so the frame keeps the 30 fps aspect (centred crop, like
     * MotionCam: 17U 4096x2560 -> 3408x2560, 4:3). Same rule as the native
     * videoAspectFit (the cropped side a multiple of 16).
     */
    private fun openGateSize(
        cameraSnapshot: JSONObject, rawWidth: Int, rawHeight: Int,
        keptWidth: Int, keptHeight: Int, highFps: Boolean
    ): VideoFrameSize {
        var w = keptWidth and -2
        var h = keptHeight and -2
        if (highFps) {
            val nw = cameraSnapshot.optInt("keptWidth").takeIf { it in 1..rawWidth } ?: rawWidth
            val nh = cameraSnapshot.optInt("keptHeight").takeIf { it in 1..rawHeight } ?: rawHeight
            if (w.toLong() * nh > h.toLong() * nw) {
                val fitted = ((h.toLong() * nw / nh).toInt()) and -16
                if (fitted >= 16) w = fitted
            } else if (w.toLong() * nh < h.toLong() * nw) {
                val fitted = ((w.toLong() * nh / nw).toInt()) and -16
                if (fitted >= 16) h = fitted
            }
        }
        return VideoFrameSize(w, h)
    }

    companion object {
        fun fromName(value: String?): VideoResolutionMode = when (value?.lowercase()) {
            "4k", "uhd", "2160p" -> UHD4K
            "open_gate", "open-gate", "opengate", "open gate" -> OPEN_GATE
            else -> HD1080
        }
    }
}
