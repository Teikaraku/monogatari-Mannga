package app.monogatari.android.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.account.AccountDevice
import app.monogatari.android.account.AccountProfile
import app.monogatari.android.account.AccountSync
import app.monogatari.android.account.AccountSyncState
import app.monogatari.android.account.SyncApiException
import app.monogatari.android.account.SyncPhase
import app.monogatari.android.data.AppState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The profile the account pages share while they are open; refreshed after each change. */
internal object AccountPages {
    var profile by mutableStateOf<AccountProfile?>(null)
    /** A verification for the next sensitive change, and what it is for. */
    var verification: String? = null
    var pending: String = ""
}

@Composable private fun syncLine(state: AccountSyncState): String = when (state.phase) {
    SyncPhase.SYNCING -> tr("MO_SYNCING")
    SyncPhase.FAILED -> when (state.error) {
        "quota_exceeded" -> tr("MO_SYNC_QUOTA")
        "unauthorized", "device_revoked" -> tr("MO_SYNC_SIGNED_OUT")
        "network" -> tr("MO_NETWORK_ERROR")
        else -> tr("MO_SYNC_FAILED")
    }
    SyncPhase.IDLE -> state.lastSynced?.let { relativeText(it, synced = true) } ?: tr("MO_SYNC_NEVER")
}

