package app.monogatari.android.reader

import android.content.Context
import org.json.JSONObject
import app.monogatari.android.data.SettingsStore

/** Android equivalents of the original Reader.* settings, including per-series overrides. */
data class ReaderOptions(
    val mode: String = "auto", val tapZones: String = "disabled", val invertTapZones: Boolean = false,
    val animateTransitions: Boolean = true, val hideBarsOnSwipe: Boolean = false,
    val pageLayout: String = "auto", val pageOffset: Boolean = false, val splitWideImages: Boolean = false,
    val reverseSplitOrder: Boolean = false, val cropBorders: Boolean = false, val downsample: Boolean = false,
    val upscale: Boolean = false, val upscaleMaxHeight: Int = 2000, val disableDoubleTap: Boolean = false,
    val fit: String = "fit", val background: String = "black", val grayscale: Boolean = false,
    val invertColors: Boolean = false, val contrast: Float = 1f, val colorTint: Long = 0L,
    val tintOpacity: Float = 0f, val brightness: Float = -1f, val preloadPages: Int = 2,
    val infiniteScroll: Boolean = true, val autoScrollSpeed: Int = 5, val autoScrollPosition: String = "right",
    val volumeKeys: Boolean = false, val orientation: String = "system", val fullscreen: Boolean = true,
    val keepScreenOn: Boolean = true, val showPageNumbers: Boolean = true, val textStyle: String = "scroll",
    val textTheme: String = "system", val textFont: String = "system", val fontSize: Int = 18,
    val lineSpacing: Float = 1.65f, val paragraphSpacing: Int = 8, val textMargins: Int = 24,
    val skipDuplicates: Boolean = true, val markDuplicates: Boolean = true,
    val pillarbox: Boolean = false, val pillarboxAmount: Int = 15, val pillarboxOrientation: String = "both",
    val disableQuickActions: Boolean = false, val autoScrollButton: Boolean = false,
    val liveText: Boolean = false, val textAppearance: String = "system",
) {
    /** Native additions are layered without allowing their cached JSON to override original UserDefaults keys. */
    fun withExtras(data:JSONObject):ReaderOptions {
        val merged=json();EXTRA_KEYS.forEach { key ->if(data.has(key) && !data.isNull(key))merged.put(key,data.get(key)) }
        val extra=parse(merged)
        return copy(fit=extra.fit,grayscale=extra.grayscale,invertColors=extra.invertColors,contrast=extra.contrast,
            colorTint=extra.colorTint,tintOpacity=extra.tintOpacity,brightness=extra.brightness,volumeKeys=extra.volumeKeys,
            fullscreen=extra.fullscreen,keepScreenOn=extra.keepScreenOn,showPageNumbers=extra.showPageNumbers,paragraphSpacing=extra.paragraphSpacing)
    }
    fun extrasJSON():JSONObject=json().let { all ->JSONObject().apply { EXTRA_KEYS.forEach { key ->put(key,all.get(key)) } } }
    fun json(): JSONObject = JSONObject().apply {
        put("mode", mode); put("tapZones", tapZones); put("invertTapZones", invertTapZones)
        put("animateTransitions", animateTransitions); put("hideBarsOnSwipe", hideBarsOnSwipe)
        put("pageLayout", pageLayout); put("pageOffset", pageOffset); put("splitWideImages", splitWideImages)
        put("reverseSplitOrder", reverseSplitOrder); put("cropBorders", cropBorders); put("downsample", downsample)
        put("upscale", upscale); put("upscaleMaxHeight", upscaleMaxHeight); put("disableDoubleTap", disableDoubleTap)
        put("fit", fit); put("background", background); put("grayscale", grayscale); put("invertColors", invertColors)
        put("contrast", contrast); put("colorTint", colorTint); put("tintOpacity", tintOpacity); put("brightness", brightness)
        put("preloadPages", preloadPages); put("infiniteScroll", infiniteScroll); put("autoScrollSpeed", autoScrollSpeed)
        put("autoScrollPosition", autoScrollPosition); put("volumeKeys", volumeKeys); put("orientation", orientation)
        put("fullscreen", fullscreen); put("keepScreenOn", keepScreenOn); put("showPageNumbers", showPageNumbers)
        put("textStyle", textStyle); put("textTheme", textTheme); put("textFont", textFont); put("fontSize", fontSize)
        put("lineSpacing", lineSpacing); put("paragraphSpacing", paragraphSpacing); put("textMargins", textMargins)
        put("skipDuplicates", skipDuplicates); put("markDuplicates", markDuplicates)
        put("pillarbox", pillarbox); put("pillarboxAmount", pillarboxAmount); put("pillarboxOrientation", pillarboxOrientation)
        put("disableQuickActions", disableQuickActions); put("autoScrollButton", autoScrollButton)
        put("liveText", liveText); put("textAppearance", textAppearance)
    }
    companion object {
        private val EXTRA_KEYS=listOf("fit","grayscale","invertColors","contrast","colorTint","tintOpacity","brightness","volumeKeys","fullscreen","keepScreenOn","showPageNumbers","paragraphSpacing")
        fun seriesExtrasKey(mangaId:String)="Reader.androidOptions.${mangaId.replaceFirst("::",".")}"
        fun parse(j: JSONObject) = ReaderOptions(
            j.optString("mode", "auto"), j.optString("tapZones", "disabled"), j.optBoolean("invertTapZones"),
            j.optBoolean("animateTransitions", true), j.optBoolean("hideBarsOnSwipe", false), j.optString("pageLayout", "auto"),
            j.optBoolean("pageOffset", false), j.optBoolean("splitWideImages"), j.optBoolean("reverseSplitOrder"),
            j.optBoolean("cropBorders"), j.optBoolean("downsample", false), j.optBoolean("upscale"),
            j.optInt("upscaleMaxHeight", 2000).coerceIn(200, 4000), j.optBoolean("disableDoubleTap"),
            j.optString("fit", "fit"), j.optString("background", "black"), j.optBoolean("grayscale"),
            j.optBoolean("invertColors"), j.optDouble("contrast", 1.0).toFloat().coerceIn(.5f, 2f), j.optLong("colorTint"),
            j.optDouble("tintOpacity").toFloat().coerceIn(0f, .8f), j.optDouble("brightness", -1.0).toFloat().coerceIn(-1f, 1f),
            j.optInt("preloadPages", 2).coerceIn(0, 20), j.optBoolean("infiniteScroll", true),
            j.optInt("autoScrollSpeed", 5).coerceIn(1, 10), j.optString("autoScrollPosition", "right"),
            j.optBoolean("volumeKeys"), j.optString("orientation", "system"), j.optBoolean("fullscreen", true),
            j.optBoolean("keepScreenOn", true), j.optBoolean("showPageNumbers", true), j.optString("textStyle", "scroll"),
            j.optString("textTheme", "system"), j.optString("textFont", "system"), j.optInt("fontSize", 18).coerceIn(10, 48),
            j.optDouble("lineSpacing", 1.65).toFloat().coerceIn(1f, 2.5f), j.optInt("paragraphSpacing", 8).coerceIn(0, 32),
            j.optInt("textMargins", 24).coerceIn(8, 64), j.optBoolean("skipDuplicates", true), j.optBoolean("markDuplicates", true),
            j.optBoolean("pillarbox"), j.optInt("pillarboxAmount", 15), j.optString("pillarboxOrientation", "both"),
            j.optBoolean("disableQuickActions"), j.optBoolean("autoScrollButton"), j.optBoolean("liveText"), j.optString("textAppearance", "system"))
        fun fromLegacy(store: SettingsStore, mangaId: String? = null): ReaderOptions {
            val iosId=mangaId?.replaceFirst("::",".")
            val mode = iosId?.let { store.string("Reader.readingMode.$it").takeIf(String::isNotBlank) }
                ?: mangaId?.let { store.string("Reader.readingMode.$it").takeIf(String::isNotBlank) } ?: store.string("Reader.readingMode")
            val font = store.int("Reader.textFontSize").coerceIn(10, 48)
            return ReaderOptions(mode = mode.ifBlank { "auto" }, tapZones = store.string("Reader.tapZones"),
                invertTapZones = store.bool("Reader.invertTapZones"), animateTransitions = store.bool("Reader.animatePageTransitions"),
                hideBarsOnSwipe = store.bool("Reader.hideBarsOnSwipe"), pageLayout = store.string("Reader.pagedPageLayout"),
                pageOffset = if(iosId!=null && store.value("Reader.pagedPageOffset.$iosId")!=null)store.bool("Reader.pagedPageOffset.$iosId")
                    else if (mangaId != null && store.value("Reader.pagedPageOffset.$mangaId") != null) store.bool("Reader.pagedPageOffset.$mangaId") else store.bool("Reader.pagedPageOffset"), splitWideImages = store.bool("Reader.splitWideImages"),
                reverseSplitOrder = store.bool("Reader.reverseSplitOrder"), cropBorders = store.bool("Reader.cropBorders"),
                downsample = store.bool("Reader.downsampleImages"), upscale = store.bool("Reader.upscaleImages"),
                upscaleMaxHeight = store.int("Reader.upscaleMaxHeight"), disableDoubleTap = store.bool("Reader.disableDoubleTap"),
                background = store.string("Reader.backgroundColor"), preloadPages = store.int("Reader.pagesToPreload"),
                infiniteScroll = store.bool("Reader.verticalInfiniteScroll"), autoScrollSpeed = store.int("Reader.autoScrollSpeed").coerceIn(1, 200),
                autoScrollPosition = store.string("Reader.autoScrollPosition"), orientation = store.string("Reader.orientation").let { if (it == "device") "system" else it },
                textStyle = store.string("Reader.textReaderStyle"), textTheme = store.string("Reader.textTheme").let { if (it == "default") "system" else it },
                textFont = store.string("Reader.textFontFamily").let { if (it == "System") "system" else it }, fontSize = font,
                lineSpacing = 1f + store.int("Reader.textLineSpacing").toFloat() / font,
                textMargins = store.int("Reader.textHorizontalPadding"), skipDuplicates = store.bool("Reader.skipDuplicateChapters"),
                markDuplicates = store.bool("Reader.markDuplicateChapters"), pillarbox = store.bool("Reader.pillarbox"),
                pillarboxAmount = store.int("Reader.pillarboxAmount"), pillarboxOrientation = store.string("Reader.pillarboxOrientation"),
                disableQuickActions = store.bool("Reader.disableQuickActions"), autoScrollButton = store.bool("Reader.autoScroll"),
                liveText = store.bool("Reader.liveText"), textAppearance = store.string("Reader.textAppearance"))
                .let { base ->
                    val old=ReaderOptionStore(store.repo.context)
                    fun read(value:String):JSONObject?=value.takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() }
                    val global=read(store.string("Android.Reader.options")) ?: old.savedJSON()
                    val series=if(mangaId==null)null else listOf(seriesExtrasKey(mangaId),"Reader.androidOptions.$mangaId","Android.Reader.options.$iosId","Android.Reader.options.$mangaId")
                        .firstNotNullOfOrNull { read(store.string(it)) } ?: old.savedJSON(mangaId)
                    global?.let(base::withExtras)?.let { value ->series?.let(value::withExtras) ?: value } ?: series?.let(base::withExtras) ?: base
                }
        }
    }
    fun toLegacy(existing: JSONObject, mangaId: String? = null): JSONObject = JSONObject(existing.toString()).apply {
        val iosId=mangaId?.replaceFirst("::",".")
        if(mangaId!=null) { remove("Reader.readingMode.$mangaId");remove("Reader.pagedPageOffset.$mangaId") }
        put(if (iosId == null) "Reader.readingMode" else "Reader.readingMode.$iosId", mode)
        put("Reader.tapZones", tapZones); put("Reader.invertTapZones", invertTapZones); put("Reader.animatePageTransitions", animateTransitions)
        put("Reader.hideBarsOnSwipe", hideBarsOnSwipe); put("Reader.pagedPageLayout", pageLayout); put(if (iosId == null) "Reader.pagedPageOffset" else "Reader.pagedPageOffset.$iosId", pageOffset)
        put("Reader.splitWideImages", splitWideImages); put("Reader.reverseSplitOrder", reverseSplitOrder); put("Reader.cropBorders", cropBorders)
        put("Reader.downsampleImages", downsample); put("Reader.upscaleImages", upscale); put("Reader.upscaleMaxHeight", upscaleMaxHeight)
        put("Reader.disableDoubleTap", disableDoubleTap); put("Reader.backgroundColor", background); put("Reader.pagesToPreload", preloadPages)
        put("Reader.verticalInfiniteScroll", infiniteScroll); put("Reader.autoScrollSpeed", autoScrollSpeed); put("Reader.autoScrollPosition", autoScrollPosition)
        put("Reader.orientation", if (orientation == "system") "device" else orientation); put("Reader.textReaderStyle", textStyle)
        put("Reader.textTheme", if (textTheme == "system") "default" else textTheme); put("Reader.textFontFamily", if (textFont == "system") "System" else textFont)
        put("Reader.textFontSize", fontSize); put("Reader.textLineSpacing", ((lineSpacing - 1f) * fontSize).toInt()); put("Reader.textHorizontalPadding", textMargins)
        put("Reader.skipDuplicateChapters", skipDuplicates); put("Reader.markDuplicateChapters", markDuplicates); put("Reader.pillarbox", pillarbox)
        put("Reader.pillarboxAmount", pillarboxAmount); put("Reader.pillarboxOrientation", pillarboxOrientation)
        put("Reader.disableQuickActions", disableQuickActions); put("Reader.autoScroll", autoScrollButton)
        put("Reader.liveText", liveText); put("Reader.textAppearance", textAppearance)
        val extraKey=if(mangaId==null)"Android.Reader.options" else seriesExtrasKey(mangaId)
        val previousExtras=(if(mangaId==null)listOf(extraKey) else listOf(extraKey,"Reader.androidOptions.$mangaId","Android.Reader.options.$iosId","Android.Reader.options.$mangaId"))
            .firstNotNullOfOrNull { key ->optString(key).takeIf(String::isNotBlank)?.let { runCatching { JSONObject(it) }.getOrNull() } } ?: JSONObject()
        val known=extrasJSON();known.keys().forEach { key ->previousExtras.put(key,known.get(key)) }
        if(mangaId==null)put(extraKey,previousExtras.toString()) else {
            remove("Reader.androidOptions.$mangaId");remove("Android.Reader.options.$iosId");remove("Android.Reader.options.$mangaId")
            put(extraKey,previousExtras.toString())
        }
    }
}

class ReaderOptionStore(context: Context) {
    private val preferences = context.getSharedPreferences("reader-options", Context.MODE_PRIVATE)
    internal fun savedJSON(mangaId:String?=null):JSONObject?=preferences.getString(if(mangaId==null)"global" else "series:$mangaId",null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun get(mangaId: String? = null, legacy: ReaderOptions? = null): ReaderOptions {
        val value = mangaId?.let { preferences.getString("series:$it", null) } ?: preferences.getString("global", null)
        return if (legacy != null) legacy else value?.let { runCatching { ReaderOptions.parse(JSONObject(it)) }.getOrNull() } ?: ReaderOptions()
    }
    fun save(options: ReaderOptions, mangaId: String? = null) {
        preferences.edit().putString(if (mangaId == null) "global" else "series:$mangaId", options.json().toString()).apply()
    }
    fun reset(mangaId: String) { preferences.edit().remove("series:$mangaId").apply() }
    fun export(): JSONObject = JSONObject(preferences.all.filterValues { it is String })
    fun restore(data: JSONObject) { preferences.edit().apply { data.keys().forEach { putString(it, data.getString(it)) } }.apply() }
}
