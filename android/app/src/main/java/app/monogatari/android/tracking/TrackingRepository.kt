package app.monogatari.android.tracking

import app.monogatari.android.AppBrand

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import androidx.work.*
import app.monogatari.android.data.Chapter
import app.monogatari.android.data.Manga
import app.monogatari.android.data.SecretStore
import app.monogatari.android.data.SourceConfig
import app.monogatari.android.data.AppRepository
import app.monogatari.android.data.SettingsStore
import app.monogatari.android.sources.SourcePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.concurrent.TimeUnit

class TrackingRepository private constructor(private val context: Context) {
    private val secrets = SecretStore(context)
    private val file = AtomicFile(File(context.filesDir, "tracking.json"))
    private val dataMutex = Mutex()
    private val syncMutex = Mutex()
    private class SyncLease(val repository: TrackingRepository) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<SyncLease> }
    private class LoginLease(val account: TrackerAccount) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<LoginLease> }
    private fun sameOrigin(first: TrackerAccount, second: TrackerAccount) = first.id == second.id && first.provider == second.provider && first.baseUrl == second.baseUrl && first.clientId == second.clientId && first.sourceId == second.sourceId && first.redirectUri == second.redirectUri
    private suspend fun <T> withSyncLock(block: suspend () -> T): T = if (currentCoroutineContext()[SyncLease]?.repository === this) block() else syncMutex.withLock { withContext(SyncLease(this)) { block() } }
    /** Hold synchronization across a multi-store migration; child participant operations reuse the lease. */
    suspend fun <T> migrationTransaction(block: suspend () -> T): T = withSyncLock(block)
    private val authMutex = Mutex()
    var loadError: String? = null; private set
    private val mutable = MutableStateFlow(try { if (file.baseFile.exists()) TrackingData.parse(JSONObject(file.readFully().toString(Charsets.UTF_8))) else TrackingData() } catch (e: Exception) { loadError = "追踪数据加载失败，原文件已保留：${e.message}"; TrackingData() })
    val data = mutable.asStateFlow()
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(40, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    val api = TrackerApi(::authorized)
    init {
        val work = PeriodicWorkRequestBuilder<TrackingRetryWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork("tracking-retry", ExistingPeriodicWorkPolicy.KEEP, work)
    }
    private suspend fun change(block: (TrackingData) -> TrackingData) = dataMutex.withLock {
        check(loadError == null) { loadError.orEmpty() }
        val next = block(mutable.value)
        val output = file.startWrite()
        try { output.write(next.json().toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) } catch (e: Exception) { file.failWrite(output); throw e }
        mutable.value = next
    }
    private suspend fun put(binding: TrackBinding) = change { it.copy(bindings = it.bindings.filter { old -> old.id != binding.id } + binding) }
    suspend fun saveAccount(account: TrackerAccount, clientSecret: String? = null, authenticated: Boolean = false) = withContext(Dispatchers.IO) { withSyncLock {
        require(account.id.isNotBlank() && account.baseUrl.isNotBlank()) { "请输入账户或服务器信息" }
        val uri = java.net.URI(account.baseUrl)
        require(uri.scheme in setOf("https", "http") && !uri.host.isNullOrBlank() && uri.userInfo == null) { "地址必须是完整的 HTTP 或 HTTPS URL" }
        require(account.provider.server || uri.scheme == "https") { "追踪服务需要 HTTPS" }
        if (account.sourceId.isNotEmpty()) {
            val source = AppRepository.get(context).state.value.sources.firstOrNull { it.id == account.sourceId } ?: error("Linked source was removed")
            require(source.type == account.provider.id && source.baseUrl == account.baseUrl) { "Edit a linked server address in source settings" }
        }
        val old = mutable.value.accounts.firstOrNull { it.id == account.id }; val changedOrigin = old != null && !sameOrigin(old, account)
        if (changedOrigin && !authenticated) listOf("token", "pending", "credentials", "client").forEach { secrets.remove("tracking.$it.${account.id}") }
        clientSecret?.let { secrets.set("tracking.client.${account.id}", it) }
        val next = if (changedOrigin && !authenticated) account.copy(connected = false, username = "", userId = "") else account
        if (next.sourceId.isNotEmpty()) AppRepository.get(context).settings.put("${next.sourceId}.disableTracking", !next.autoSync)
        change { it.copy(accounts = it.accounts.filter { previous -> previous.id != next.id } + next,
            bindings = if (next.sourceId.isNotEmpty() && !next.autoSync) it.bindings.filterNot { binding -> binding.accountId == next.id } else it.bindings) }
        if (next.sourceId.isNotEmpty() && next.autoSync && old?.autoSync == false) {
            val app = AppRepository.get(context).state.value; bindEnhancedSources(app.manga.filter { it.sourceId == next.sourceId }, app.sources)
        }
    } }
    suspend fun logout(accountId: String) = withContext(Dispatchers.IO) {
        withSyncLock {
            secrets.remove("tracking.token.$accountId"); secrets.remove("tracking.pending.$accountId"); secrets.remove("tracking.credentials.$accountId")
            change { it.copy(accounts = it.accounts.map { a -> if (a.id == accountId) a.copy(connected = false, userId = "", username = "") else a }) }
        }
    }
    suspend fun removeAccount(id: String) = withContext(Dispatchers.IO) {
        withSyncLock {
            secrets.remove("tracking.token.$id"); secrets.remove("tracking.pending.$id"); secrets.remove("tracking.credentials.$id"); secrets.remove("tracking.client.$id")
            change { it.copy(accounts = it.accounts.filter { a -> a.id != id }, bindings = it.bindings.filter { b -> b.accountId != id }) }
        }
    }
    /** Original resetSettings removes UserDefaults authentication and offline page tracking, not CoreData links. */
    suspend fun resetSettings(clearBindings: Boolean = false) = withContext(Dispatchers.IO) { withSyncLock {
        context.getSharedPreferences("credentials", Context.MODE_PRIVATE).all.keys.filter { it.startsWith("tracking.") }.forEach(secrets::remove)
        change { current -> current.copy(accounts = current.accounts.map { account -> TrackerAccount(account.id, account.provider, baseUrl = if (account.provider.server) account.baseUrl else account.provider.apiUrl, sourceId = account.sourceId) },
            bindings = if (clearBindings) emptyList() else current.bindings.map { it.copy(pending = false, pendingPage = null, pendingPages = emptyList(), error = "") }) }
    } }
    /** Original .aib stores UserDefaults token strings and optional NSData JSON OAuth responses. */
    suspend fun importLegacyCredentials(settings: JSONObject) = withContext(Dispatchers.IO) { withSyncLock {
        val accounts = mutable.value.accounts.toMutableList()
        for (provider in TrackerProvider.entries.filterNot { it.server }) {
            val prefix = "Tracker.${provider.id}"; val access = settings.optString("$prefix.token").takeIf { it.isNotBlank() }
            val encoded = settings.opt("$prefix.oauth")
            val oauth = when (encoded) { is JSONObject -> encoded; is String -> runCatching { JSONObject(if (encoded.trimStart().startsWith("{")) encoded else android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)) }.getOrNull(); else -> null }
            val token = oauth?.optString("access_token")?.takeIf { it.isNotBlank() } ?: access ?: continue
            val existing = accounts.firstOrNull { it.provider == provider && it.sourceId.isBlank() }
            val account = existing ?: TrackerAccount(provider.id, provider)
            // Tokens from the original built-in providers must never be sent to an overridden API host.
            val normalized = account.copy(baseUrl = provider.apiUrl, connected = true)
            val expires = if (oauth?.has("expires_in") == true) Math.addExact(System.currentTimeMillis(), Math.multiplyExact(oauth.getLong("expires_in"), 1000)) else Long.MAX_VALUE
            secrets.set("tracking.token.${normalized.id}", OAuthToken(token, oauth?.optString("refresh_token").orEmpty(), expires, oauth?.optString("token_type")?.takeIf { it.isNotBlank() } ?: "Bearer").json().toString())
            accounts.removeAll { it.id == normalized.id }; accounts += normalized
        }
        change { it.copy(accounts = accounts) }
    } }
    suspend fun loginToken(account: TrackerAccount, accessToken: String): TrackerAccount = withContext(Dispatchers.IO) { withSyncLock { withContext(LoginLease(account)) {
        require(accessToken.isNotBlank()) { "请输入访问令牌" }
        val key = "tracking.token.${account.id}"; val previous = secrets.get(key)
        secrets.set(key, OAuthToken(accessToken.trim()).json().toString())
        try { api.profile(account).also { saveAccount(it, authenticated = true) } } catch (e: Exception) { if (previous != null) secrets.set(key, previous) else secrets.remove(key); throw e }
    } } }
    suspend fun loginServer(account: TrackerAccount, username: String, password: String, bearer: String = ""): TrackerAccount = withContext(Dispatchers.IO) { withSyncLock { withContext(LoginLease(account)) {
        require(account.provider.server)
        val key = "tracking.token.${account.id}"; val previous = secrets.get(key)
        val token = when {
            bearer.isNotBlank() -> OAuthToken(bearer.trim())
            account.provider == TrackerProvider.KAVITA -> {
                val j = raw(account.baseUrl.trimEnd('/') + "/api/Account/login", "POST", JSONObject().put("username", username).put("password", password).toString().toRequestBodyJson())
                OAuthToken(j.getString("token"), j.optString("refreshToken"))
            }
            else -> OAuthToken(Credentials.basic(username, password), type = "Header")
        }
        secrets.set(key, token.json().toString())
        try {
            val connected = api.profile(account.copy(username = username))
            saveAccount(connected, authenticated = true)
            if (account.provider == TrackerProvider.KAVITA) secrets.set("tracking.credentials.${account.id}", JSONObject().put("username", username).put("password", password).toString())
            connected
        } catch (e: Exception) { if (previous != null) secrets.set(key, previous) else secrets.remove(key); throw e }
    } } }
    suspend fun startOAuth(account: TrackerAccount): String = withContext(Dispatchers.IO) { withSyncLock {
        require(!account.provider.server && account.clientId.isNotBlank()) { "请先配置 OAuth 应用 Client ID" }
        if (account.provider in setOf(TrackerProvider.BANGUMI, TrackerProvider.SHIKIMORI)) require(!secrets.get("tracking.client.${account.id}").isNullOrBlank()) { "此服务需要应用 Client Secret，可在账户设置中填写，或使用个人访问令牌登录" }
        saveAccount(account)
        val state = TrackerOAuth.random(); val verifier = TrackerOAuth.random()
        secrets.set("tracking.pending.${account.id}", JSONObject().put("state", state).put("verifier", verifier).put("created", System.currentTimeMillis()).toString())
        TrackerOAuth.authorize(account, state, verifier)
    } }
    suspend fun handleOAuthCallback(uri: Uri): String = withContext(Dispatchers.IO) { withSyncLock {
        val params = TrackerOAuth.parameters(uri)
        val candidates = mutable.value.accounts.filter { TrackerOAuth.callbackMatches(it.redirectUri, uri) }
        val account = candidates.firstOrNull { a -> secrets.get("tracking.pending.${a.id}")?.let { JSONObject(it).optString("state") == params["state"] } == true } ?: error("授权回调不匹配或已过期，请重新登录")
        val pending = JSONObject(secrets.get("tracking.pending.${account.id}") ?: error("授权请求已失效"))
        require(System.currentTimeMillis() - pending.getLong("created") < 10 * 60 * 1000) { "授权请求已过期，请重新登录" }
        secrets.remove("tracking.pending.${account.id}")
        require(params["error"] == null) { params["error_description"] ?: "授权已取消" }
        val token = if (account.provider == TrackerProvider.ANILIST) OAuthToken(params["access_token"] ?: error("没有收到访问令牌"), expiresAt = System.currentTimeMillis() + (params["expires_in"]?.toLongOrNull() ?: 31536000) * 1000)
            else OAuthToken.response(raw(account.provider.tokenUrl, "POST", TrackerOAuth.tokenForm(account, secrets.get("tracking.client.${account.id}").orEmpty(), params["code"] ?: error("没有收到授权码"), pending.getString("verifier"))))
        secrets.set("tracking.token.${account.id}", token.json().toString())
        val profile = api.profile(account); saveAccount(profile)
        "${profile.provider.title} 已登录${profile.username.takeIf { it.isNotEmpty() }?.let { "：$it" }.orEmpty()}"
    } }
    private fun token(a: TrackerAccount): OAuthToken? {
        if (a.sourceId.isNotEmpty()) return secrets.get(a.sourceId)?.let { if (a.provider == TrackerProvider.KAVITA) OAuthToken(it.removePrefix("Bearer "), secrets.get("${a.sourceId}.refreshToken").orEmpty()) else OAuthToken(it, type = "Header") }
        return secrets.get("tracking.token.${a.id}")?.let { OAuthToken.stored(JSONObject(it)) }
    }
    private suspend fun refresh(a: TrackerAccount): OAuthToken = authMutex.withLock {
        val old = token(a) ?: error("请重新登录 ${a.name}")
        if (a.provider == TrackerProvider.KAVITA) {
            val j = if (old.refresh.isNotBlank()) raw(a.baseUrl.trimEnd('/') + "/api/Account/refresh-token", "POST", JSONObject().put("token", old.access).put("refreshToken", old.refresh).toString().toRequestBodyJson()) else {
                if (a.sourceId.isNotEmpty()) {
                    val key = secrets.get("${a.sourceId}.apiKey") ?: error("服务器登录已过期，请重新登录")
                    val url = (a.baseUrl.trimEnd('/') + "/api/Plugin/authenticate").toHttpUrl().newBuilder().addQueryParameter("apiKey", key).addQueryParameter("pluginName", AppBrand.NAME).build().toString()
                    return@withLock OAuthToken(raw(url, "POST").getString("token")).also { secrets.set(a.sourceId, "Bearer ${it.access}") }
                }
                val credentials = secrets.get("tracking.credentials.${a.id}") ?: error("服务器登录已过期，请重新登录")
                raw(a.baseUrl.trimEnd('/') + "/api/Account/login", "POST", credentials.toRequestBodyJson())
            }
            return@withLock OAuthToken(j.getString("token"), j.optString("refreshToken")).also {
                if (a.sourceId.isNotEmpty()) { secrets.set(a.sourceId, "Bearer ${it.access}"); secrets.set("${a.sourceId}.refreshToken", it.refresh) }
                else secrets.set("tracking.token.${a.id}", it.json().toString())
            }
        }
        require(old.refresh.isNotBlank()) { "${a.name} 登录已过期，请重新登录" }
        OAuthToken.response(raw(a.provider.tokenUrl, "POST", TrackerOAuth.tokenForm(a, secrets.get("tracking.client.${a.id}").orEmpty(), refresh = old.refresh)), old.refresh).also { secrets.set("tracking.token.${a.id}", it.json().toString()) }
    }
    private suspend fun authorized(a: TrackerAccount, url: String, method: String, body: RequestBody?): JSONObject = withSyncLock {
        val current = mutable.value.accounts.firstOrNull { it.id == a.id }; val login = currentCoroutineContext()[LoginLease]?.account
        require(current?.let { sameOrigin(it, a) } == true || login?.let { sameOrigin(it, a) } == true) { "Tracker account changed; reopen this account" }
        if (a.sourceId.isNotEmpty()) {
            val source = AppRepository.get(context).state.value.sources.firstOrNull { it.id == a.sourceId } ?: error("Linked source was removed")
            require(source.type == a.provider.id && source.baseUrl == a.baseUrl) { "Linked server origin changed; refresh source settings" }
        }
        var token = token(a)
        if (token != null && token.expiresAt < System.currentTimeMillis() + 30000 && token.refresh.isNotEmpty()) token = refresh(a)
        fun header(t: OAuthToken?) = t?.let { if (it.type == "Header") it.access else "${it.type} ${it.access}" }
        try { raw(url, method, body, header(token), a.clientId.takeIf { a.provider == TrackerProvider.MYANIMELIST }) }
        catch (e: TrackerHttpException) {
            if (e.status != 401 || (token?.refresh.isNullOrBlank() && a.provider != TrackerProvider.KAVITA)) throw e
            raw(url, method, body, header(refresh(a)))
        }
    }
    private fun raw(url: String, method: String, body: RequestBody? = null, authorization: String? = null, malClientId: String? = null): JSONObject {
        val b = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "Monogatari/${app.monogatari.android.BuildConfig.VERSION_NAME} (${app.monogatari.android.AppBrand.REPOSITORY_URL})")
        authorization?.let { b.header("Authorization", it) }; malClientId?.let { b.header("X-MAL-CLIENT-ID", it) }
        b.method(method, if (method in listOf("POST", "PUT", "PATCH")) body ?: ByteArray(0).toRequestBodyJson() else body)
        return http.newCall(b.build()).execute().use { response ->
            if (!response.isSuccessful) throw TrackerHttpException(response.code, when (response.code) { 401 -> "${"登录已过期或凭据无效"}"; 403 -> "没有访问权限，请检查授权范围"; 429 -> "请求过于频繁，请稍后重试"; else -> "服务返回 HTTP ${response.code}" })
            val bytes = response.body?.byteStream()?.use { input -> java.io.ByteArrayOutputStream().also { output ->
                val buffer = ByteArray(64 * 1024); var count = 0
                while (true) { val length = input.read(buffer); if (length < 0) break; count += length; require(count < 16 * 1024 * 1024) { "Tracker response exceeds the size limit" }; output.write(buffer, 0, length) }
            }.toByteArray() } ?: ByteArray(0)
            require(bytes.size < 16 * 1024 * 1024) { "追踪服务响应过大" }
            val text = bytes.toString(Charsets.UTF_8).trim()
            when { text.isEmpty() || text == "true" || text == "false" -> JSONObject(); text.startsWith('[') -> JSONObject().put("_array", JSONArray(text)); else -> JSONObject(text) }
        }
    }
    suspend fun syncSourceAccounts(sources: List<SourceConfig>) = withContext(Dispatchers.IO) {
        val supported = sources.filter { it.type in setOf("komga", "kavita", "suwayomi") }
        withSyncLock { change { d -> d.copy(accounts = d.accounts.filter { it.sourceId.isEmpty() } + supported.map { source ->
            val existing = d.accounts.firstOrNull { it.sourceId == source.id }
            val disabled = (AppRepository.get(context).settings.value("${source.id}.disableTracking") ?: SourcePreferences(context, source.id).get("disableTracking")) as? Boolean
            (existing ?: TrackerAccount("source:${source.id}", TrackerProvider.from(source.type))).copy(name = source.name, baseUrl = source.baseUrl, sourceId = source.id, connected = true, autoSync = disabled?.not() ?: existing?.autoSync ?: true)
        }) } }
    }
    suspend fun bindEnhancedSources(manga: List<Manga>, sources: List<SourceConfig>) = withContext(Dispatchers.IO) {
        syncSourceAccounts(sources)
        withSyncLock {
        for (m in manga.filter { it.inLibrary }) for (a in mutable.value.accounts.filter { it.provider.server && it.sourceId == m.sourceId && it.autoSync }) {
            if (mutable.value.bindings.none { it.mangaId == m.id && it.accountId == a.id }) {
                val remote = try { api.read(a, m.key) } catch (e: CancellationException) { throw e } catch (_: Exception) { TrackState(registered = true) }
                put(TrackBinding(m.id, a.id, m.key, m.title, remote))
            }
        }
        }
    }
    suspend fun search(accountId: String, title: String) = withContext(Dispatchers.IO) { api.search(account(accountId), title) }
    private fun account(id: String) = mutable.value.accounts.firstOrNull { it.id == id } ?: error("追踪账户不存在")
    private fun mode(mangaId: String) = SettingsStore(AppRepository.get(context)).int("Manga.chapterDisplayMode.${mangaId.replaceFirst("::", ".")}")
    private fun bindingAccount(binding: TrackBinding): TrackerAccount = account(binding.accountId).let { a ->
        if (a.provider == TrackerProvider.KOMGA) a.copy(useVolumes = komgaVolumes(a, mode(binding.mangaId))) else a
    }
    private fun komgaVolumes(account: TrackerAccount, displayMode: Int): Boolean = when (displayMode) {
        1 -> false; 2 -> true; else -> if (account.sourceId.isEmpty()) account.useVolumes else !((SourcePreferences(context, account.sourceId).get("useChapters") ?: SettingsStore(AppRepository.get(context)).value("${account.sourceId}.useChapters")) as? Boolean ?: false)
    }
    suspend fun bind(manga: Manga, accountId: String, result: TrackSearchResult) = withContext(Dispatchers.IO) {
        withSyncLock {
            val base = account(accountId); val a = if (base.provider == TrackerProvider.KOMGA) base.copy(useVolumes = komgaVolumes(base, mode(manga.id))) else base
            var remote = api.read(a, result.id)
            if (!remote.registered) {
                val completed = manga.chapters.filter { it.completed }
                remote = api.update(a, result.id, remote.copy(status = if (completed.isEmpty()) TrackStatus.PLANNING else TrackStatus.READING, chapter = completed.maxOfOrNull { it.number } ?: 0.0,
                    startDate = completed.map { it.readAt }.filter { it > 0 }.minOrNull()?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }.orEmpty()))
            }
            put(TrackBinding(manga.id, accountId, result.id, result.title, remote, lastSynced = System.currentTimeMillis()))
        }
    }
    suspend fun unbind(binding: TrackBinding) = withContext(Dispatchers.IO) { withSyncLock { change { it.copy(bindings = it.bindings.filter { b -> b.id != binding.id || b.remoteId != binding.remoteId }) } } }
    suspend fun setOffset(binding: TrackBinding, offset: Int) = withContext(Dispatchers.IO) { withSyncLock { change { it.copy(bindings = it.bindings.map { b -> if (b.id == binding.id && b.remoteId == binding.remoteId) b.copy(chapterOffset = offset) else b }) } } }
    suspend fun migrateBindings(oldId: String, newId: String, copy: Boolean, chapterKeys: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
        require(oldId.contains("::") && newId.contains("::"))
        withSyncLock { change { d ->
            val transfers = d.bindings.filter { it.mangaId == oldId }.mapNotNull { b ->
                val a = d.accounts.firstOrNull { it.id == b.accountId } ?: return@mapNotNull null
                if (a.provider.server && a.sourceId != newId.substringBefore("::")) null
                else b.copy(mangaId = newId, remoteId = if (a.provider.server) newId.substringAfter("::") else b.remoteId,
                    pendingPage = null, pendingPages = if (!a.provider.server) b.queuedPages() else b.queuedPages().mapNotNull { page -> chapterKeys[page.key]?.let { page.copy(key = it) } })
            }.filter { b -> d.bindings.none { it.id == b.id } }
            d.copy(bindings = d.bindings.filter { copy || it.mangaId != oldId } + transfers)
        } }
    }
    suspend fun refreshBinding(binding: TrackBinding): TrackState = withContext(Dispatchers.IO) {
        withSyncLock { val current = mutable.value.bindings.firstOrNull { it.id == binding.id && it.remoteId == binding.remoteId } ?: error("Tracker binding changed; reopen this manga")
            api.read(bindingAccount(current), current.remoteId, current.state.entryId).also { put(current.copy(state = it, error = "", lastSynced = System.currentTimeMillis())) } }
    }
    suspend fun update(binding: TrackBinding, state: TrackState) = withContext(Dispatchers.IO) {
        withSyncLock { push(binding.copy(state = state.validated(), pending = true)) }
    }
    private suspend fun push(binding: TrackBinding) {
        val current = mutable.value.bindings.firstOrNull { it.id == binding.id } ?: return
        // A queued UI/reader request may predate an unbind or identifier migration.
        if (current.remoteId != binding.remoteId) return
        var queued = current.copy(state = binding.state, pending = true, pendingPage = null, pendingPages = mergeQueuedPages(current.queuedPages(), binding.queuedPages()))
        put(queued)
        try {
            val a = bindingAccount(queued)
            val state = api.update(a, queued.remoteId, queued.state); queued = queued.copy(state = state)
            for (page in queued.pendingPages.toList()) {
                api.page(a, queued.remoteId, page)
                queued = queued.copy(pendingPages = queued.pendingPages.filterNot { it.key == page.key }); put(queued)
            }
            put(queued.copy(pending = false, pendingPage = null, error = "", lastSynced = System.currentTimeMillis()))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { put(queued.copy(pending = true, error = e.message ?: "Sync failed")); throw e }
    }
    suspend fun retryPending() = withContext(Dispatchers.IO) { withSyncLock {
        mutable.value.bindings.filter { it.pending && mutable.value.accounts.any { a -> a.id == it.accountId && a.connected && a.autoSync } }.forEach { try { push(it) } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
    } }
    suspend fun autoSync(manga: Manga, chapter: Chapter, sources: List<SourceConfig> = emptyList()): List<String> = withContext(Dispatchers.IO) {
        val settings = SettingsStore(AppRepository.get(context))
        if (sources.isNotEmpty()) syncSourceAccounts(sources)
        withSyncLock {
            val currentManga = AppRepository.get(context).state.value.manga.firstOrNull { it.id == manga.id } ?: return@withSyncLock emptyList()
            if (currentManga.title != manga.title || currentManga.url != manga.url) return@withSyncLock emptyList()
            mutable.value.accounts.filter { it.connected && it.autoSync && it.sourceId == manga.sourceId && it.provider.server }.forEach { a ->
                if (mutable.value.bindings.none { it.mangaId == manga.id && it.accountId == a.id }) {
                    val remote = try { api.read(a, manga.key) } catch (e: CancellationException) { throw e } catch (_: Exception) { TrackState(registered = true) }
                    put(TrackBinding(manga.id, a.id, manga.key, manga.title, remote))
                }
            }
            val errors = mutableListOf<String>()
            mutable.value.bindings.filter { it.mangaId == manga.id }.forEach { b ->
                val a = bindingAccount(b); if (!a.connected || !a.autoSync) return@forEach
                if (!a.provider.server && !settings.bool("Tracking.updateAfterReading")) return@forEach
                if (!chapter.completed && !a.provider.server) return@forEach
                val completed = manga.chapters.filter { it.completed }
                val maxRead = completed.maxOfOrNull { it.number.takeIf { n -> n > 0 } ?: it.volume ?: 0.0 } ?: 0.0
                val maxVolume = completed.mapNotNull { it.volume?.toInt() ?: it.number.takeIf { n -> a.useVolumes }?.toInt() }.maxOrNull() ?: 0
                val latest = try { if (b.pending) b.state else api.read(a, b.remoteId, b.state.entryId) } catch (e: CancellationException) { throw e } catch (_: Exception) { b.state }
                val progress = applyOffset(maxRead, b.chapterOffset, latest.totalChapters)
                val today = LocalDate.now().toString()
                val displayMode = mode(manga.id)
                var s = latest.copy(chapter = if (displayMode == 2 || a.useVolumes) latest.chapter else maxOf(latest.chapter, progress), volume = if (displayMode == 1) latest.volume else maxOf(latest.volume, maxVolume))
                if (chapter.completed && s.status !in setOf(TrackStatus.READING, TrackStatus.REREADING)) s = s.copy(status = if (s.status == TrackStatus.COMPLETED) TrackStatus.REREADING else TrackStatus.READING)
                if (chapter.completed && s.startDate.isEmpty()) s = s.copy(startDate = today)
                val currentNumber = chapter.number.takeIf { it > 0 } ?: chapter.volume ?: 0.0
                if (displayMode != 2 && !a.useVolumes && s.totalChapters > 0 && applyOffset(currentNumber, b.chapterOffset, s.totalChapters) >= s.totalChapters) s = s.copy(status = TrackStatus.COMPLETED, finishDate = s.finishDate.ifEmpty { today })
                if ((displayMode == 2 || a.useVolumes || completed.none { it.number > 0 }) && s.totalVolumes > 0 && (chapter.volume ?: chapter.number) >= s.totalVolumes) s = s.copy(status = TrackStatus.COMPLETED, finishDate = s.finishDate.ifEmpty { today })
                try { push(b.copy(state = s, pending = true, pendingPage = if (a.provider.server) ChapterRemoteProgress(chapter.key, chapter.page, chapter.total, chapter.completed, chapter.readAt, System.currentTimeMillis()) else null)) }
                catch (e: CancellationException) { throw e } catch (e: Exception) { errors += "${a.name}：${e.message}" }
            }
            errors
        }
    }
    suspend fun importRemoteProgress(manga: Manga, binding: TrackBinding): Manga = withContext(Dispatchers.IO) {
        val a = bindingAccount(binding)
        val remote = api.read(a, binding.remoteId, binding.state.entryId)
        val pageMap = api.pages(a, binding.remoteId).associateBy { it.key }
        refreshBinding(binding)
        manga.copy(chapters = manga.chapters.map { c ->
            val p = pageMap[c.key]
            if (p != null) c.copy(page = p.page, total = maxOf(c.total, p.total), completed = p.completed, readAt = p.readAt.takeIf { it > 0 } ?: c.readAt)
            else if ((mode(manga.id) == 2 || a.useVolumes) && (c.volume ?: c.number) > 0 && (c.volume ?: c.number) <= remote.volume) c.copy(completed = true, readAt = c.readAt.takeIf { it > 0 } ?: System.currentTimeMillis())
            else if (mode(manga.id) != 2 && c.number > 0 && kotlin.math.floor(c.number) + binding.chapterOffset <= remote.chapter) c.copy(completed = true, readAt = c.readAt.takeIf { it > 0 } ?: System.currentTimeMillis()) else c
        })
    }
    /** Original detail opening always pulls PageTracker history; public trackers follow the original toggle. */
    suspend fun syncOnOpen(repo: AppRepository, mangaId: String): List<String> = withContext(Dispatchers.IO) {
        val includePublic = repo.settings.bool("Tracking.autoSyncFromTracker")
        val bindings = mutable.value.bindings.filter { binding -> binding.mangaId == mangaId && mutable.value.accounts.any { account -> account.id == binding.accountId && account.connected && (account.provider.server && account.autoSync || !account.provider.server && includePublic) } }
        val errors = mutableListOf<String>()
        for (binding in bindings) try { syncBindingProgress(repo, mangaId, binding, automatic = true) } catch (e: CancellationException) { throw e } catch (e: Exception) { errors += e.message.orEmpty() }
        errors
    }
    suspend fun syncBindingProgress(repo: AppRepository, mangaId: String, binding: TrackBinding, automatic: Boolean = false): Int = withContext(Dispatchers.IO) { withSyncLock {
        val linked = mutable.value.bindings.firstOrNull { it.id == binding.id && it.remoteId == binding.remoteId } ?: return@withSyncLock 0
        val account = bindingAccount(linked); val remote = api.read(account, linked.remoteId, linked.state.entryId)
        val pages = if (account.provider.server) api.pages(account, linked.remoteId).associateBy { it.key } else emptyMap()
        val queued = linked.queuedPages().map { it.key }.toSet(); var count = 0
        val displayMode = repo.settings.int("Manga.chapterDisplayMode.${mangaId.replaceFirst("::", ".")}")
        repo.change { state -> state.copy(manga = state.manga.map { manga -> if (manga.id != mangaId) manga else {
            val chapterThreshold = applyOffset(remote.chapter, -linked.chapterOffset, remote.totalChapters)
            val highest = manga.chapters.filter { it.completed }.maxOfOrNull { if (displayMode == 2) it.volume ?: it.number else it.number.takeIf { n -> n != 0.0 } ?: it.volume ?: 0.0 } ?: 0.0
            val volumeMode = displayMode == 2 || account.useVolumes || displayMode == 0 && (remote.chapter == 0.0 || manga.chapters.none { it.number > 0 && kotlin.math.floor(it.number) <= chapterThreshold })
            val canAdvance = !automatic || if (volumeMode) remote.volume > highest else chapterThreshold > highest
            val chapters = manga.chapters.map { chapter ->
                val page = pages[chapter.key]
                if (account.provider.server) {
                    if (page == null || chapter.key in queued || page.readAt > 0 && page.readAt <= chapter.readAt) chapter
                    else { count++; chapter.copy(page = page.page.coerceAtLeast(0), total = maxOf(chapter.total, page.total), completed = page.completed, readAt = page.readAt.takeIf { it > 0 } ?: System.currentTimeMillis()) }
                } else if (canAdvance && !chapter.completed && if (volumeMode) (chapter.volume ?: chapter.number).let { it > 0 && it <= remote.volume } else (if (displayMode == 1) chapter.number.takeIf { it != 0.0 } ?: chapter.volume ?: 0.0 else kotlin.math.floor(chapter.number)).let { it > 0 && it <= chapterThreshold }) {
                    count++; chapter.copy(completed = true, readAt = chapter.readAt.takeIf { it > 0 } ?: System.currentTimeMillis())
                } else chapter
            }
            val metadata = JSONObject(manga.metadata); if (count > 0) metadata.put("lastRead", maxOf(metadata.optLong("lastRead"), chapters.maxOfOrNull { it.readAt } ?: 0))
            manga.copy(chapters = chapters, metadata = metadata.toString())
        } }) }
        put(linked.copy(state = remote, lastSynced = System.currentTimeMillis()))
        count
    } }
    suspend fun migrateSourceBindings(sourceId: String, mangaKeys: Map<String, String>, chapterKeys: Map<String, Map<String, String>> = emptyMap()) = withContext(Dispatchers.IO) { withSyncLock {
        change { data ->
            val mapped = data.bindings.map { binding ->
                val prefix = "$sourceId::"; val oldKey = binding.mangaId.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)
                val key = oldKey?.let { mangaKeys[it] ?: it.takeIf(chapterKeys::containsKey) }
                if (key == null) binding else {
                    val account = data.accounts.firstOrNull { it.id == binding.accountId }
                    binding.copy(mangaId = "$prefix$key", remoteId = if (account?.sourceId == sourceId && account.provider.server) key else binding.remoteId,
                        pendingPage = null, pendingPages = binding.queuedPages().map { page -> page.copy(key = chapterKeys[oldKey.orEmpty()]?.get(page.key) ?: page.key) })
                }
            }.groupBy { it.mangaId to it.accountId }.values.map { group -> require(group.all { it == group.first() }) { "Conflicting tracker bindings after source key migration" }; group.first() }
            data.copy(bindings = mapped)
        }
    } }
    fun exportBackup(): JSONObject = mutable.value.json().also { j ->
        // Login tokens and client secrets never enter normal backups.
        j.getJSONArray("accounts").list().forEach { it.put("connected", false) }
    }
    suspend fun importBackup(j: JSONObject, restoreAuthenticated: Boolean = false) = withContext(Dispatchers.IO) {
        val imported = TrackingData.parse(j)
        withSyncLock { change { old -> imported.copy(accounts = imported.accounts.map { a -> a.copy(connected = old.accounts.any { it.id == a.id && it.connected && it.provider == a.provider && it.baseUrl == a.baseUrl && it.clientId == a.clientId && it.sourceId == a.sourceId } || restoreAuthenticated && a.connected && (secrets.get("tracking.token.${a.id}") != null || a.sourceId.isNotBlank() && secrets.get(a.sourceId) != null)) }) } }
    }
    suspend fun restoreSnapshot(j: JSONObject) = withContext(Dispatchers.IO) { withSyncLock { val snapshot = TrackingData.parse(j); change { snapshot } } }
    suspend fun restoreSourceSnapshot(j: JSONObject, affectedMangaIds: Set<String>) = withContext(Dispatchers.IO) { withSyncLock {
        val snapshot = TrackingData.parse(j); change { current ->
            val bindings = current.bindings.filterNot { it.mangaId in affectedMangaIds }.toMutableList(); snapshot.bindings.withIndex().filter { it.value.mangaId in affectedMangaIds }.forEach { bindings.add(it.index.coerceAtMost(bindings.size), it.value) }; current.copy(bindings = bindings)
        }
    } }
    fun exportIosTrackItems(): JSONArray = JSONArray(mutable.value.bindings.map { binding ->
        val parts = binding.mangaId.split("::", limit = 2)
        val a = mutable.value.accounts.firstOrNull { it.id == binding.accountId }
        JSONObject().put("id", if (a?.provider?.server == true) "${a.sourceId}|${binding.remoteId}" else if (a?.provider == TrackerProvider.SHIKIMORI && binding.state.entryId.isNotEmpty()) binding.state.entryId else binding.remoteId)
            .put("trackerId", a?.provider?.id ?: binding.accountId).put("sourceId", parts.first()).put("mangaId", parts.getOrElse(1) { "" }).put("title", binding.title).put("chapterOffset", binding.chapterOffset)
    })
    suspend fun importIosTrackItems(items: JSONArray) = withContext(Dispatchers.IO) {
        val accounts = mutable.value.accounts.toMutableList()
        val bindings = items.list().mapNotNull { j ->
            val provider = TrackerProvider.entries.firstOrNull { it.id == j.optString("trackerId") } ?: return@mapNotNull null
            val idParts = j.getString("id").split('|', limit = 2)
            val source = j.getString("sourceId")
            val accountId = if (provider.server) "source:${idParts.first()}" else provider.id
            if (accounts.none { it.id == accountId }) accounts += TrackerAccount(accountId, provider, sourceId = if (provider.server) idParts.first() else "")
            TrackBinding("$source::${j.getString("mangaId")}", accountId, if (provider.server) idParts.last() else idParts.first(), j.optString("title"),
                TrackState(entryId = if (provider == TrackerProvider.SHIKIMORI) idParts.first() else ""), chapterOffset = j.optInt("chapterOffset"))
        }
        change { it.copy(accounts = accounts, bindings = bindings) }
    }
    companion object {
        @Volatile private var instance: TrackingRepository? = null
        fun get(context: Context): TrackingRepository = instance?.takeIf { it.context === context.applicationContext } ?: synchronized(this) { instance?.takeIf { it.context === context.applicationContext } ?: TrackingRepository(context.applicationContext).also { instance = it } }
        fun applyOffset(chapter: Double, offset: Int, totalChapters: Int = 0): Double = (chapter + offset).coerceAtLeast(0.0).let { if (totalChapters > 0) it.coerceAtMost(totalChapters.toDouble()) else it }
    }
}
private fun String.toRequestBodyJson(): RequestBody = this.toByteArray().toRequestBodyJson()
private fun ByteArray.toRequestBodyJson(): RequestBody = toRequestBody("application/json".toMediaType())
class TrackingRetryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = try { TrackingRepository.get(applicationContext).retryPending(); Result.success() } catch (e: CancellationException) { throw e } catch (_: Exception) { Result.retry() }
}
