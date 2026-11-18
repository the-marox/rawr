package com.rawr.camera.model

/**
 * Photo aspect ratio, applied to the JPEG only (the DNG keeps the full frame).
 * [num]:[den] is in sensor orientation and turns with the phone through EXIF:
 * 3:2 held upright is a 2:3 portrait, 5:4 upright is 4:5.
 */
enum class PhotoAspect(
    val id: String, val num: Int, val den: Int, val label: String, val shortLabel: String, val group: String
) {
    Native("native", 0, 0, "Full frame (sensor)", "Full", "Base"),
    ThreeTwo("3:2", 3, 2, "3:2 35mm", "3:2", "Photo"),
    FiveFour("5:4", 5, 4, "5:4 (4:5 upright)", "5:4", "Photo"),
    Square("1:1", 1, 1, "1:1 Square", "1:1", "Photo"),
    SixteenNine("16:9", 16, 9, "16:9", "16:9", "Wide"),
    Scope("2.39", 239, 100, "2.39:1 Scope", "2.39", "Wide"),
    XPan("xpan", 65, 24, "XPan 65:24", "XPan", "Wide");

    companion object {
        fun fromId(id: String?): PhotoAspect = entries.firstOrNull { it.id == id } ?: Native
        val groups: List<String> = listOf("Base", "Photo", "Wide")
    }
}
