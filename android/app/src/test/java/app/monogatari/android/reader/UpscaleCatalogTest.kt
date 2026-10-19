package app.monogatari.android.reader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], shadows=[app.monogatari.android.backup.PosixAtomicFileShadow::class])
class UpscaleCatalogTest {
    private val context get()=ApplicationProvider.getApplicationContext<Context>()
    private fun row(file:String="models/sample.mlpackage",version:Int=2)=JSONObject().put("file",file).put("type","image").put("version",version)
        .put("name","Native model").put("info","Original info").put("tags",JSONArray().put("photo")).put("config",JSONObject().put("inputName","pixels").put("scale",5))
        .put("future",JSONObject().put("keep",true))
    @Test fun refreshDownloadsRealSourceAndExportsCompleteMetadataWithoutInstallingIt() = runBlocking {
        MockWebServer().use { server ->server.start();val base=server.url("/models.json").toString();val catalog=UpscaleModelCatalog(context,OkHttpClient(),base)
            server.enqueue(MockResponse().setBody(JSONObject().put("models",JSONArray().put(row())).toString()))
            val remote=catalog.refresh().single();assertEquals(server.url("/models/sample.mlpackage.zip").toString(),remote.downloadUrl)
            assertEquals(2,remote.version);assertEquals("photo",remote.tags.single());assertEquals("Original info",remote.info)
            assertTrue(JSONObject(remote.metadata).getJSONObject("future").getBoolean("keep"));assertEquals("pixels",JSONObject(remote.metadata).getJSONObject("config").getString("inputName"))
            assertEquals(remote,catalog.cached().single());assertNull(catalog.downloaded(remote));assertTrue(remote.needsUpdate(emptyList()))
            val bytes="real source bytes, not an installed graph".toByteArray();server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
            var received=0L;val source=catalog.download(remote) { count,_ ->received=count };assertArrayEquals(bytes,source.readBytes());assertEquals(bytes.size.toLong(),received)
            assertNotNull(catalog.downloaded(remote));assertTrue(remote.needsUpdate(emptyList()))
            val out=ByteArrayOutputStream();catalog.export(remote,out)
            val entries=linkedMapOf<String,ByteArray>();ZipInputStream(out.toByteArray().inputStream()).use { zip ->while(true) { val entry=zip.nextEntry ?: break;entries[entry.name]=zip.readBytes() } }
            assertEquals(setOf("source.mlpackage.zip","model.json"),entries.keys);assertArrayEquals(bytes,entries.getValue("source.mlpackage.zip"))
            assertEquals(JSONObject(remote.metadata).toString(),JSONObject(entries.getValue("model.json").toString(Charsets.UTF_8)).toString())
            val request=server.takeRequest();assertEquals("/models.json",request.path);assertEquals("Monogatari/0.9",request.getHeader("User-Agent"));assertEquals("/models/sample.mlpackage.zip",server.takeRequest().path)
        }
    }
    @Test fun invalidRefreshLeavesPreviouslyValidCachedCatalogIntact() = runBlocking {
        MockWebServer().use { server ->server.start();val catalog=UpscaleModelCatalog(context,OkHttpClient(),server.url("/models.json").toString())
            server.enqueue(MockResponse().setBody(JSONObject().put("models",JSONArray().put(row())).toString()));catalog.refresh()
            server.enqueue(MockResponse().setBody(JSONObject().put("models",JSONArray().put(row("file:///outside.mlmodel"))).toString()))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { catalog.refresh() } }
            assertEquals("models/sample.mlpackage",catalog.cached().single().originalFile)
        }
    }
    @Test fun failedSourceChecksumNeverPromotesACompletedDownload() = runBlocking {
        MockWebServer().use { server ->server.start();val base=server.url("/models.json").toString();val catalog=UpscaleModelCatalog(context,OkHttpClient(),base)
            val remote=UpscaleModelCatalog.parse(JSONObject().put("models",JSONArray().put(row("wrong-checksum.mlmodel").put("sha256","0".repeat(64)))),base).single()
            server.enqueue(MockResponse().setBody("different bytes"))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { catalog.download(remote) } };assertNull(catalog.downloaded(remote))
            assertTrue(java.io.File(context.cacheDir,"model-sources").listFiles().orEmpty().none { it.name.endsWith(".writing") })
        }
    }
    @Test fun catalogVersionsMatchConvertedUuidFilesAndOnlyActualInstallations() {
        val remote=UpscaleModelCatalog.parse(JSONObject().put("models",JSONArray().put(row())),"https://example.test/models.json").single()
        fun model(version:Int,installed:Boolean=true)=UpscaleModel("custom-uuid.onnx","Converted",3,0,5,0,0,"",false,installed,version=version,catalogFile="sample.mlpackage")
        assertTrue(remote.needsUpdate(listOf(model(1))));assertFalse(remote.needsUpdate(listOf(model(2))));assertFalse(remote.needsUpdate(listOf(model(3))))
        assertTrue(remote.needsUpdate(listOf(model(2,false))))
        val bundled=UpscaleModel("sample.onnx","Bundled",3,0,5,0,0,"",true,true,version=2)
        assertFalse(remote.needsUpdate(listOf(bundled)))
    }
    @Test fun originalSupportedTypesAreFilteredAndUnknownCatalogMetadataIsRetained() {
        val model=row("../native.mlmodel");val other=JSONObject().put("file","other.mlmodel").put("type","opaque-custom")
        val parsed=UpscaleModelCatalog.parse(JSONObject().put("models",JSONArray().put(model).put(other)),"https://example.test/list/models.json")
        assertEquals(1,parsed.size);assertEquals("https://example.test/native.mlmodel",parsed.single().downloadUrl)
        assertTrue(JSONObject(parsed.single().metadata).getJSONObject("future").getBoolean("keep"))
    }
}
