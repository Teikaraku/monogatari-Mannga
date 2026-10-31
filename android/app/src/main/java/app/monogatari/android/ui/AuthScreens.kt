package app.monogatari.android.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.account.AccountException
import app.monogatari.android.account.AccountSync
import app.monogatari.android.account.AuthFailure
import app.monogatari.android.account.AuthInteraction
import app.monogatari.android.account.SavedCredentials
import kotlinx.coroutines.launch

/**
 * The sign-in screens share one interaction while they run (the email-code step needs the cookies the
 * first step opened), so it lives here rather than in a route key.
 */
internal object AuthFlow {
    var interaction: AuthInteraction? = null
    var email: String = ""
    var verification: String = ""
    /** register or forgot */
    var purpose: String = "register"
}

/** Closes every sign-in screen once signed in and greets the user. */
private fun finished(vm: MonogatariViewModel) {
    AuthFlow.interaction = null
    while (vm.screen in setOf("signIn", "register", "forgotPassword", "authCode", "newPassword")) vm.back()
    val name = AccountSync.get(vm.repo.context).account.user.value?.displayName.orEmpty()
    vm.message = Localization.string(vm.repo.context, "MO_WELCOME_%@", name)
}

private suspend fun finish(vm: MonogatariViewModel, interaction: AuthInteraction) {
    val code = interaction.submit()
    AccountSync.get(vm.repo.context).completeSignIn(code, interaction.codeVerifier)
    finished(vm)
}

