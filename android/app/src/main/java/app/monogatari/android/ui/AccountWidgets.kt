package app.monogatari.android.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.account.AccountException
import app.monogatari.android.account.AuthFailure
import app.monogatari.android.account.SyncApiException
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val emailPattern = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
fun isEmail(value: String) = emailPattern.matches(value.trim())

/** The identity center's password policy, checked first here: 8 to 72 characters, at least two kinds. Returns the key of the problem. */
fun passwordProblem(value: String): String? {
    if (value.length < 8) return "MO_PASSWORD_TOO_SHORT"
    if (value.length > 72) return "MO_PASSWORD_TOO_LONG"
    val kinds = listOf(Regex("[a-z]"), Regex("[A-Z]"), Regex("\\d"), Regex("[^A-Za-z0-9]")).count { it.containsMatchIn(value) }
    return if (kinds < 2) "MO_PASSWORD_TYPES" else null
}

/** 0 to 3, for the strength bar under a new password. */
fun passwordStrength(value: String): Int {
    if (value.isEmpty()) return 0
    var score = 0
    if (value.length >= 8) score++
    if (Regex("[A-Za-z]").containsMatchIn(value) && Regex("\\d").containsMatchIn(value)) score++
    if (value.length >= 12 || Regex("[^A-Za-z0-9]").containsMatchIn(value)) score++
    return score
}

/** The message key (and argument) for a failed account request. */
fun authErrorKey(error: Throwable): Pair<String, Any?> {
    if (error is AccountException) return when (error.code) {
        "network" -> "MO_NETWORK_ERROR" to null
        "signed_out" -> "MO_SYNC_SIGNED_OUT" to null
        else -> "MO_ERROR_GENERIC" to null
    }
    if (error is SyncApiException) return (if (error.code == "network") "MO_NETWORK_ERROR" else "MO_ERROR_GENERIC") to null
    if (error !is AuthFailure) return "MO_ERROR_GENERIC" to null
    val issues = error.passwordIssues
    if (issues.isNotEmpty() || error.code.startsWith("password")) {
        for (issue in issues) when (issue) {
            "password_rejected.pwned" -> return "MO_PASSWORD_PWNED" to null
            "password_rejected.restricted.sequence", "password_rejected.restricted.repetition" -> return "MO_PASSWORD_SEQUENCE" to null
            "password_rejected.restricted.user_info" -> return "MO_PASSWORD_USER_INFO" to null
            "password_rejected.too_short" -> return "MO_PASSWORD_TOO_SHORT" to null
            "password_rejected.too_long" -> return "MO_PASSWORD_TOO_LONG" to null
            "password_rejected.character_types" -> return "MO_PASSWORD_TYPES" to null
        }
        return "MO_PASSWORD_TYPES" to null
    }
    return when (error.code) {
        "session.invalid_credentials" -> "MO_WRONG_PASSWORD" to null
        "session.verification_blocked_too_many_attempts" -> "MO_LOCKED" to null
        "user.suspended" -> "MO_SUSPENDED" to null
        "verification_code.code_mismatch" -> "MO_CODE_WRONG" to null
        "verification_code.expired" -> "MO_CODE_EXPIRED" to null
        "verification_code.exceed_max_try" -> "MO_CODE_TOO_MANY" to null
        "user.email_already_in_use" -> "MO_EMAIL_TAKEN" to null
        "user.identity_not_exist", "user.email_not_exist" -> "MO_EMAIL_UNKNOWN" to null
        "session.not_found", "session_lost", "session.verification_session_not_found", "session.verification_failed",
        "verification_record.not_found", "verification_record.permission_denied" -> "MO_SESSION_LOST" to null
        "connector.general" -> "MO_SOCIAL_UNAVAILABLE" to null
        "gate_rate", "request.too_many_requests" -> error.retryAfter?.takeIf { it < 120 }?.let { "MO_TOO_MANY_SECONDS_%i" to it } ?: ("MO_TOO_MANY" to null)
        "gate_pow" -> "MO_BOT_CHECK_FAILED" to null
        "gate_unavailable" -> "MO_BOT_CHECK_UNAVAILABLE" to null
        "network" -> "MO_NETWORK_ERROR" to null
        "user.identity_already_in_use", "user.social_account_exists_in_profile" -> "MO_SOCIAL_ALREADY_LINKED" to null
        else -> "MO_ERROR_GENERIC" to null
    }
}

