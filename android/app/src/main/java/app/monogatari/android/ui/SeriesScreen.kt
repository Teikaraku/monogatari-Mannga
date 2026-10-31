package app.monogatari.android.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.monogatari.android.MonogatariViewModel
import app.monogatari.android.R

/** Another app or site of the 物语 family; [url] is null for this app, which is shown without an action. */
internal data class RelatedProject(val name: String, val summary: String, @param:DrawableRes val icon: Int, val url: String? = null, val action: String = "DOWNLOAD")

internal val relatedProjects = listOf(
    RelatedProject("物语漫画", "PROJECT_MANGA_SUMMARY", R.drawable.monogatari_icon),
    RelatedProject("物语番剧", "PROJECT_ANIME_SUMMARY", R.drawable.project_anime, "https://github.com/Teikaraku/monogatari-anime/releases/latest"),
    RelatedProject("物语轻小说", "PROJECT_NOVEL_SUMMARY", R.drawable.project_novel, "https://github.com/Teikaraku/monogatari-novel/releases/latest"),
    RelatedProject("物语GAL", "PROJECT_GAL_SUMMARY", R.drawable.project_gal, "https://monogatari.top", "VISIT"))

@Composable fun SeriesScreen(vm: MonogatariViewModel) {
    Column {
        PageTitle(tr("MONOGATARI_SERIES"), vm::back, contentWidth = ReadableWidth)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant).verticalScroll(rememberScrollState())) {
            SettingGroup(tr("RELATED_PROJECTS")) {
                relatedProjects.forEach { project ->
                    // External links open in the system browser through the shared ACTION_VIEW route.
                    ProjectRow(project) { url -> vm.navigate("external", url) }
                }
            }
        }
    }
}

@Composable private fun ProjectRow(project: RelatedProject, open: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(project.icon), null, Modifier.size(48.dp).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(project.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.height(2.dp))
            Text(tr(project.summary), fontSize = 14.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        val url = project.url
        if (url == null) Text(tr("CURRENT_APP"), Modifier.padding(horizontal = 12.dp), fontSize = 14.sp, fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            val action = tr(project.action)
            FilledTonalButton({ open(url) }, Modifier.heightIn(min = 36.dp).semantics { role = Role.Button; contentDescription = action + project.name },
                shape = RoundedCornerShape(50), contentPadding = PaddingValues(horizontal = 16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = Pink.copy(alpha = .12f), contentColor = Pink)) { Text(action) }
        }
    }
    HorizontalDivider(Modifier.padding(start = 16.dp + 48.dp + 14.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
}
