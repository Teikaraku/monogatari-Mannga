package app.monogatari.android.ui

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "zh-rCN")
class RelatedProjectsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun projectsMatchTheSeriesListInOrder() {
        assertEquals(listOf(
            Triple("物语漫画", "物语系列开源漫画阅读项目", null),
            Triple("物语番剧", "物语系列开源番剧观看项目", "https://github.com/Teikaraku/monogatari-anime/releases/latest"),
            Triple("物语轻小说", "物语系列开源轻小说阅读项目", "https://github.com/Teikaraku/monogatari-novel/releases/latest"),
            Triple("物语GAL", "物语系列面向中文玩家的Galgame资料库", "https://monogatari.top"),
        ), relatedProjects.map { Triple(it.name, Localization.string(context, it.summary), it.url) })
    }

    @Test fun onlyMangaIsTheCurrentAppAndOthersHaveTheirAction() {
        assertEquals(listOf("物语漫画"), relatedProjects.filter { it.url == null }.map { it.name })
        assertEquals(listOf("当前应用", "下载", "下载", "访问"),
            relatedProjects.map { Localization.string(context, if (it.url == null) "CURRENT_APP" else it.action) })
    }

    @Test fun settingsSectionStringsResolve() {
        assertEquals(listOf("项目", "物语系列", "物语系列相关项目总览", "相关项目"),
            listOf("PROJECTS", "MONOGATARI_SERIES", "MONOGATARI_SERIES_SUBTITLE", "RELATED_PROJECTS").map { Localization.string(context, it) })
    }
}
