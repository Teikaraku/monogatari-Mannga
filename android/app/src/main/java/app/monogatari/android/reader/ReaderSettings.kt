package app.monogatari.android.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import app.monogatari.android.ui.tr
import app.monogatari.android.ui.SettingRow
import app.monogatari.android.ui.CallerContextDropdownMenu
import app.monogatari.android.ui.OptionSheet
import kotlinx.coroutines.launch

@Composable fun ReaderSettingsDialog(options: ReaderOptions, onChange: (ReaderOptions, Boolean) -> Unit, onDismiss: () -> Unit) {
    var global by remember { mutableStateOf(false) }
    fun set(o: ReaderOptions) = onChange(o, global)
    OptionSheet(readerLabel(tr("READER_SETTINGS")), onDismiss) {
        Column(Modifier.padding(horizontal = 4.dp)) {
            ReaderToggle("同时设为全局默认", global) { global = it }
            ReaderSettingsContent(options) { set(it) }
        }
    }
}
@Composable fun ReaderSettingsContent(options: ReaderOptions, onChange: (ReaderOptions) -> Unit) {
    var showModels by remember { mutableStateOf(false) }
    val context=LocalContext.current
    val settingStore=remember { app.monogatari.android.data.SettingsStore(app.monogatari.android.data.AppRepository.get(context)) }
    val scope=rememberCoroutineScope()
    var quality by remember { mutableStateOf(settingStore.string("Reader.upscaleQuality")) }
    fun set(o: ReaderOptions) = onChange(o)
    Column {
            ReaderChoice(tr("READING_MODE"), options.mode, listOf("default" to tr("DEFAULT"), "auto" to tr("AUTOMATIC"), "rtl" to tr("RTL"), "ltr" to tr("LTR"), "vertical" to tr("VERTICAL"), "webtoon" to tr("WEBTOON"), "continuous" to tr("CONTINUOUS_WITH_GAPS"))) { set(options.copy(mode = it)) }
            ReaderChoice(tr("PAGE_LAYOUT"), options.pageLayout, listOf("single" to tr("SINGLE_PAGE"), "double" to tr("DOUBLE_PAGE"), "auto" to tr("AUTOMATIC"))) { set(options.copy(pageLayout = it)) }
            ReaderToggle(tr("PAGE_OFFSET"), options.pageOffset) { set(options.copy(pageOffset = it)) }
            ReaderToggle(tr("SPLIT_WIDE_IMAGES"), options.splitWideImages) { set(options.copy(splitWideImages = it)) }
            ReaderToggle(tr("REVERSE_SPLIT_ORDER"), options.reverseSplitOrder) { set(options.copy(reverseSplitOrder = it)) }
            ReaderChoice("图像缩放", options.fit, listOf("fit" to "适合屏幕", "width" to "适合宽度", "height" to "适合高度", "fill" to "填满屏幕")) { set(options.copy(fit = it)) }
            ReaderToggle(tr("CROP_BORDERS"), options.cropBorders) { set(options.copy(cropBorders = it)) }
            ReaderToggle(tr("DOWNSAMPLE_IMAGES"), options.downsample) { set(options.copy(downsample = it)) }
            ReaderToggle(tr("UPSCALE_IMAGES"), options.upscale) { set(options.copy(upscale = it)) }
            if (options.upscale) {
                TextButton({ showModels = true }) { Text(tr("UPSCALING_MODELS")) }
                ReaderChoice(tr("UPSCALE_QUALITY"),quality,listOf("fast" to tr("UPSCALE_QUALITY_FAST"),"best" to tr("UPSCALE_QUALITY_BEST"))) {
                    quality=it;scope.launch { settingStore.put("Reader.upscaleQuality",it) }
                }
                ReaderSlider(tr("UPSCALE_MAX_IMAGE_HEIGHT"), options.upscaleMaxHeight.toFloat(), 200f..4000f) { set(options.copy(upscaleMaxHeight = it.toInt())) }
            }
            ReaderToggle(tr("DISABLE_DOUBLE_TAP_ZOOM"), options.disableDoubleTap) { set(options.copy(disableDoubleTap = it)) }
            ReaderToggle(tr("DISABLE_QUICK_ACTIONS"), options.disableQuickActions) { set(options.copy(disableQuickActions = it)) }
            ReaderToggle(tr("LIVE_TEXT"), options.liveText) { set(options.copy(liveText = it)) }
            ReaderChoice(tr("READER_BG_COLOR"), options.background, listOf("black" to tr("READER_BG_COLOR_BLACK"), "white" to tr("READER_BG_COLOR_WHITE"), "auto" to tr("READER_BG_COLOR_AUTO"), "gray" to "灰色", "system" to tr("READER_BG_COLOR_SYSTEM"))) { set(options.copy(background = it)) }
            ReaderToggle("灰阶", options.grayscale) { set(options.copy(grayscale = it)) }
            ReaderToggle("反转图片颜色", options.invertColors) { set(options.copy(invertColors = it)) }
            ReaderSlider("对比度", options.contrast, .5f..2f) { set(options.copy(contrast = it)) }
            ReaderChoice("护眼色调", options.colorTint.toString(), listOf("0" to "无", "4294940518" to "暖黄", "4289388494" to "柔绿", "4291611852" to "淡灰")) { set(options.copy(colorTint = it.toLong(), tintOpacity = if (it == "0") 0f else .2f)) }
            if (options.colorTint != 0L) ReaderSlider("色调强度", options.tintOpacity, 0f..0.8f) { set(options.copy(tintOpacity = it)) }
            ReaderToggle("使用系统亮度", options.brightness < 0f) { set(options.copy(brightness = if (it) -1f else .5f)) }
            if (options.brightness >= 0f) ReaderSlider("屏幕亮度", options.brightness, .01f..1f) { set(options.copy(brightness = it)) }
            ReaderSlider(tr("PAGES_TO_PRELOAD"), options.preloadPages.toFloat(), 0f..20f) { set(options.copy(preloadPages = it.toInt())) }
            ReaderChoice(tr("TAP_ZONES"), options.tapZones, listOf("auto" to tr("AUTOMATIC"), "left-right" to tr("ZONE_LEFT_RIGHT"), "l-shaped" to tr("ZONE_L_SHAPED"), "kindle" to tr("ZONE_KINDLE"), "edge" to tr("ZONE_EDGE"), "vertical" to "上下分区", "disabled" to tr("DISABLED"))) { set(options.copy(tapZones = it)) }
            ReaderToggle(tr("INVERT_TAP_ZONES"), options.invertTapZones) { set(options.copy(invertTapZones = it)) }
            ReaderToggle(tr("ANIMATE_PAGE_TRANSITIONS"), options.animateTransitions) { set(options.copy(animateTransitions = it)) }
            ReaderToggle(tr("HIDE_BARS_ON_SWIPE"), options.hideBarsOnSwipe) { set(options.copy(hideBarsOnSwipe = it)) }
            ReaderToggle("音量键翻页", options.volumeKeys) { set(options.copy(volumeKeys = it)) }
            ReaderToggle(tr("INFINITE_VERTICAL_SCROLL"), options.infiniteScroll) { set(options.copy(infiniteScroll = it)) }
            ReaderToggle(tr("AUTO_SCROLL"), options.autoScrollButton) { set(options.copy(autoScrollButton = it)) }
            ReaderSlider(tr("AUTO_SCROLL_SPEED"), options.autoScrollSpeed.toFloat(), 1f..10f) { set(options.copy(autoScrollSpeed = it.toInt())) }
            ReaderToggle(tr("PILLARBOX"), options.pillarbox) { set(options.copy(pillarbox = it)) }
            if (options.pillarbox) {
                ReaderSlider(tr("PILLARBOX_AMOUNT"), options.pillarboxAmount.toFloat(), 5f..95f) { set(options.copy(pillarboxAmount = it.toInt())) }
                ReaderChoice(tr("PILLARBOX_ORIENTATION"), options.pillarboxOrientation, listOf("both" to tr("BOTH"), "portrait" to tr("PORTRAIT"), "landscape" to tr("LANDSCAPE"))) { set(options.copy(pillarboxOrientation = it)) }
            }
            ReaderChoice(tr("AUTO_SCROLL_POSITION"), options.autoScrollPosition, listOf("left" to "左侧", "right" to "右侧")) { set(options.copy(autoScrollPosition = it)) }
            ReaderToggle(tr("SKIP_DUPLICATE_CHAPTERS"), options.skipDuplicates) { set(options.copy(skipDuplicates = it)) }
            ReaderToggle(tr("MARK_DUPLICATE_CHAPTERS"), options.markDuplicates) { set(options.copy(markDuplicates = it)) }
            ReaderChoice(tr("READER_ORIENTATION"), options.orientation, listOf("system" to tr("FOLLOW_DEVICE"), "portrait" to tr("PORTRAIT"), "landscape" to tr("LANDSCAPE"), "locked" to "锁定当前")) { set(options.copy(orientation = it)) }
            ReaderToggle("全屏阅读", options.fullscreen) { set(options.copy(fullscreen = it)) }
            ReaderToggle("保持屏幕常亮", options.keepScreenOn) { set(options.copy(keepScreenOn = it)) }
            ReaderToggle("显示页码", options.showPageNumbers) { set(options.copy(showPageNumbers = it)) }
            ReaderChoice(tr("TEXT_READER_STYLE"), options.textStyle, listOf("paged" to tr("TEXT_READER_PAGED"), "scroll" to tr("TEXT_READER_SCROLL"), "rich" to "EPUB 原排版")) { set(options.copy(textStyle = it)) }
            ReaderChoice(tr("TEXT_THEME"), options.textTheme, listOf("system" to tr("READER_BG_COLOR_SYSTEM"), "sepia" to tr("TEXT_THEME_SEPIA"), "paper" to tr("TEXT_THEME_PAPER"), "gray" to tr("TEXT_THEME_GRAY"), "green" to "绿色")) { set(options.copy(textTheme = it)) }
            ReaderChoice(tr("APPEARANCE"), options.textAppearance, listOf("system" to tr("READER_BG_COLOR_SYSTEM"), "light" to tr("APPEARANCE_LIGHT"), "dark" to tr("APPEARANCE_DARK"))) { set(options.copy(textAppearance = it)) }
            ReaderFontPicker(options.textFont) { set(options.copy(textFont=it)) }
            ReaderSlider(tr("TEXT_FONT_SIZE"), options.fontSize.toFloat(), 10f..48f) { set(options.copy(fontSize = it.toInt())) }
            ReaderSlider(tr("TEXT_LINE_SPACING"), options.lineSpacing, 1f..2.5f) { set(options.copy(lineSpacing = it)) }
            ReaderSlider("段落间距", options.paragraphSpacing.toFloat(), 0f..32f) { set(options.copy(paragraphSpacing = it.toInt())) }
            ReaderSlider(tr("TEXT_HORIZONTAL_PADDING"), options.textMargins.toFloat(), 8f..64f) { set(options.copy(textMargins = it.toInt())) }
    }
    if (showModels) UpscaleModelsDialog { showModels = false }
}