@Composable fun authErrorText(error: Throwable): String = authErrorKey(error).let { (key, arg) -> if (arg == null) tr(key) else tr(key, arg) }

/** What a form shows under its fields: a check made here, or a request that failed. */
sealed interface FormError {
    data class Check(val key: String) : FormError
    data class Failed(val error: Throwable) : FormError
}

@Composable fun FormError.text(): String = when (this) { is FormError.Check -> tr(key); is FormError.Failed -> authErrorText(error) }

/** A grouped settings-style page for the account forms, scrolling above the keyboard. */
@Composable fun AuthPage(title: String, back: () -> Unit, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxSize()) {
        PageTitle(title, back, contentWidth = ReadableWidth)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).clickable(indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { focus.clearFocus() }
            .verticalScroll(rememberScrollState()).imePadding().padding(top = 4.dp, bottom = 32.dp)) {
            if (subtitle != null) Text(subtitle, Modifier.readableWidth().padding(horizontal = 20.dp, vertical = 0.dp).padding(bottom = 8.dp),
                fontSize = 15.sp, lineHeight = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

/** A labelled text field on a grouped card row. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable fun AuthField(
    value: String, onChange: (String) -> Unit, label: String, hint: String = "", password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text, contentType: ContentType? = null, imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null, autofocus: Boolean = false, maxLength: Int? = null, divider: Boolean = true,
) {
    var hidden by rememberSaveable { mutableStateOf(password) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(autofocus) { if (autofocus) runCatching { focus.requestFocus() } }
    val color = MaterialTheme.colorScheme.onSurface
    // The whole row focuses the field, not just the text's own line.
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(indication = null,
        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { runCatching { focus.requestFocus() } }
        .padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.width(80.dp), fontSize = 16.sp, color = color)
        Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
            if (value.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f))
            BasicTextField(value, { next -> onChange(if (maxLength != null) next.take(maxLength) else next) },
                Modifier.fillMaxWidth().focusRequester(focus).semantics {
                    contentDescription = label
                    if (contentType != null) this.contentType = contentType
                },
                singleLine = true, textStyle = TextStyle(fontSize = 16.sp, color = color), cursorBrush = SolidColor(Pink),
                visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = imeAction, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }))
        }
        if (password) IconButton({ hidden = !hidden }) {
            Icon(if (hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, tr(if (hidden) "MO_SHOW_PASSWORD" else "MO_HIDE_PASSWORD"),
                Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Spacer(Modifier.width(12.dp))
    }
    if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** The full-width primary button; shows a spinner while [busy]. */
@Composable fun AuthButton(label: String, onClick: () -> Unit, busy: Boolean = false, busyLabel: String? = null, enabled: Boolean = true, destructive: Boolean = false) {
    Box(Modifier.readableWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        Button(onClick, Modifier.fillMaxWidth().height(50.dp).semantics { if (busy) stateDescription = busyLabel ?: label }, enabled = enabled && !busy,
            shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (destructive) MaterialTheme.colorScheme.error else Pink,
                contentColor = Color.White, disabledContainerColor = (if (destructive) MaterialTheme.colorScheme.error else Pink).copy(alpha = .45f), disabledContentColor = Color.White)) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                if (busyLabel != null) { Spacer(Modifier.width(10.dp)); Text(busyLabel, fontSize = 16.sp) }
            } else Text(label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** A red line explaining the last failure, announced to screen readers. */
@Composable fun AuthError(message: String?) {
    if (message == null) { Spacer(Modifier.height(4.dp)); return }
    Text(message, Modifier.readableWidth().padding(start = 32.dp, end = 32.dp, top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
        fontSize = 14.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.error)
}

/** A secondary text link, centred under the form. */
@Composable fun AuthLink(label: String, lead: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (lead != null) Text(lead, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick, enabled = enabled) { Text(label, fontSize = 15.sp, color = if (enabled) Pink else MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** The 3-segment strength bar under a new password. */
@Composable fun PasswordStrengthBar(password: String) {
    val score = passwordStrength(password)
    val colors = listOf(Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFF34C759))
    val labels = listOf(tr("MO_STRENGTH_WEAK"), tr("MO_STRENGTH_FAIR"), tr("MO_STRENGTH_STRONG"))
    Row(Modifier.readableWidth().padding(start = 32.dp, end = 32.dp, top = 2.dp, bottom = 4.dp).semantics(mergeDescendants = true) {
        if (score > 0) contentDescription = labels[score - 1]
    }, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until 3) {
            val fill by animateColorAsState(if (i < score) colors[score - 1] else MaterialTheme.colorScheme.outlineVariant, label = "strength")
            Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(fill))
            if (i < 2) Spacer(Modifier.width(6.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(if (score == 0) "" else labels[score - 1], Modifier.width(30.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Six digit boxes for an emailed code; a hidden field takes typing, pasting and the keyboard's one-time-code suggestion. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable fun CodeInput(code: String, onChange: (String) -> Unit, enabled: Boolean = true) {
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val label = tr("MO_CODE_LABEL")
    Box(Modifier.readableWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
        BasicTextField(code, { next -> onChange(next.filter(Char::isDigit).take(6)) },
            Modifier.matchParentSize().alpha(0f).focusRequester(focus).onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = label; contentType = ContentType.SmsOtpCode },
            enabled = enabled, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done))
        Row(Modifier.fillMaxWidth().clickable(enabled = enabled) { runCatching { focus.requestFocus() } }.clearAndSetSemantics {
            contentDescription = label; stateDescription = code
        }, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (i in 0 until 6) {
                val active = focused && i == code.length
                Box(Modifier.weight(1f).aspectRatio(.86f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                    .border(BorderStroke(1.5.dp, if (active) Pink else Color.Transparent), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                    Text(code.getOrNull(i)?.toString().orEmpty(), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** The resend link with its 60-second countdown. */
@Composable fun ResendCode(onResend: suspend () -> Boolean) {
    var left by rememberSaveable { mutableIntStateOf(60) }
    var sending by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    LaunchedEffect(round) { while (left > 0) { delay(1_000); left-- } }
    val scope = rememberCoroutineScope()
    AuthLink(if (left > 0) tr("MO_RESEND_IN_%i", left) else tr("MO_RESEND"), enabled = left <= 0 && !sending) {
        sending = true
        scope.launch {
            try { if (onResend()) { left = 60; round++ } } finally { sending = false }
        }
    }
}

/** A round check box with its label; the whole row toggles it. */
@Composable fun RememberBox(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Checkbox) { onChange(!checked) }
        .semantics(mergeDescendants = true) { toggleableState = androidx.compose.ui.state.ToggleableState(checked) }
        .padding(horizontal = 4.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(if (checked) Pink else Color.Transparent)
            .border(1.5.dp, if (checked) Pink else MaterialTheme.colorScheme.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            if (checked) Icon(Icons.Outlined.Check, null, Modifier.size(14.dp), tint = Color.White)
        }
        Spacer(Modifier.width(8.dp))
        Text(label, fontSize = 15.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The Google "G" and the GitHub mark, drawn from their published paths. */
@Composable fun SocialLogo(provider: String, size: androidx.compose.ui.unit.Dp = 20.dp) {
    val github = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        if (provider == "google") scale(this.size.width / 48f, Offset.Zero) {
            fun path(block: Path.() -> Unit) = Path().apply(block)
            drawPath(path { moveTo(43.6f, 20.5f); lineTo(42f, 20.5f); lineTo(42f, 20.4f); lineTo(24f, 20.4f); lineTo(24f, 27.6f); lineTo(35.3f, 27.6f)
                cubicTo(34.2f, 30.8f, 32.1f, 33.2f, 29.4f, 34.9f); lineTo(35.6f, 40.1f); cubicTo(35.2f, 40.5f, 44f, 34f, 44f, 24f)
                cubicTo(44f, 22.8f, 43.9f, 21.6f, 43.6f, 20.5f); close() }, Color(0xFF4285F4))
            drawPath(path { moveTo(24f, 44f); cubicTo(29.2f, 44f, 33.9f, 42f, 37.4f, 38.8f); lineTo(31.2f, 33.6f); cubicTo(29.2f, 35.1f, 26.7f, 36f, 24f, 36f)
                cubicTo(18.8f, 36f, 14.4f, 32.7f, 12.7f, 28f); lineTo(6.3f, 33f); cubicTo(9.6f, 39.6f, 16.3f, 44f, 24f, 44f); close() }, Color(0xFF34A853))
            drawPath(path { moveTo(6.3f, 14.7f); lineTo(12.9f, 19.5f); cubicTo(14.7f, 15.1f, 19f, 12f, 24f, 12f); cubicTo(27.1f, 12f, 29.9f, 13.2f, 32f, 15.1f)
                lineTo(37.7f, 9.4f); cubicTo(34.1f, 6.1f, 29.3f, 4f, 24f, 4f); cubicTo(16.3f, 4f, 9.7f, 8.3f, 6.3f, 14.7f); close() }, Color(0xFFEA4335))
            drawPath(path { moveTo(12.7f, 28f); cubicTo(12.2f, 26.8f, 12f, 25.4f, 12f, 24f); cubicTo(12f, 22.4f, 12.3f, 20.9f, 12.9f, 19.5f); lineTo(6.3f, 14.7f)
                cubicTo(4.8f, 17.5f, 4f, 20.7f, 4f, 24f); cubicTo(4f, 27.2f, 4.8f, 30.3f, 6.3f, 33f); close() }, Color(0xFFFBBC05))
        } else scale(this.size.width / 24f, Offset.Zero) {
            drawPath(Path().apply {
                moveTo(12f, .5f); cubicTo(5.65f, .5f, .5f, 5.65f, .5f, 12f); cubicTo(.5f, 17.08f, 3.79f, 21.39f, 8.36f, 22.91f)
                cubicTo(8.94f, 23.02f, 9.15f, 22.66f, 9.15f, 22.36f); lineTo(9.14f, 20.4f); cubicTo(5.94f, 21.1f, 5.27f, 18.86f, 5.27f, 18.86f)
                cubicTo(4.75f, 17.53f, 3.99f, 17.18f, 3.99f, 17.18f); cubicTo(2.95f, 16.47f, 4.07f, 16.48f, 4.07f, 16.48f)
                cubicTo(5.22f, 16.56f, 5.83f, 17.66f, 5.83f, 17.66f); cubicTo(6.86f, 19.42f, 8.52f, 18.91f, 9.17f, 18.62f)
                cubicTo(9.28f, 17.88f, 9.57f, 17.37f, 9.9f, 17.08f); cubicTo(7.35f, 16.79f, 4.66f, 15.8f, 4.66f, 11.39f)
                cubicTo(4.66f, 10.13f, 5.11f, 9.11f, 5.85f, 8.3f); cubicTo(5.73f, 8.01f, 5.33f, 6.84f, 5.96f, 5.25f)
                cubicTo(5.96f, 5.25f, 6.93f, 4.94f, 9.12f, 6.43f); cubicTo(10.04f, 6.17f, 11.02f, 6.04f, 12f, 6.04f)
                cubicTo(12.98f, 6.04f, 13.96f, 6.17f, 14.88f, 6.43f); cubicTo(17.07f, 4.94f, 18.04f, 5.25f, 18.04f, 5.25f)
                cubicTo(18.67f, 6.84f, 18.27f, 8.01f, 18.15f, 8.3f); cubicTo(18.89f, 9.11f, 19.34f, 10.13f, 19.34f, 11.39f)
                cubicTo(19.34f, 15.81f, 16.65f, 16.79f, 14.09f, 17.07f); cubicTo(14.5f, 17.43f, 14.87f, 18.13f, 14.87f, 19.21f)
                lineTo(14.86f, 22.36f); cubicTo(14.86f, 22.67f, 15.07f, 23.03f, 15.66f, 22.91f); cubicTo(20.22f, 21.39f, 23.5f, 17.08f, 23.5f, 12f)
                cubicTo(23.5f, 5.65f, 18.35f, .5f, 12f, .5f); close()
            }, github)
        }
    }
}

/** "Continue with Google / GitHub" under an "or" divider. */
@Composable fun SocialButtons(busy: String?, onSelect: (String) -> Unit) {
    Column(Modifier.readableWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            Text(tr("MO_OR"), Modifier.padding(horizontal = 12.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
        }
        Spacer(Modifier.height(14.dp))
        for (provider in listOf("google", "github")) {
            OutlinedButton({ onSelect(provider) }, Modifier.fillMaxWidth().height(48.dp), enabled = busy == null, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(containerColor = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface,
                    disabledContainerColor = MaterialTheme.colorScheme.surface, disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                if (busy == provider) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Pink) else SocialLogo(provider)
                Spacer(Modifier.width(10.dp))
                Text(tr(if (provider == "google") "MO_CONTINUE_GOOGLE" else "MO_CONTINUE_GITHUB"), fontSize = 16.sp)
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** A round profile picture: the uploaded one, else the first letter of the name, else a person icon. */
@Composable fun AccountAvatar(name: String?, url: String?, size: androidx.compose.ui.unit.Dp = 48.dp) {
    val letter = name?.trim()?.takeIf(String::isNotEmpty)?.let { String(Character.toChars(it.codePointAt(0))).uppercase() }
    val fallback: @Composable () -> Unit = {
        Box(Modifier.size(size).clip(CircleShape).background(if (letter == null) MaterialTheme.colorScheme.outlineVariant else Pink), contentAlignment = Alignment.Center) {
            if (letter == null) Icon(Icons.Outlined.Person, null, Modifier.size(size * .58f), tint = Color.White)
            else Text(letter, fontSize = (size.value * .42f).sp, fontWeight = FontWeight.SemiBold, color = Color.White)
        }
    }
    if (url.isNullOrBlank()) { fallback(); return }
    var failed by remember(url) { mutableStateOf(false) }
    Box(Modifier.size(size)) {
        if (failed) fallback() else {
            fallback()
            AsyncImage(url, null, Modifier.matchParentSize().clip(CircleShape), contentScale = ContentScale.Crop, onError = { failed = true })
        }
    }
}

/** "3 min ago"-style relative times for sync and device lists. */
fun relativeKey(time: Long, now: Long, synced: Boolean): Pair<String, Any> {
    val minutes = (now - time) / 60_000
    val prefix = if (synced) "MO_SYNCED" else "MO_ACTIVE"
    return when {
        minutes < 1 -> "${prefix}_JUST_NOW" to Unit
        minutes < 60 -> "${prefix}_MINUTES_%i" to minutes.toInt()
        minutes < 24 * 60 -> "${prefix}_HOURS_%i" to (minutes / 60).toInt()
        else -> "${prefix}_ON_%@" to java.text.SimpleDateFormat("yyyy/M/d", java.util.Locale.ROOT).format(java.util.Date(time))
    }
}

@Composable fun relativeText(time: Long, synced: Boolean): String {
    val (key, arg) = relativeKey(time, System.currentTimeMillis(), synced)
    return if (arg == Unit) tr(key) else tr(key, arg)
}

/** A confirmation with a destructive or plain confirm button. */
@Composable fun ConfirmDialog(title: String, message: String?, confirm: String, destructive: Boolean = false, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = message?.let { { Text(it) } },
        confirmButton = { TextButton({ onDismiss(); onConfirm() }) { Text(confirm, color = if (destructive) MaterialTheme.colorScheme.error else Pink) } },
        dismissButton = { TextButton(onDismiss) { Text(tr("CANCEL"), color = Pink) } })
}
