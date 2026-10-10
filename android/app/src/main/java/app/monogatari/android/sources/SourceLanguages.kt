package app.monogatari.android.sources

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private fun InstalledSource.legacyLanguageOptions():List<JSONObject> = (manifest.optJSONArray("languages") ?: info.optJSONArray("languages"))?.values()?.filterIsInstance<JSONObject>().orEmpty()
private fun InstalledSource.isLegacyManifest() = legacyLanguageOptions().isNotEmpty() || info.has("lang") && !info.has("languages")
fun sourceSupportedLanguages(manifest:JSONObject):List<String> {
    val info=manifest.optJSONObject("info") ?: manifest
    fun codes(values:JSONArray?):List<String> = values?.values()?.mapNotNull { value -> when(value) {
        is JSONObject -> value.optString("code").takeIf(String::isNotBlank)
        is String -> value.takeIf(String::isNotBlank)
        else -> null
    } }?.distinct().orEmpty()
    return codes(info.optJSONArray("languages")).ifEmpty { codes(manifest.optJSONArray("languages")) }
        .ifEmpty { listOf(info.optString("lang","multi").ifBlank { "multi" }) }
}
fun sourceContentRating(manifest:JSONObject):Int {
    val info=manifest.optJSONObject("info") ?: manifest
    return (if(info.has("contentRating")) info.optInt("contentRating") else info.optInt("nsfw")).takeIf { it in 0..2 } ?: 0
}
fun InstalledSource.languageCodes():List<String> = sourceSupportedLanguages(manifest)

/** Language selection keys and legacy raw values match the two original source ABIs. */
internal fun sourceLanguageSettings(context:Context,source:InstalledSource,legacy:Boolean=source.isLegacyManifest()):List<JSONObject> {
    val options=source.legacyLanguageOptions();val languages=source.languageCodes()
    if(!legacy && languages.size<=1 || legacy && options.isEmpty())return emptyList()
    val preferred=(0 until context.resources.configuration.locales.size()).map { context.resources.configuration.locales[it].language }.toSet()
    val single=if(legacy)source.manifest.optString("languageSelectType")=="single" else source.manifest.optJSONObject("config")?.optString("languageSelectType")=="single"
    val values=if(legacy)options.map { it.optString("value",it.optString("code")) } else languages
    val codes=if(legacy)options.map { it.optString("code") } else languages
    var defaults=codes.indices.filter { codes[it] in preferred }.map { values[it] }
    if(legacy && defaults.isEmpty())defaults=options.indices.filter { options[it].optBoolean("default",options[it].optBoolean("isDefault")) }.map { values[it] }
    if(legacy && defaults.isEmpty())defaults=values.take(1)
    if(single)defaults=defaults.take(1)
    val key=if(single && !legacy)"language" else "languages"
    val setting=JSONObject().put("key",key).put("title",if(single)"LANGUAGE" else "LANGUAGES")
        .put("type",if(single) { if(legacy)"multi-single-select" else "select" } else "multi-select")
        .put("values",JSONArray(values)).put("titles",JSONArray(codes.map { Locale.forLanguageTag(it).getDisplayName(Locale.getDefault()) }))
        .put("notification",if(legacy)"languageChange" else key).put("refreshes",JSONArray().put("content"))
    if(single && !legacy)defaults.firstOrNull()?.let { setting.put("default",it) } else setting.put("default",JSONArray(defaults))
    return listOf(JSONObject().put("type","group").put("title",setting.getString("title")).put("items",JSONArray().put(setting)))
}

/** Global search uses the user's enabled languages; an explicit empty selection stays empty. */
fun sourceSelectedLanguages(context:Context,source:InstalledSource,preferences:SourcePreferences=SourcePreferences(context,source.id)):List<String> {
    val languages=source.languageCodes();if(languages.size<=1)return languages
    val legacy=source.isLegacyManifest();preferences.initialize(JSONArray(sourceLanguageSettings(context,source,legacy)))
    val single=!legacy && source.manifest.optJSONObject("config")?.optString("languageSelectType")=="single"
    val selected=if(single)listOfNotNull(preferences.get("language") as? String) else when(val values=preferences.get("languages")) {
        is JSONArray -> values.values().map { it.toString() };is List<*> -> values.map { it.toString() };else -> emptyList()
    }
    if(!legacy)return selected
    val rawCodes=source.legacyLanguageOptions().associate { it.optString("value",it.optString("code")) to it.optString("code") }
    return selected.map { rawCodes[it]?:it }
}