@Composable private fun socialSignIn(vm: MonogatariViewModel): Pair<String?, (String) -> Unit> {
    var busy by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    return busy to { provider ->
        busy = provider
        scope.launch {
            try {
                AccountSync.get(context).signInWithBrowser(provider)
                finished(vm)
            } catch (e: AccountException) {
                if (e.code != "cancelled") vm.message = Localization.string(context, "MO_SOCIAL_UNAVAILABLE")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { vm.message = Localization.string(context, "MO_SOCIAL_UNAVAILABLE") }
            finally { busy = null }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable fun SignInScreen(vm: MonogatariViewModel) {
    val context = LocalContext.current
    val saved = remember { SavedCredentials(context) }
    val initial = remember { saved.read() }
    var email by rememberSaveable { mutableStateOf(initial?.email.orEmpty()) }
    var password by rememberSaveable { mutableStateOf(initial?.password.orEmpty()) }
    var remember by rememberSaveable { mutableStateOf(initial?.password != null) }
    var error by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val (social, signInWith) = socialSignIn(vm)
    fun submit() {
        val address = email.trim().lowercase()
        if (!isEmail(address)) { error = FormError.Check("MO_EMAIL_INVALID"); return }
        if (password.isEmpty()) { error = FormError.Check("MO_PASSWORD_MISSING"); return }
        busy = true; error = null
        scope.launch {
            try {
                val interaction = AccountSync.get(context).account.interaction()
                interaction.start("SignIn")
                interaction.identify(interaction.verifyPassword(address, password))
                saved.save(address, if (remember) password else null)
                finish(vm, interaction)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e) }
            finally { busy = false }
        }
    }
    AuthPage(tr("MO_SIGN_IN_TITLE"), vm::back) {
        SettingGroup {
            AuthField(email, { email = it }, tr("MO_EMAIL"), "you@example.com", keyboard = KeyboardType.Email,
                contentType = ContentType.Username + ContentType.EmailAddress)
            AuthField(password, { password = it }, tr("MO_PASSWORD"), tr("MO_PASSWORD_HINT"), password = true, contentType = ContentType.Password,
                imeAction = ImeAction.Done, onDone = ::submit, divider = false)
        }
        Row(Modifier.readableWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            RememberBox(remember, tr("MO_REMEMBER_PASSWORD")) { checked ->
                remember = checked
                // Unchecking forgets a saved password at once.
                if (!checked) saved.forgetPassword()
            }
            Spacer(Modifier.weight(1f))
            TextButton({ AuthFlow.email = email.trim(); vm.navigate("forgotPassword") }) { Text(tr("MO_FORGOT_PASSWORD"), fontSize = 15.sp, color = Pink) }
        }
        AuthError(error?.text())
        AuthButton(tr("MO_SIGN_IN"), ::submit, busy, tr("MO_SIGNING_IN"))
        SocialButtons(social, signInWith)
        AuthLink(tr("MO_REGISTER"), tr("MO_NO_ACCOUNT")) { AuthFlow.email = email.trim(); vm.back(); vm.navigate("register") }
    }
}

/** Registration and password reset, step 1: the email. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable fun EmailStepScreen(vm: MonogatariViewModel, purpose: String) {
    val context = LocalContext.current
    var email by rememberSaveable { mutableStateOf(AuthFlow.email) }
    var error by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val (social, signInWith) = socialSignIn(vm)
    val event = if (purpose == "register") "Register" else "ForgotPassword"
    fun submit() {
        val address = email.trim().lowercase()
        if (!isEmail(address)) { error = FormError.Check("MO_EMAIL_INVALID"); return }
        busy = true; error = null
        scope.launch {
            try {
                val interaction = AccountSync.get(context).account.interaction()
                interaction.start(event)
                AuthFlow.verification = interaction.sendCode(event, address)
                AuthFlow.interaction = interaction; AuthFlow.email = address; AuthFlow.purpose = purpose
                vm.navigate("authCode")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e) }
            finally { busy = false }
        }
    }
    AuthPage(tr(if (purpose == "register") "MO_REGISTER_TITLE" else "MO_FORGOT_TITLE"), vm::back) {
        SettingGroup {
            AuthField(email, { email = it }, tr("MO_EMAIL"), "you@example.com", keyboard = KeyboardType.Email, contentType = ContentType.EmailAddress,
                imeAction = ImeAction.Done, onDone = ::submit, autofocus = email.isEmpty(), divider = false)
        }
        AuthError(error?.text())
        AuthButton(tr("MO_SEND_CODE"), ::submit, busy, tr("MO_SENDING_CODE"))
        if (purpose == "register") {
            SocialButtons(social, signInWith)
            AuthLink(tr("MO_SIGN_IN"), tr("MO_HAVE_ACCOUNT")) { AuthFlow.email = email.trim(); vm.back(); vm.navigate("signIn") }
        }
    }
}

/** The 6-digit code sent to the email. */
@Composable fun AuthCodeScreen(vm: MonogatariViewModel) {
    val interaction = AuthFlow.interaction
    if (interaction == null) { LaunchedEffect(Unit) { vm.back() }; return }
    val context = LocalContext.current
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<FormError?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val register = AuthFlow.purpose == "register"
    fun verify(value: String) {
        busy = true; error = null
        scope.launch {
            try {
                val verified = interaction.verifyCode(AuthFlow.email, AuthFlow.verification, value)
                if (register) {
                    try { interaction.addEmail(verified) } catch (e: AuthFailure) {
                        if (e.code != "user.email_already_in_use") throw e
                        // Already registered: the verified email signs straight in.
                        interaction.switchEvent("SignIn")
                        interaction.identify(verified)
                        finish(vm, interaction)
                        vm.message = Localization.string(context, "MO_ALREADY_REGISTERED")
                        return@launch
                    }
                } else interaction.identify(verified)
                vm.back(); vm.navigate("newPassword")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e); code = "" }
            finally { busy = false }
        }
    }
    AuthPage(tr("MO_CODE_TITLE"), vm::back, tr("MO_CODE_SENT_%@", AuthFlow.email)) {
        CodeInput(code, { next -> code = next; if (next.length == 6 && !busy) verify(next) }, enabled = !busy)
        AuthError(error?.text())
        if (busy) Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator(color = Pink) }
        ResendCode {
            try {
                AuthFlow.verification = interaction.sendCode(if (register) "Register" else "ForgotPassword", AuthFlow.email)
                vm.message = Localization.string(context, "MO_CODE_RESENT"); true
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e); false }
        }
    }
}

/** Registration step 3, or the new password after a reset; both end signed in. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable fun NewPasswordScreen(vm: MonogatariViewModel) {
    val interaction = AuthFlow.interaction
    if (interaction == null) { LaunchedEffect(Unit) { vm.back() }; return }
    val register = AuthFlow.purpose == "register"
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
                if (register) { interaction.setPassword(password); interaction.identify() } else interaction.resetPassword(password)
                finish(vm, interaction)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) { error = FormError.Failed(e) }
            finally { busy = false }
        }
    }
    AuthPage(tr(if (register) "MO_SET_PASSWORD_TITLE" else "MO_NEW_PASSWORD_TITLE"), vm::back) {
        SettingGroup {
            AuthField(password, { password = it }, tr("MO_PASSWORD"), tr("MO_PASSWORD_HINT"), password = true, contentType = ContentType.NewPassword,
                autofocus = true, maxLength = 72)
            AuthField(again, { again = it }, tr("MO_PASSWORD_AGAIN"), tr("MO_PASSWORD_AGAIN_HINT"), password = true, contentType = ContentType.NewPassword,
                imeAction = ImeAction.Done, onDone = ::submit, maxLength = 72, divider = false)
        }
        PasswordStrengthBar(password)
        AuthError(error?.text())
        AuthButton(tr(if (register) "MO_FINISH_REGISTER" else "MO_SAVE_AND_SIGN_IN"), ::submit, busy)
    }
}