/** Additional native controls remain available after the original grouped settings. */
@Composable fun ReaderExtensionsSettingsContent(options: ReaderOptions, onChange: (ReaderOptions) -> Unit) {
    ReaderChoice("图像缩放", options.fit, listOf("fit" to "适合屏幕", "width" to "适合宽度", "height" to "适合高度", "fill" to "填满屏幕")) { onChange(options.copy(fit = it)) }
    ReaderToggle("灰阶", options.grayscale) { onChange(options.copy(grayscale = it)) }
    ReaderToggle("反转图片颜色", options.invertColors) { onChange(options.copy(invertColors = it)) }
    ReaderSlider("对比度", options.contrast, .5f..2f) { onChange(options.copy(contrast = it)) }
    ReaderChoice("护眼色调", options.colorTint.toString(), listOf("0" to "无", "4294940518" to "暖黄", "4289388494" to "柔绿", "4291611852" to "淡灰")) {
        onChange(options.copy(colorTint = it.toLong(), tintOpacity = if (it == "0") 0f else .2f))
    }
    if (options.colorTint != 0L) ReaderSlider("色调强度", options.tintOpacity, 0f..0.8f) { onChange(options.copy(tintOpacity = it)) }
    ReaderToggle("使用系统亮度", options.brightness < 0f) { onChange(options.copy(brightness = if (it) -1f else .5f)) }
    if (options.brightness >= 0f) ReaderSlider("屏幕亮度", options.brightness, .01f..1f) { onChange(options.copy(brightness = it)) }
    ReaderToggle("音量键翻页", options.volumeKeys) { onChange(options.copy(volumeKeys = it)) }
    ReaderToggle("全屏阅读", options.fullscreen) { onChange(options.copy(fullscreen = it)) }
    ReaderToggle("保持屏幕常亮", options.keepScreenOn) { onChange(options.copy(keepScreenOn = it)) }
    ReaderToggle("显示页码", options.showPageNumbers) { onChange(options.copy(showPageNumbers = it)) }
    ReaderSlider("段落间距", options.paragraphSpacing.toFloat(), 0f..32f) { onChange(options.copy(paragraphSpacing = it.toInt())) }
}
@Composable private fun ReaderToggle(label: String, value: Boolean, change: (Boolean) -> Unit) {
    SettingRow(readerLabel(label), onClick = { change(!value) }, trailing = { Switch(value, change) })
}
@Composable private fun ReaderSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, change: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { Text("${readerLabel(label)}: ${if (range.endInclusive > 3f) value.toInt() else "%.2f".format(value)}", style = MaterialTheme.typography.bodyMedium)
        Slider(value.coerceIn(range.start, range.endInclusive), change, valueRange = range) }
}
@Composable private fun ReaderChoice(label: String, selected: String, values: List<Pair<String, String>>, change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box { SettingRow(readerLabel(label), value = readerLabel(values.firstOrNull { it.first == selected }?.second ?: selected), onClick = { expanded = true })
        CallerContextDropdownMenu(expanded, { expanded = false }) { values.forEach { (key, title) -> DropdownMenuItem({ Text(readerLabel(title)) }, { expanded = false; change(key) }) } } }
}

