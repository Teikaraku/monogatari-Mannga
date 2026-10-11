package app.monogatari.android

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.monogatari.android.data.AppRepository
import app.monogatari.android.ui.MonogatariApp

class MainActivity : FragmentActivity() {
    private lateinit var model: MonogatariViewModel
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST") override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MonogatariViewModel(AppRepository.get(applicationContext)) as T
        })[MonogatariViewModel::class.java]
        // Intercept through the public Window callback before the focused view consumes reader shortcuts.
        val defaultCallback = window.callback
        window.callback = object : android.view.Window.Callback by defaultCallback {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean =
                handleKeyboard(event) || defaultCallback.dispatchKeyEvent(event)
        }
        savedInstanceState?.let { model.restoreNavigation(it) }
        setContent { MonogatariApp(model) }
        if (savedInstanceState == null) importIntent(intent)
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); importIntent(intent) }
    override fun onStart() {
        super.onStart()
        app.monogatari.android.library.ForegroundActivities.started(this)
        app.monogatari.android.downloads.DownloadService.get(applicationContext).setAppVisible(true)
        if (::model.isInitialized && model.screen == "reader") model.readerChapter?.let { model.beginReadingSession(model.key, it.key, model.readerVisiblePage) }
        if (::model.isInitialized && model.repo.loadError == null) model.run {
            app.monogatari.android.backup.CompleteBackupManager(model.repo).automaticIfDue()
            app.monogatari.android.library.LibraryRefresh.foregroundIfDue(model.repo)
        }
    }
    override fun onStop() {
        if (::model.isInitialized && model.screen == "reader" && model.readerVisibleTotal > 0) model.readerChapter?.let {
            model.progress(model.key, it.key, model.readerVisiblePage, model.readerVisibleTotal, model.readerVisiblePosition, model.readerVisibleCompleted)
        }
        if (::model.isInitialized) model.endReadingSession()
        if (app.monogatari.android.library.ForegroundActivities.stopped(this)) {
            if (::model.isInitialized) app.monogatari.android.library.LibraryRefresh.appStopped(model.repo)
            app.monogatari.android.downloads.DownloadService.get(applicationContext).setAppVisible(false)
        }
        super.onStop()
    }
    override fun onSaveInstanceState(outState: Bundle) { model.saveNavigation(outState); super.onSaveInstanceState(outState) }
    private fun handleKeyboard(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (app.monogatari.android.reader.ReaderInput.dispatch(event.keyCode, event)) return true
            val emulatorShift = event.isShiftPressed && android.os.Build.HARDWARE in setOf("ranchu", "goldfish")
                && !getSystemService(android.view.inputmethod.InputMethodManager::class.java).isAcceptingText
            if (::model.isInitialized && model.screen != "reader" && (emulatorShift || event.isCtrlPressed || event.isMetaPressed)
                && event.keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_5) {
                model.selectTab(event.keyCode - KeyEvent.KEYCODE_1)
                return true
            }
        }
        return false
    }
    private fun importIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra("monogatari.openUpdates", false)) {
            intent.removeExtra("monogatari.openUpdates"); model.navigate("updates"); return
        }
        val uris = when (intent.action) {
            Intent.ACTION_VIEW -> listOfNotNull(intent.data)
            Intent.ACTION_SEND -> listOfNotNull((intent.getParcelableExtra<android.os.Parcelable>(Intent.EXTRA_STREAM) as? android.net.Uri)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)?.trim()?.takeIf { it.startsWith("http://") || it.startsWith("https://") || it.startsWith("aidoku://") || it.startsWith("monogatari://") }?.let(android.net.Uri::parse))
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<android.os.Parcelable>(Intent.EXTRA_STREAM).orEmpty().filterIsInstance<android.net.Uri>()
            else -> emptyList()
        }
        uris.forEach { uri ->
            if (uri.scheme in setOf("aidoku", "aidoku-android") && uri.host in setOf("oauth", "anilist-auth", "myanimelist-auth", "bangumi-auth", "shikimori-auth", "mangabaka-auth")) {
                model.run { app.monogatari.android.tracking.TrackingRepository.get(applicationContext).handleOAuthCallback(uri); model.navigate("tracking") }
                return@forEach
            }
            if (uri.scheme == "http" || uri.scheme == "https" || uri.scheme in app.monogatari.android.library.IncomingLinks.appSchemes) {
                model.run {
                    app.monogatari.android.library.IncomingLinks.open(model, uri)
                }
            } else model.run {
                when (withContext(Dispatchers.IO) { app.monogatari.android.library.IncomingLinks.fileKind(model.repo, uri) }) {
                    "source" -> error(app.monogatari.android.ui.Localization.string(model.repo.context, "UNAVAILABLE"))
                    "backup" -> { val summary = app.monogatari.android.backup.CompleteBackupManager(model.repo).import(uri); model.navigate("backups", summary.file.absolutePath) }
                    else -> { model.repo.import(uri); model.showTabRoot(0) }
                }
            }
        }
    }
}
