package app.monogatari.android.sources

import app.monogatari.android.data.CatalogSource

/** Source compatibility follows the ported iOS app contract, independently of Android APK releases. */
object SourceCompatibility {
    const val APP_VERSION = "0.9"
    const val APP_BUILD = 3
    fun supports(source: CatalogSource): Boolean = (source.minAppVersion.isBlank() || compare(APP_VERSION, source.minAppVersion) >= 0) && (source.maxAppVersion.isBlank() || compare(APP_VERSION, source.maxAppVersion) <= 0)
    fun compare(left: String, right: String): Int {
        fun core(value: String) = value.removePrefix("v").substringBefore('+').substringBefore('-').split('.').map { it.toLongOrNull() ?: 0 }
        val a = core(left); val b = core(right)
        repeat(maxOf(a.size, b.size)) { index -> val c = a.getOrElse(index) { 0 }.compareTo(b.getOrElse(index) { 0 }); if (c != 0) return c }
        val ap = left.substringBefore('+').substringAfter('-', ""); val bp = right.substringBefore('+').substringAfter('-', "")
        if (ap.isEmpty() || bp.isEmpty()) return when { ap == bp -> 0; ap.isEmpty() -> 1; else -> -1 }
        val ai = ap.split('.'); val bi = bp.split('.')
        repeat(minOf(ai.size, bi.size)) { index -> val an = ai[index].toLongOrNull(); val bn = bi[index].toLongOrNull(); val c = when { an != null && bn != null -> an.compareTo(bn); an != null -> -1; bn != null -> 1; else -> ai[index].compareTo(bi[index]) }; if (c != 0) return c }
        return ai.size.compareTo(bi.size)
    }
}