@Composable private fun readerLabel(value: String): String {
    val keys = mapOf("阅读设置" to "READER_SETTINGS", "完成" to "DONE", "阅读方向" to "READING_MODE", "全局默认" to "DEFAULT",
        "源自动选择" to "AUTOMATIC", "自动" to "AUTOMATIC", "从右到左" to "RTL", "从左到右" to "LTR", "竖向分页" to "VERTICAL",
        "条漫" to "WEBTOON", "连续（有间距）" to "CONTINUOUS_WITH_GAPS", "分页布局" to "PAGE_LAYOUT", "单页" to "SINGLE_PAGE",
        "双页" to "DOUBLE_PAGE", "横屏双页" to "AUTOMATIC", "双页首张单独显示" to "PAGE_OFFSET", "拆分横向跨页图片" to "SPLIT_WIDE_IMAGES",
        "反转跨页拆分顺序" to "REVERSE_SPLIT_ORDER", "自动裁掉单色边框" to "CROP_BORDERS", "按屏幕大小降低解码分辨率" to "DOWNSAMPLE_IMAGES",
        "高质量插值放大" to "UPSCALE_IMAGES", "放大高度上限" to "UPSCALE_MAX_IMAGE_HEIGHT", "禁用双击放大" to "DISABLE_DOUBLE_TAP_ZOOM",
        "背景" to "READER_BG_COLOR", "黑色" to "READER_BG_COLOR_BLACK", "白色" to "READER_BG_COLOR_WHITE", "系统" to "READER_BG_COLOR_SYSTEM",
        "预取页数" to "PAGES_TO_PRELOAD", "点击区域" to "TAP_ZONES", "左右分区" to "ZONE_LEFT_RIGHT", "L 型" to "ZONE_L_SHAPED",
        "Kindle" to "ZONE_KINDLE", "边缘" to "ZONE_EDGE", "关闭" to "DISABLED", "反转点击翻页" to "INVERT_TAP_ZONES", "翻页动画" to "ANIMATE_PAGE_TRANSITIONS",
        "滑动时隐藏工具栏" to "HIDE_BARS_ON_SWIPE", "连续模式自动进入下一章" to "INFINITE_VERTICAL_SCROLL", "自动滚动" to "AUTO_SCROLL",
        "自动滚动速度" to "AUTO_SCROLL_SPEED", "自动滚动按钮位置" to "AUTO_SCROLL_POSITION", "跳过重复章节号" to "SKIP_DUPLICATE_CHAPTERS",
        "同步标记重复章节已读" to "MARK_DUPLICATE_CHAPTERS", "屏幕方向" to "READER_ORIENTATION", "跟随系统" to "FOLLOW_DEVICE", "竖屏" to "PORTRAIT",
        "横屏" to "LANDSCAPE", "文字阅读" to "TEXT_READER_STYLE", "分页" to "TEXT_READER_PAGED", "连续" to "TEXT_READER_SCROLL",
        "文字主题" to "TEXT_THEME", "羊皮纸" to "TEXT_THEME_SEPIA", "字体" to "TEXT_FONT_FAMILY", "字号" to "TEXT_FONT_SIZE",
        "行距" to "TEXT_LINE_SPACING", "文字页边距" to "TEXT_HORIZONTAL_PADDING", "侧边留白" to "PILLARBOX", "留白比例" to "PILLARBOX_AMOUNT",
        "留白方向" to "PILLARBOX_ORIENTATION", "全部" to "BOTH")
    keys[value]?.let { return tr(it) }
    if (LocalConfiguration.current.locales[0].language == "zh") return value
    return mapOf("同时设为全局默认" to "Save as global defaults", "图像缩放" to "Image scaling", "适合屏幕" to "Fit screen", "适合宽度" to "Fit width",
        "适合高度" to "Fit height", "填满屏幕" to "Fill screen", "灰色" to "Gray", "灰阶" to "Grayscale", "反转图片颜色" to "Invert image colors",
        "对比度" to "Contrast", "护眼色调" to "Color overlay", "无" to "None", "暖黄" to "Warm yellow", "柔绿" to "Soft green", "淡灰" to "Light gray",
        "色调强度" to "Overlay strength", "使用系统亮度" to "Use system brightness", "屏幕亮度" to "Screen brightness", "上下分区" to "Top and bottom",
        "音量键翻页" to "Volume key navigation", "左侧" to "Left", "右侧" to "Right", "锁定当前" to "Lock current orientation", "全屏阅读" to "Fullscreen",
        "保持屏幕常亮" to "Keep screen on", "显示页码" to "Show page numbers", "EPUB 原排版" to "EPUB original layout", "深色" to "Dark",
        "绿色" to "Green", "衬线" to "Serif", "等宽" to "Monospace", "段落间距" to "Paragraph spacing")[value] ?: value
}