/** The account at the top of the settings: who is signed in and how sync is doing, or sign-in. */
@Composable fun AccountCard(vm: MonogatariViewModel) {
    val sync = remember { AccountSync.get(vm.repo.context) }
    val user by sync.account.user.collectAsState()
    val state by sync.state.collectAsState()
    val current = user
    Box(Modifier.readableWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
            .clickable(role = Role.Button) { vm.navigate(if (current == null) "signIn" else "account") }.padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            AccountAvatar(current?.displayName, current?.picture)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(current?.displayName ?: tr("MO_SIGN_IN_OR_REGISTER"), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface)
                if (current != null) Text(syncLine(state), Modifier.padding(top = 2.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private data class LibraryStats(val titles: Int, val chapters: Int, val days: Int)

private fun stats(state: AppState): LibraryStats {
    val zone = java.time.ZoneId.systemDefault()
    val reads = state.manga.flatMap { m -> m.chapters.filter { it.completed || it.readAt > 0 } }
    val days = reads.filter { it.readAt > 0 }.map { java.time.Instant.ofEpochMilli(it.readAt).atZone(zone).toLocalDate() }.toSet().size
    return LibraryStats(state.manga.count { it.inLibrary }, reads.count { it.completed }, days)
}

/** The signed-in 物语 account: profile, security, devices, sync and data. */
@Composable fun AccountScreen(vm: MonogatariViewModel) {
    val context = LocalContext.current
    val sync = remember { AccountSync.get(context) }
    val user by sync.account.user.collectAsState()
    val state by sync.state.collectAsState()
    val appState by vm.state.collectAsState()
    val scope = rememberCoroutineScope()
    var loadError by remember { mutableStateOf<Throwable?>(null) }
    var uploading by remember { mutableStateOf(false) }
    var avatarSheet by remember { mutableStateOf(false) }
    var confirmSignOut by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val profile = AccountPages.profile

    suspend fun load() {
        try {
            val fresh = sync.myAccount().profile()
            sync.account.updateProfile(fresh.name, fresh.email, fresh.avatar, clearPicture = fresh.avatar == null)
            AccountPages.profile = fresh; loadError = null
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { loadError = e }
    }
    LaunchedEffect(Unit) { load() }
    // Signed out (here, by a refused refresh, or from another device): leave the page.
    LaunchedEffect(user) { if (user == null) { AccountPages.profile = null; vm.back() } }
    val current = user ?: return

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        uploading = true
        scope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
                if (bytes.size > 10 * 1024 * 1024) { vm.message = Localization.string(context, "MO_AVATAR_TOO_LARGE"); return@launch }
                val url = sync.api.uploadAvatar(bytes)
                val updated = sync.myAccount().update(avatar = url)
                sync.account.updateProfile(picture = updated.avatar, clearPicture = updated.avatar == null)
                AccountPages.profile = updated
                vm.message = Localization.string(context, "MO_SAVED")
            } catch (e: SyncApiException) { vm.message = Localization.string(context, "MO_AVATAR_FAILED")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { vm.message = Localization.string(context, authErrorKey(e).first) }
            finally { uploading = false }
        }
    }
    fun removeAvatar() {
        uploading = true
        scope.launch {
            try {
                val updated = sync.myAccount().update(avatar = "")
                sync.account.updateProfile(clearPicture = true); AccountPages.profile = updated
                vm.message = Localization.string(context, "MO_SAVED")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { vm.message = Localization.string(context, authErrorKey(e).first) }
            finally { uploading = false }
        }
    }
    /** Sensitive changes ask the user to prove it is them first. */
    fun sensitive(next: String) { AccountPages.pending = next; AccountPages.verification = null; vm.navigate("accountVerify") }

    val name = profile?.name ?: current.displayName
    val email = profile?.email ?: current.email
    val avatar = profile?.avatar ?: current.picture
    val numbers = remember(appState) { stats(appState) }

    Column(Modifier.fillMaxSize()) {
        PageTitle(tr("MO_ACCOUNT"), vm::back, contentWidth = ReadableWidth)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            // Header: avatar (tap to change), name, email.
            Column(Modifier.readableWidth().padding(top = 8.dp, bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val avatarLabel = tr("MO_AVATAR")
                Box(Modifier.clip(CircleShape).clickable(enabled = profile != null && !uploading) { avatarSheet = true }
                    .semantics { role = Role.Button; contentDescription = avatarLabel }) {
                    AccountAvatar(name, avatar, 84.dp)
                    if (uploading) Box(Modifier.size(84.dp).clip(CircleShape).background(Color.Black.copy(alpha = .38f)), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    }
                    Box(Modifier.align(Alignment.BottomEnd).size(26.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface)
                        .border(2.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.PhotoCamera, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(name, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                if (email != null) Text(email, Modifier.padding(top = 2.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // This app's data at a glance.
            Row(Modifier.readableWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp).clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface).padding(vertical = 14.dp)) {
                for ((figure, label) in listOf(numbers.titles to tr("MO_STAT_LIBRARY"), numbers.chapters to tr("MO_STAT_CHAPTERS"), numbers.days to tr("MO_STAT_DAYS"))) {
                    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$figure", fontSize = 19.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                        Text(label, Modifier.padding(top = 3.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            loadError?.let { error -> SettingGroup { SettingRow(authErrorText(error), Icons.Outlined.WifiOff, Color(0xFF8E8E93), onClick = { scope.launch { load() } }) } }
            SettingGroup(tr("MO_PROFILE")) {
                SettingRow(tr("MO_NICKNAME"), value = name, onClick = if (profile == null) null else ({ vm.navigate("accountName") }))
                SettingRow(tr("MO_AVATAR"), onClick = if (profile == null) null else ({ avatarSheet = true }), trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AccountAvatar(name, avatar, 30.dp)
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                })
                SettingRow(tr("MO_ACCOUNT_ID"), value = current.id, onLongClick = {
                    clipboard.setText(AnnotatedString(current.id)); vm.message = Localization.string(context, "MO_ID_COPIED")
                }, trailing = {})
                profile?.createdAt?.let { created ->
                    SettingRow(tr("MO_JOINED"), value = java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.ROOT).format(java.util.Date(created)))
                }
            }
            SettingGroup(tr("MO_SECURITY")) {
                SettingRow(tr("MO_EMAIL"), Icons.Outlined.MailOutline, Color(0xFF007AFF), value = email.orEmpty(),
                    onClick = if (profile == null) null else ({ sensitive("accountEmail") }))
                SettingRow(tr(if (profile?.hasPassword == false) "MO_SET_PASSWORD" else "MO_CHANGE_PASSWORD"), Icons.Outlined.Lock, Color(0xFFFF9500),
                    onClick = if (profile == null) null else ({ sensitive("accountPassword") }))
                SettingRow(tr("MO_LINKED"), Icons.Outlined.Link, Color(0xFF5856D6), value = profile?.let { tr("MO_LINKED_COUNT_%i", it.identities.size) }.orEmpty(),
                    onClick = if (profile == null) null else ({ vm.navigate("accountLinked") }))
                SettingRow(tr("MO_DEVICES"), Icons.Outlined.Devices, Color(0xFF30B0C7), onClick = { vm.navigate("accountDevices") })
            }
            SettingGroup(tr("MO_SYNC")) {
                SettingRow(tr("MO_SYNC_NOW"), Icons.Outlined.Sync, Color(0xFF34C759), subtitle = syncLine(state),
                    onClick = if (state.phase == SyncPhase.SYNCING) null else ({ scope.launch { sync.syncNow() } }),
                    trailing = if (state.phase == SyncPhase.SYNCING) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Pink) }) else null)
            }
            SettingGroup { SettingRow(tr("MO_SIGN_OUT"), titleColor = Pink, onClick = { confirmSignOut = true }, trailing = {}) }
            SettingGroup { SettingRow(tr("MO_DELETE"), titleColor = MaterialTheme.colorScheme.error, onClick = { vm.navigate("accountDelete") }) }
        }
    }
    if (avatarSheet) OptionSheet(tr("MO_AVATAR"), { avatarSheet = false }) {
        val close = LocalSheetClose.current
        SheetActions(listOfNotNull(
            SheetActionItem(tr("MO_AVATAR_CHOOSE"), Icons.Outlined.PhotoLibrary) { close(); pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            if (avatar.isNullOrEmpty()) null else SheetActionItem(tr("MO_AVATAR_REMOVE"), Icons.Outlined.DeleteOutline, destructive = true) { close(); removeAvatar() },
        ), Modifier.padding(horizontal = 16.dp))
    }
    if (confirmSignOut) ConfirmDialog(tr("MO_SIGN_OUT"), tr("MO_SIGN_OUT_MESSAGE"), tr("MO_SIGN_OUT"), onDismiss = { confirmSignOut = false }) {
        vm.run { sync.signOut() }
    }
}

@Composable fun AccountNameScreen(vm: MonogatariViewModel) {
    val context = LocalContext.current
    val sync = remember { AccountSync.get(context) }
    val user by sync.account.user.collectAsState()
    val initial = remember { AccountPages.profile?.name ?: user?.displayName.orEmpty() }
    var name by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun save() {
        val value = name.trim()
        if (value.isEmpty()) { error = FormError.Check("MO_NICKNAME_EMPTY"); return }
        if (value.codePointCount(0, value.length) > 24) { error = FormError.Check("MO_NICKNAME_TOO_LONG"); return }
        if (value == initial) { vm.back(); return }
        busy = true; error = null
        scope.launch {
            try {
                val updated = sync.myAccount().update(name = value)
                sync.account.updateProfile(name = updated.name); AccountPages.profile = updated
                vm.message = Localization.string(context, "MO_SAVED"); vm.back()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e) } finally { busy = false }
        }
    }
    AuthPage(tr("MO_NICKNAME"), vm::back) {
        SettingGroup { AuthField(name, { name = it }, tr("MO_NICKNAME"), imeAction = ImeAction.Done, onDone = ::save, autofocus = true, maxLength = 48, divider = false) }
        AuthError(error?.text())
        AuthButton(tr("SAVE"), ::save, busy)
    }
}

/** Proves it is the user before a sensitive change: the current password, or for an account without one a code sent to its email. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable fun AccountVerifyScreen(vm: MonogatariViewModel) {
    val profile = AccountPages.profile ?: run { LaunchedEffect(Unit) { vm.back() }; return }
    val context = LocalContext.current
    val api = remember { AccountSync.get(context).myAccount() }
    var password by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var codeVerification by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val byCode = !profile.hasPassword
    suspend fun sendCode(): Boolean {
        val email = profile.email ?: return false
        return try { codeVerification = api.sendCode(email); true }
        catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = FormError.Failed(e); false }
    }
    LaunchedEffect(byCode) { if (byCode) sendCode() }
    fun run(verify: suspend () -> String) {
        busy = true; error = null
        scope.launch {
            try {
                AccountPages.verification = verify()
                val next = AccountPages.pending
                vm.back(); if (next.isNotEmpty()) vm.navigate(next)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e); code = "" } finally { busy = false }
        }
    }
    if (byCode) AuthPage(tr("MO_VERIFY_TITLE"), vm::back, tr("MO_CODE_SENT_%@", profile.email.orEmpty())) {
        CodeInput(code, { next -> code = next; if (next.length == 6 && !busy) run { api.confirmCode(profile.email.orEmpty(), codeVerification.orEmpty(), next) } }, enabled = !busy)
        AuthError(error?.text())
        ResendCode { sendCode() }
    } else AuthPage(tr("MO_VERIFY_TITLE"), vm::back) {
        SettingGroup {
            AuthField(password, { password = it }, tr("MO_PASSWORD"), tr("MO_CURRENT_PASSWORD_HINT"), password = true, contentType = ContentType.Password,
                imeAction = ImeAction.Done, onDone = { if (password.isNotEmpty()) run { api.verifyPassword(password) } }, autofocus = true, divider = false)
        }
        AuthError(error?.text())
        AuthButton(tr("CONTINUE"), { if (password.isEmpty()) error = FormError.Check("MO_PASSWORD_MISSING") else run { api.verifyPassword(password) } }, busy)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable fun AccountPasswordScreen(vm: MonogatariViewModel) {
    val profile = AccountPages.profile; val verification = AccountPages.verification
    if (profile == null || verification == null) { LaunchedEffect(Unit) { vm.back() }; return }
    val context = LocalContext.current
    var password by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun submit() {
        passwordProblem(password)?.let { error = FormError.Check(it); return }
        if (password != again) { error = FormError.Check("MO_PASSWORD_MISMATCH"); return }
        busy = true; error = null
        scope.launch {
            try {
                val sync = AccountSync.get(context)
                sync.myAccount().changePassword(password, verification)
                AccountPages.profile = runCatching { sync.myAccount().profile() }.getOrNull() ?: profile.copy(hasPassword = true)
                AccountPages.verification = null
                vm.message = Localization.string(context, "MO_PASSWORD_CHANGED"); vm.back()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e) } finally { busy = false }
        }
    }
    AuthPage(tr(if (profile.hasPassword) "MO_CHANGE_PASSWORD" else "MO_SET_PASSWORD"), vm::back) {
        SettingGroup {
            AuthField(password, { password = it }, tr("MO_NEW_PASSWORD"), tr("MO_PASSWORD_HINT"), password = true, contentType = ContentType.NewPassword, autofocus = true, maxLength = 72)
            AuthField(again, { again = it }, tr("MO_PASSWORD_AGAIN"), tr("MO_PASSWORD_AGAIN_HINT"), password = true, contentType = ContentType.NewPassword,
                imeAction = ImeAction.Done, onDone = ::submit, maxLength = 72, divider = false)
        }
        PasswordStrengthBar(password)
        AuthError(error?.text())
        AuthButton(tr("SAVE"), ::submit, busy)
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable fun AccountEmailScreen(vm: MonogatariViewModel) {
    val profile = AccountPages.profile; val verification = AccountPages.verification
    if (profile == null || verification == null) { LaunchedEffect(Unit) { vm.back() }; return }
    val context = LocalContext.current
    val sync = remember { AccountSync.get(context) }
    var email by remember { mutableStateOf("") }
    var sentTo by remember { mutableStateOf<String?>(null) }
    var codeVerification by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun send() {
        val address = email.trim().lowercase()
        if (!isEmail(address)) { error = FormError.Check("MO_EMAIL_INVALID"); return }
        if (address == profile.email) { error = FormError.Check("MO_EMAIL_SAME"); return }
        busy = true; error = null
        scope.launch {
            try { codeVerification = sync.myAccount().sendCode(address); sentTo = address }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = FormError.Failed(e) } finally { busy = false }
        }
    }
    fun confirm(value: String) {
        val address = sentTo ?: return
        busy = true; error = null
        scope.launch {
            try {
                val api = sync.myAccount()
                api.changeEmail(address, verification, api.confirmCode(address, codeVerification, value))
                sync.account.updateProfile(email = address)
                AccountPages.profile = runCatching { api.profile() }.getOrNull() ?: profile.copy(email = address)
                AccountPages.verification = null
                vm.message = Localization.string(context, "MO_EMAIL_CHANGED"); vm.back()
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e); code = "" } finally { busy = false }
        }
    }
    val target = sentTo
    if (target != null) AuthPage(tr("MO_CODE_TITLE"), { sentTo = null; code = "" }, tr("MO_CODE_SENT_%@", target)) {
        CodeInput(code, { next -> code = next; if (next.length == 6 && !busy) confirm(next) }, enabled = !busy)
        AuthError(error?.text())
        ResendCode {
            try { codeVerification = sync.myAccount().sendCode(target); true }
            catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = FormError.Failed(e); false }
        }
    } else AuthPage(tr("MO_CHANGE_EMAIL"), vm::back) {
        SettingGroup {
            AuthField(email, { email = it }, tr("MO_NEW_EMAIL"), "you@example.com", keyboard = KeyboardType.Email, contentType = ContentType.EmailAddress,
                imeAction = ImeAction.Done, onDone = ::send, autofocus = true, divider = false)
        }
        AuthError(error?.text())
        AuthButton(tr("MO_SEND_CODE"), ::send, busy, tr("MO_SENDING_CODE"))
    }
}

@Composable fun AccountLinkedScreen(vm: MonogatariViewModel) {
    val profile = AccountPages.profile ?: run { LaunchedEffect(Unit) { vm.back() }; return }
    val context = LocalContext.current
    var busy by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // Coming back from the identity check: finish the unlink it was for.
    LaunchedEffect(Unit) {
        val target = AccountPages.pending.removePrefix("unlink:").takeIf { AccountPages.pending.startsWith("unlink:") } ?: return@LaunchedEffect
        val verification = AccountPages.verification ?: return@LaunchedEffect
        AccountPages.pending = ""; AccountPages.verification = null
        busy = target
        try {
            val api = AccountSync.get(context).myAccount()
            api.unlinkSocial(target, verification)
            AccountPages.profile = api.profile()
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { vm.message = Localization.string(context, authErrorKey(e).first) } finally { busy = null }
    }
    fun label(provider: String) = if (provider == "google") "Google" else "GitHub"
    Column(Modifier.fillMaxSize()) {
        PageTitle(tr("MO_LINKED"), vm::back, contentWidth = ReadableWidth)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState())) {
            SettingGroup {
                for (provider in listOf("google", "github")) {
                    val linked = provider in profile.identities
                    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp).semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(29.dp), contentAlignment = Alignment.Center) { SocialLogo(provider, 24.dp) }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(label(provider), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(profile.identities[provider]?.takeIf(String::isNotEmpty) ?: tr(if (linked) "MO_LINKED_YES" else "MO_LINKED_NO"),
                                fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (busy == provider) CircularProgressIndicator(Modifier.padding(end = 12.dp).size(18.dp), strokeWidth = 2.dp, color = Pink)
                        else if (linked) TextButton({
                            // Keep at least one way in: a password, or another linked account.
                            if (!profile.hasPassword && profile.identities.size <= 1) vm.message = Localization.string(context, "MO_UNLINK_LAST")
                            else confirm = provider
                        }) { Text(tr("MO_UNLINK"), color = Pink) }
                    }
                    if (provider == "google") HorizontalDivider(Modifier.padding(start = 55.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
    confirm?.let { provider ->
        ConfirmDialog(tr("MO_UNLINK_TITLE_%@", label(provider)), tr("MO_UNLINK_MESSAGE"), tr("MO_UNLINK"), destructive = true, onDismiss = { confirm = null }) {
            AccountPages.pending = "unlink:$provider"; AccountPages.verification = null
            vm.navigate("accountVerify")
        }
    }
}

@Composable fun AccountDevicesScreen(vm: MonogatariViewModel) {
    val context = LocalContext.current
    val sync = remember { AccountSync.get(context) }
    var devices by remember { mutableStateOf<List<AccountDevice>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<AccountDevice?>(null) }
    var round by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(round) {
        failed = false
        try { devices = sync.api.devices() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { failed = true }
    }
    Column(Modifier.fillMaxSize()) {
        PageTitle(tr("MO_DEVICES"), vm::back, contentWidth = ReadableWidth)
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            val list = devices
            when {
                failed && list == null -> EmptyPage(Icons.Outlined.WifiOff, tr("MO_NETWORK_ERROR"), "", tr("RETRY")) { round++ }
                list == null -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Pink)
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    SettingGroup {
                        for (device in list) SettingRow(device.name, if (device.platform == "android" || device.platform == "ios") Icons.Outlined.PhoneAndroid else Icons.Outlined.Laptop,
                            if (device.current) Color(0xFF34C759) else Color(0xFF8E8E93),
                            subtitle = if (device.current) tr("MO_THIS_DEVICE") else relativeText(device.lastSeen, synced = false),
                            trailing = { if (!device.current) TextButton({ confirm = device }) { Text(tr("MO_DEVICE_REMOVE"), color = Pink) } })
                    }
                }
            }
        }
    }
    confirm?.let { device ->
        ConfirmDialog(tr("MO_DEVICE_REMOVE_TITLE_%@", device.name), tr("MO_DEVICE_REMOVE_MESSAGE"), tr("MO_DEVICE_REMOVE"), destructive = true, onDismiss = { confirm = null }) {
            scope.launch {
                try { sync.api.removeDevice(device.id); round++ }
                catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { vm.message = Localization.string(context, "MO_SYNC_FAILED") }
            }
        }
    }
}

/** Deletes this app's data from the account; the 物语 account itself is kept. */
@Composable fun AccountDeleteScreen(vm: MonogatariViewModel) {
    val context = LocalContext.current
    val phrase = tr("MO_DELETE_PHRASE")
    var typed by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<FormError?>(null) }
    val scope = rememberCoroutineScope()
    AuthPage(tr("MO_DELETE"), vm::back, tr("MO_DELETE_MESSAGE")) {
        SettingGroup(tr("MO_DELETE_TYPE_%@", phrase)) {
            AuthField(typed, { typed = it }, tr("MO_DELETE_CONFIRM"), phrase, imeAction = ImeAction.Done, divider = false)
        }
        AuthError(error?.text())
        AuthButton(tr("MO_DELETE"), {
            busy = true; error = null
            scope.launch {
                try {
                    AccountSync.get(context).deleteAppData()
                    vm.message = Localization.string(context, "MO_DELETED")
                    while (vm.screen.startsWith("account")) vm.back()
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Exception) { error = FormError.Check("MO_NETWORK_ERROR") } finally { busy = false }
            }
        }, busy, enabled = typed.trim() == phrase, destructive = true)
    }
}
