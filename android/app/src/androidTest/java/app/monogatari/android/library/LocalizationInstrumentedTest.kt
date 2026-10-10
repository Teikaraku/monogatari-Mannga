package app.monogatari.android.library

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.monogatari.android.ui.Localization
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class LocalizationInstrumentedTest {
    @Test fun nativeOriginalPlaceholdersPreserveNumbersStringsIndexesAndEscapedPercents() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context = base.createConfigurationContext(Configuration(base.resources.configuration).apply { setLocale(Locale.US) })
        assertEquals("Page 3", Localization.string(context, "Page %i", 3))
        assertEquals("Name Beacon", Localization.string(context, "Name %@", "Beacon"))
        assertEquals("9/2", Localization.string(context, "%2\$i/%1\$i", 2, 9))
        assertEquals("7 Beacon", Localization.string(context, "%2\$i %1\$@", "Beacon", 7))
        assertEquals("007", Localization.string(context, "%03i", 7))
        assertEquals("3 4 5", Localization.string(context, "%lld %ld %lu", 3L, 4L, 5L))
        assertEquals("%@ and %i / 7", Localization.string(context, "%%@ and %%i / %i", 7))
        assertEquals("3.5", Localization.string(context, "%g", 3.5))
        assertEquals("1", Localization.string(context, "%g", 1.0))
        assertEquals("Selected (2/24)", Localization.string(context, "SELECTED_%i_OF_%i", 2, 24))
    }
}
