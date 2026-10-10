package app.monogatari.android.sources

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Calendar
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit

data class SourceLogEntry(val date: Long, val level: String, val message: String) {
    fun formatted(): String = "[${SimpleDateFormat("MM/dd HH:mm:ss.SSS", Locale.US).format(Date(date))}] ${if (level.isEmpty()) "" else "[$level] "}$message"
}

/** Original Logs.logServer is an HTTP POST destination, not an inbound server. */
class SourceLogStore private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("monogatari-logs", Context.MODE_PRIVATE)
    private val directory = File(context.filesDir, "Logs").apply { mkdirs() }
    private val file = File(directory, "current.txt")
    private val data = MutableStateFlow<List<SourceLogEntry>>(readRecent(file))
    val entries: StateFlow<List<SourceLogEntry>> = data
    @Volatile private var streamUrl = prefs.getString("Logs.logServer", null)?.toHttpUrlOrNull()
    private val http = OkHttpClient.Builder().followRedirects(false).connectTimeout(5, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
    fun setStreamUrl(address: String?) {
        val url = address?.takeIf { it.isNotBlank() }?.let { it.toHttpUrlOrNull() ?: error("日志服务器地址必须是有效 HTTP 或 HTTPS 地址") }
        streamUrl = url; prefs.edit().putString("Logs.logServer", address).apply()
    }
    @Synchronized fun add(message: String, level: String = "") {
        val entry = SourceLogEntry(System.currentTimeMillis(), level, message)
        data.value = (data.value + entry).takeLast(5000)
        file.appendText(entry.formatted() + "\n")
        Log.println(when (level) { "ERROR" -> Log.ERROR; "WARNING", "WARN" -> Log.WARN; "DEBUG" -> Log.DEBUG; else -> Log.INFO }, "MonogatariSource", message)
        streamUrl?.let { address -> http.newCall(Request.Builder().url(address).post(entry.formatted().toRequestBody("text/plain; charset=utf-8".toMediaType())).build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { Log.w("MonogatariLogs", "Log streaming failed: ${e.message}") }
            override fun onResponse(call: Call, response: Response) { response.close() }
        }) }
    }
    @Synchronized fun clear() { data.value = emptyList(); file.writeText("") }
    @Synchronized fun export(destination: File = File(directory, "log_${SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())}.txt")): File {
        destination.parentFile?.mkdirs(); if (file.exists()) file.copyTo(destination, overwrite = true) else destination.writeText(""); return destination
    }
    companion object {
        /** The original text format omits the year. Infer it backwards across New Year. */
        internal fun readRecent(file:File,limit:Int=5000):List<SourceLogEntry> {
            if(!file.isFile)return emptyList()
            val pattern=Regex("""^\[(\d{2}/\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\] (.*)$""")
            val rows=ArrayDeque<Pair<String,String>>()
            file.bufferedReader().useLines { lines -> lines.forEach { line ->
                val match=pattern.matchEntire(line)
                if(match!=null) { rows.addLast(match.groupValues[1] to match.groupValues[2]);while(rows.size>limit)rows.removeFirst() }
                else if(rows.isNotEmpty()) { val last=rows.removeLast();rows.addLast(last.first to (last.second+"\n"+line)) }
            } }
            val format=SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS",Locale.US).apply { isLenient=false }
            var latest=file.lastModified().takeIf { it>0 }?:System.currentTimeMillis()
            var year=Calendar.getInstance().apply { timeInMillis=latest }.get(Calendar.YEAR)
            val reversed=rows.toList().asReversed().mapNotNull { (stamp,raw) ->
                var parsed:Long?=null;var candidateYear=year
                repeat(5) { if(parsed==null) {
                    val date=runCatching { format.parse("$candidateYear/$stamp")?.time }.getOrNull()
                    if(date!=null && date<=latest+24*60*60*1000L) { parsed=date;year=candidateYear } else candidateYear--
                } }
                parsed?.let { date -> latest=date
                    val level=Regex("""^\[(INFO|DEBUG|WARN|WARNING|ERROR)\] (.*)$""",RegexOption.DOT_MATCHES_ALL).matchEntire(raw)
                    SourceLogEntry(date,level?.groupValues?.get(1).orEmpty(),level?.groupValues?.get(2)?:raw)
                }
            }
            return reversed.asReversed()
        }
        @Volatile private var instance: SourceLogStore? = null
        fun get(context: Context): SourceLogStore = instance?.takeIf { it.context===context.applicationContext } ?: synchronized(this) { instance?.takeIf { it.context===context.applicationContext } ?: SourceLogStore(context.applicationContext).also { instance = it } }
    }
}
