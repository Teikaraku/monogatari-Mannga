package app.monogatari.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import app.monogatari.android.AppBrand
import org.json.JSONObject
import java.util.Locale

/** Original catalogs stay intact; application descriptions use the current display brand. */
object Localization {
    @Volatile private var catalog: JSONObject? = null
    fun string(context: Context, key: String, vararg arguments: Any?): String {
        val catalogs = catalog ?: synchronized(this) { catalog ?: JSONObject(context.assets.open("ios/localizations.json").bufferedReader().use { it.readText() }).also { catalog = it } }
        val locale = context.resources.configuration.locales[0] ?: Locale.getDefault()
        val language = when (locale.language) {
            "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) "zh-Hant" else "zh-Hans"
            "iw" -> "he"; "in" -> "id"; "nb" -> "nb-NO"; else -> locale.language
        }
        val original = catalogs.optJSONObject(locale.toLanguageTag())?.optString(key)?.takeIf { it.isNotEmpty() }
            ?: catalogs.optJSONObject(language)?.optString(key)?.takeIf { it.isNotEmpty() }
            ?: catalogs.optJSONObject("en")?.optString(key)?.takeIf { it.isNotEmpty() } ?: key
        val value = AppBrand.displayText(original)
        if (arguments.isEmpty()) return value
        return try { objcFormat(locale, value, arguments) } catch (_: Exception) { value }
    }
    private fun objcFormat(locale: Locale, format: String, arguments: Array<out Any?>): String {
        val token = Regex("%(?:(\\d+)\\$)?[-+# 0,(]*\\d*(?:\\.\\d+)?(?:ll|l)?[a-zA-Z@%]")
        val output = StringBuilder(); var offset = 0; var nextArgument = 0
        token.findAll(format).forEach { match ->
            output.append(format.substring(offset, match.range.first))
            val type = match.value.last()
            if (type == '%' || type == 'n') output.append(String.format(locale, match.value)) else {
                val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: nextArgument++
                val plain = match.value.replace(Regex("^%\\d+\\$"), "%")
                    .replace(Regex("ll?(?=[diu]$)"), "")
                val specifier = plain.dropLast(1) + when (type) { '@' -> 's'; 'i', 'u' -> 'd'; else -> type }
                var rendered = String.format(locale, specifier, arguments[index])
                if (type in "gG" && '#' !in specifier) {
                    val symbols = java.text.DecimalFormatSymbols.getInstance(locale)
                    val exponent = rendered.indexOfFirst { it == 'e' || it == 'E' }.let { if (it < 0) rendered.length else it }
                    var mantissa = rendered.substring(0, exponent)
                    if (symbols.decimalSeparator in mantissa) mantissa = mantissa.trimEnd(symbols.zeroDigit).trimEnd(symbols.decimalSeparator)
                    rendered = mantissa + rendered.substring(exponent)
                }
                output.append(rendered)
            }
            offset = match.range.last + 1
        }
        return output.append(format.substring(offset)).toString()
    }
}
@Composable fun tr(key: String, vararg arguments: Any?): String = Localization.string(LocalContext.current, key, *arguments)
