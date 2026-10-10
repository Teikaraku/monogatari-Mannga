package app.monogatari.android.reader

/** Coordinates match the original TapZone.swift regions, independent of writing direction. */
object TapZones {
    fun action(zone: String, x: Float, y: Float, continuous: Boolean = false): Int = when (if (zone == "auto") if (continuous) "l-shaped" else "left-right" else zone) {
        "disabled" -> 0
        "vertical" -> if (y < 1f / 3) -1 else if (y > 2f / 3) 1 else 0
        "l", "l-shaped" -> when { y < 1f / 3 || x < 1f / 3 && y < 2f / 3 -> -1; x > 2f / 3 || y > 2f / 3 -> 1; else -> 0 }
        "kindle" -> if (y < 1f / 3) 0 else if (x < 1f / 3) -1 else 1
        "edge" -> if (x < 1f / 3 || x > 2f / 3) 1 else if (y > 2f / 3) -1 else 0
        else -> if (x < 1f / 3) -1 else if (x > 2f / 3) 1 else 0
    }
}
