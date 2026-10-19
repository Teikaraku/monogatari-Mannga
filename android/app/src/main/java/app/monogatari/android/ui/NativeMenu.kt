package app.monogatari.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** One row of a [NativeMenu]: an action, or a submenu that opens inside the same popup. */
sealed interface MenuEntry

/** [checked] adds the leading checkmark column of a picker; null for plain actions. */
class MenuAction(
    val title: String,
    val icon: ImageVector? = null,
    val checked: Boolean? = null,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
) : MenuEntry

/** A section title such as the iOS "2 Chapters" caption above the mark actions. */
class MenuHeader(val title: String, val maxLines: Int = Int.MAX_VALUE) : MenuEntry

/** [subtitle] shows the current choice under the title, like the iOS sort menu. */
class MenuSubmenu(
    val title: String,
    val icon: ImageVector? = null,
    val subtitle: String = "",
    val sections: List<List<MenuEntry>>,
) : MenuEntry

/**
 * The pull-down menus of the iOS app: grouped sections separated by a thick band, the item's symbol on the
 * trailing edge, a leading checkmark for the current choice, red destructive items, and submenus that replace
 * the menu's content with a back row instead of opening a second popup.
 */
@Composable internal fun NativeMenu(expanded: Boolean, onDismissRequest: () -> Unit, sections: List<List<MenuEntry>>) {
    val caller = LocalContext.current; val configuration = LocalConfiguration.current
    var path by remember { mutableStateOf(listOf<MenuSubmenu>()) }
    LaunchedEffect(expanded) { if (!expanded) path = emptyList() }
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    DropdownMenu(expanded, onDismissRequest, Modifier.widthIn(min = 240.dp, max = 300.dp), shape = RoundedCornerShape(14.dp),
        containerColor = if (dark) Color(0xFF2C2C2E) else Color.White, shadowElevation = 12.dp) {
        CompositionLocalProvider(LocalContext provides caller, LocalConfiguration provides configuration) {
            val current = path.lastOrNull()
            if (current != null) {
                Row(Modifier.fillMaxWidth().clickable { path = path.dropLast(1) }.heightIn(min = 44.dp).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(current.title, Modifier.padding(start = 4.dp), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                MenuSectionBreak(dark)
            }
            val visible = (current?.sections ?: sections).filter { it.isNotEmpty() }
            visible.forEachIndexed { index, section ->
                if (index > 0) MenuSectionBreak(dark)
                val picker = section.any { it is MenuAction && it.checked != null }
                section.forEachIndexed { row, entry ->
                    if (row > 0 && section[row - 1] !is MenuHeader) HorizontalDivider(thickness = .5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f))
                    when (entry) {
                        is MenuHeader -> Text(entry.title, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
                            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = entry.maxLines, overflow = TextOverflow.Ellipsis)
                        is MenuAction -> MenuActionRow(entry, picker) { onDismissRequest(); entry.onClick() }
                        is MenuSubmenu -> MenuSubmenuRow(entry) { path = path + entry }
                    }
                }
            }
        }
    }
}

@Composable private fun MenuSectionBreak(dark: Boolean) {
    Box(Modifier.fillMaxWidth().height(8.dp).background(if (dark) Color(0xFF1C1C1E) else Color(0xFFEEEEF0)))
}

@Composable private fun MenuActionRow(action: MenuAction, picker: Boolean, onClick: () -> Unit) {
    val color = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = .38f)
        action.destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().clickable(enabled = action.enabled, onClick = onClick).heightIn(min = 44.dp).padding(start = if (picker) 10.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (picker) Box(Modifier.width(26.dp), contentAlignment = Alignment.CenterStart) {
            if (action.checked == true) Icon(Icons.Outlined.Check, null, Modifier.size(18.dp), tint = color)
        }
        Text(action.title, Modifier.weight(1f).padding(vertical = 10.dp), color = color, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (action.icon != null) Icon(action.icon, null, Modifier.padding(start = 12.dp).size(20.dp), tint = color)
    }
}

@Composable private fun MenuSubmenuRow(menu: MenuSubmenu, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 44.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(menu.title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (menu.subtitle.isNotBlank()) Text(menu.subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (menu.icon != null) Icon(menu.icon, null, Modifier.padding(start = 12.dp).size(20.dp), tint = MaterialTheme.colorScheme.onSurface)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, Modifier.padding(start = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A form row with an iOS menu-style picker: the label on the left, the current choice in the accent colour on the
 * right, and the options in a [NativeMenu] instead of a row of chips.
 */
@Composable internal fun <T> MenuPicker(title: String, options: List<Pair<T, String>>, selected: T, modifier: Modifier = Modifier, enabled: Boolean = true,
    onSelect: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clickable(enabled = enabled) { open = true }.heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).padding(end = 12.dp), fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f))
        Box {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(options.firstOrNull { it.first == selected }?.second.orEmpty(), Modifier.widthIn(max = 200.dp), fontSize = 15.sp,
                    color = if (enabled) Pink else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Icon(Icons.Outlined.UnfoldMore, null, Modifier.padding(start = 2.dp).size(18.dp), tint = if (enabled) Pink else MaterialTheme.colorScheme.onSurface.copy(alpha = .38f))
            }
            NativeMenu(open, { open = false }, listOf(options.map { (value, label) -> MenuAction(label, checked = value == selected) { onSelect(value) } }))
        }
    }
}
