package app.monogatari.android.ui

import android.graphics.Color
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.monogatari.android.dictionary.DictionaryGlossary
import org.json.JSONTokener

@Composable fun DictionaryGlossaryView(dictionaryId: String, glossary: String, textColor: String = "#222222", onLookup: (String) -> Unit = {}) {
    val context = LocalContext.current
    val document = remember(dictionaryId, glossary, textColor) { DictionaryGlossary.document(JSONTokener(glossary).nextValue(), dictionaryId, DictionaryGlossary.css(context, dictionaryId), textColor) }
    var height by remember(document) { mutableIntStateOf(160) }
    val lookup by rememberUpdatedState(onLookup)
    key(dictionaryId) { AndroidView(factory = { ctx -> DictionaryGlossary.createView(ctx, dictionaryId, heightChanged = { height = it }, lookup = { lookup(it) }) }, update = { view -> if (view.tag != document) { view.tag = document; DictionaryGlossary.loadDocument(view, dictionaryId, document) } }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxWidth().height(height.dp)) }
}
