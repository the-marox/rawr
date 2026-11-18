package com.rawr.camera.video

/**
 * Recording aspect ratio: a centred crop of the 1080p, 4K or Open Gate frame.
 * [num]:[den] 0:0 keeps the frame. The frame stays horizontal in the world:
 * with the phone upright the crop runs along the sensor's short side
 * ([cropRequest]). Native mirrors the rule (SessionEngine::videoFrameLocked,
 * VideoCrop.h videoAspectFit) for the viewfinder and the prewarm.
 */
enum class VideoAspect(
    val id: String, val num: Int, val den: Int, val label: String, val shortLabel: String, val group: String
) {
    Native("native", 0, 0, "Full frame", "Full", "Base"),
    Imax("1.43", 143, 100, "1.43:1 IMAX 70mm", "1.43", "Digital and IMAX"),
    European("1.66", 166, 100, "1.66:1 European", "1.66", "Classic"),
    Flat("1.85", 185, 100, "1.85:1 Flat", "1.85", "Classic"),
    Dci("1.90", 256, 135, "1.90:1 DCI / IMAX digital", "1.90", "Digital and IMAX"),
    Univisium("2.00", 2, 1, "2:1 Univisium", "2:1", "Digital and IMAX"),
    ToddAo("2.20", 220, 100, "2.20:1 Todd-AO 70mm", "2.20", "Wide"),
    Scope("2.39", 239, 100, "2.39:1 Scope", "2.39", "Wide"),
    UltraPanavision("2.76", 276, 100, "2.76:1 Ultra Panavision 70", "2.76", "Wide"),
    XPan("xpan", 65, 24, "XPan 65:24", "XPan", "Photo");

    companion object {
        fun fromId(id: String?): VideoAspect = entries.firstOrNull { it.id == id } ?: Native
        val groups: List<String> = listOf("Base", "Classic", "Digital and IMAX", "Wide", "Photo")
    }
}

/**
 * What the recorder frames: a base size (0x0 = the image area, Open Gate), an
 * aspect crop in sensor orientation, and an optional 2x reduction.
 */
data class VideoCropRequest(val width: Int, val height: Int, val num: Int, val den: Int, val halve: Boolean) {
    /** Recording size for the active RAW; keptWidth/Height = RAW without padded edges. */
    fun resolve(keptWidth: Int, keptHeight: Int): VideoFrameSize {
        var size = if (width <= 0 || height <= 0) VideoFrameSize(keptWidth and -2, keptHeight and -2)
        else VideoFrameSize(width, height)
        size = aspectFit(size, num, den)
        if (halve) size = VideoFrameSize((size.width / 2) and 15.inv(), (size.height / 2) and 15.inv())
        return size
    }

    companion object {
        /** The largest [num]:[den] frame inside [size]; the cropped side is a multiple of 16. */
        fun aspectFit(size: VideoFrameSize, num: Int, den: Int): VideoFrameSize {
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
    }
}

/**
 * Crop for [mode] at [aspect] with the phone [portrait] or not; null = no crop
 * (Open Gate, full frame). Upright, a horizontal frame comes from the sensor's
 * short side (the aspect is turned), so 4K and Open Gate give the same size and
 * 1080p is that size halved.
 */
fun videoCropRequest(mode: VideoResolutionMode, aspect: VideoAspect, portrait: Boolean): VideoCropRequest? {
    if (aspect == VideoAspect.Native || !portrait) {
        val n = aspect.num
        val d = aspect.den
        return when (mode) {
            VideoResolutionMode.HD1080 -> VideoCropRequest(1920, 1080, n, d, false)
            VideoResolutionMode.UHD4K -> VideoCropRequest(3840, 2160, n, d, false)
            VideoResolutionMode.OPEN_GATE ->
                if (aspect == VideoAspect.Native) null else VideoCropRequest(0, 0, n, d, false)
        }
    }
    return VideoCropRequest(0, 0, aspect.den, aspect.num, mode == VideoResolutionMode.HD1080)
}
