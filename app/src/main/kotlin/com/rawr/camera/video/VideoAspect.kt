package com.rawr.camera.video

/**
 * Recording aspect ratio inside the selected resolution: a centred crop of the
 * 1080p, 4K or Open Gate frame. [num]:[den] 0:0 keeps the frame as it is.
 * Native mirrors [fit] exactly (video_pipeline/VideoCrop.h videoAspectFit) for
 * the viewfinder crop and the prewarm, so both must stay in step.
 */
enum class VideoAspect(val id: String, val num: Int, val den: Int, val label: String, val shortLabel: String) {
    Native("native", 0, 0, "Native", "Off"),
    Flat("1.85", 185, 100, "1.85:1", "1.85"),
    Univisium("2.00", 2, 1, "2:1", "2:1"),
    Scope("2.39", 239, 100, "2.39:1", "2.39"),
    XPan("xpan", 65, 24, "XPan 65:24", "XPan");

    /** The largest [num]:[den] frame inside [size]; the cropped side is a multiple of 16. */
    fun fit(size: VideoFrameSize): VideoFrameSize {
        if (num <= 0 || den <= 0 || size.width <= 0 || size.height <= 0) return size
        val w = size.width.toLong()
        val h = size.height.toLong()
        return when {
            w * den < h * num -> {
                val nh = ((w * den / num).toInt()) and 15.inv()
                if (nh >= 16) VideoFrameSize(size.width, nh) else size
            }
            w * den > h * num -> {
                val nw = ((h * num / den).toInt()) and 15.inv()
                if (nw >= 16) VideoFrameSize(nw, size.height) else size
            }
            else -> size
        }
    }

    companion object {
        fun fromId(id: String?): VideoAspect = entries.firstOrNull { it.id == id } ?: Native
    }
}
