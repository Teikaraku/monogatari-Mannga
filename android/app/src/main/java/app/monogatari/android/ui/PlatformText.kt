package app.monogatari.android.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/** Additional Android controls fall back to English when no original translation key exists. */
@Composable internal fun pt(english: String, chinese: String): String = platformText(LocalContext.current, english, chinese)
internal fun platformText(context: Context, english: String, chinese: String): String = if (context.resources.configuration.locales[0].language == "zh") chinese else english
