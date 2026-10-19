@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package app.monogatari.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Animated close for the sheet that encloses the caller; it falls back to an immediate no-op outside a sheet. */
internal val LocalSheetClose = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Pickers, filters and action lists share this sheet. Like the other popup wrappers it keeps the caller's
 * localized context, because the sheet is hosted in a separate platform window.
 */
@Composable internal fun OptionSheet(
    title: String,
    onDismiss: () -> Unit,
    subtitle: String = "",
    scrollable: Boolean = true,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    actions: @Composable RowScope.() -> Unit = {},
    footer: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val caller = LocalContext.current; val configuration = LocalConfiguration.current
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val currentDismiss by rememberUpdatedState(onDismiss)
    val close: () -> Unit = remember(state, scope) { { scope.launch { state.hide() }.invokeOnCompletion { currentDismiss() } } }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, containerColor = containerColor,
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 4.dp).size(width = 36.dp, height = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant)) }) {
        CompositionLocalProvider(LocalContext provides caller, LocalConfiguration provides configuration, LocalSheetClose provides close) {
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                    Text(title, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 2.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                actions()
                if (footer == null) Box(Modifier.padding(start = 4.dp).size(30.dp).clip(CircleShape).background(sheetGroupColor()).clickable(onClick = close),
                    contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Close, pt("Close", "关闭"), Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(bottom = if (footer == null) 20.dp else 4.dp), content = content)
            if (footer != null) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), content = footer)
        }
    }
}

@Composable internal fun sheetGroupColor(): Color =
    if (MaterialTheme.colorScheme.surface.luminance() < .5f) MaterialTheme.colorScheme.surfaceVariant else GroupGray

/** A titled block inside a sheet. */
@Composable internal fun SheetSection(title: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            trailing?.invoke(this)
        }
        content()
    }
}

/** Chips that wrap onto as many lines as they need, so every option stays visible without sideways scrolling. */
@Composable internal fun SheetChips(modifier: Modifier = Modifier, content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** Selected chips use the accent tint; excluded chips (three-state filters) use the error tint and a cross. */
@Composable internal fun SheetChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
    excluded: Boolean = false, enabled: Boolean = true, trailingIcon: ImageVector? = null, checkmark: Boolean = true) {
    val accent = if (excluded) MaterialTheme.colorScheme.error else Pink
    val active = selected || excluded
    Surface(onClick, modifier.defaultMinSize(minWidth = 56.dp, minHeight = 36.dp), enabled = enabled, shape = CircleShape,
        color = if (active) accent.copy(alpha = .1f) else sheetGroupColor(),
        border = if (active) BorderStroke(1.dp, accent.copy(alpha = .6f)) else null) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (active && checkmark && trailingIcon == null) { Icon(if (excluded) Icons.Outlined.Close else Icons.Outlined.Check, null, Modifier.size(15.dp), tint = accent); Spacer(Modifier.width(4.dp)) }
            Text(label, fontSize = 14.sp, fontWeight = if (active) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f); active -> accent; else -> MaterialTheme.colorScheme.onSurface })
            if (trailingIcon != null) { Spacer(Modifier.width(3.dp)); Icon(trailingIcon, null, Modifier.size(16.dp), tint = if (active) accent else MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Rows grouped on a rounded card, separated by hairlines. */
@Composable internal fun SheetGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp)).background(sheetGroupColor()), content = content)
}

/** One choice of a single or multiple selection. */
@Composable internal fun SheetOption(title: String, selected: Boolean, onClick: () -> Unit, subtitle: String = "", multiple: Boolean = false,
    divider: Boolean = true, enabled: Boolean = true, fontFamily: FontFamily? = null) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, role = if (multiple) Role.Checkbox else Role.RadioButton, onClick = onClick)
        .heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontFamily = fontFamily, fontWeight = if (selected && !multiple) FontWeight.Medium else FontWeight.Normal,
                color = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f); selected && !multiple -> Pink; else -> MaterialTheme.colorScheme.onSurface })
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 16.sp)
        }
        if (multiple) Icon(if (selected) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked, null, Modifier.size(22.dp),
            tint = if (selected) Pink else MaterialTheme.colorScheme.outline)
        else if (selected) Icon(Icons.Outlined.Check, null, Modifier.size(20.dp), tint = Pink)
    }
    if (divider) HorizontalDivider(Modifier.padding(start = 16.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

/** One command of an action list. */
@Composable internal fun SheetAction(title: String, icon: ImageVector, onClick: () -> Unit, subtitle: String = "", destructive: Boolean = false,
    enabled: Boolean = true, divider: Boolean = true) {
    val tint = when { !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f); destructive -> MaterialTheme.colorScheme.error; else -> Pink }
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 50.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(22.dp), tint = tint)
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Text(title, fontSize = 16.sp, color = if (destructive && enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
            if (subtitle.isNotBlank()) Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (divider) HorizontalDivider(Modifier.padding(start = 52.dp), thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}

internal class SheetActionItem(val title: String, val icon: ImageVector, val destructive: Boolean = false, val subtitle: String = "",
    val enabled: Boolean = true, val onClick: () -> Unit)
@Composable internal fun SheetActions(items: List<SheetActionItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    SheetGroup(modifier) { items.forEachIndexed { index, item ->
        SheetAction(item.title, item.icon, item.onClick, item.subtitle, item.destructive, item.enabled, divider = index < items.lastIndex)
    } }
}

@Composable internal fun RowScope.SheetSecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(onClick, Modifier.weight(1f).height(46.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface)) { Text(text, fontSize = 15.sp) }
}
@Composable internal fun RowScope.SheetPrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick, Modifier.weight(1f).height(46.dp), enabled = enabled, shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Pink, contentColor = Color.White)) { Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium) }
}
