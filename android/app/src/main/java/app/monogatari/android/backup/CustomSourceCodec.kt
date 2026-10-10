package app.monogatari.android.backup

import app.monogatari.android.data.SourceConfig
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** The original CustomSourceConfig varint format, preserving the original source key. */
object CustomSourceCodec {
    fun decode(bytes: ByteArray): SourceConfig {
        require(bytes.isNotEmpty()) { "源配置为空" }; var position = 1
        fun string(): String {
            var length = 0L; var shift = 0
            while (true) {
                require(position < bytes.size && shift <= 56) { "源配置 varint 无效" }
                val b = bytes[position++].toInt() and 255; length = length or ((b and 127).toLong() shl shift)
                if (b and 128 == 0) break; shift += 7
            }
            require(length <= 1024 * 1024 && length <= bytes.size - position) { "源配置字符串越界" }
            val result = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes, position, length.toInt())).toString(); position += length.toInt(); return result
        }
        return when (bytes[0].toInt()) {
            0 -> SourceConfig("demo", "Demo", "demo")
            2 -> SourceConfig.local
            1, 3, 4 -> { val key = string(); val name = string(); val server = string(); require(position == bytes.size) { "源配置包含尾随数据" }; SourceConfig(key, name, when (bytes[0].toInt()) { 1 -> "komga"; 3 -> "kavita"; else -> "suwayomi" }, server) }
            else -> error("未知的内置源配置类型")
        }
    }
    fun encode(source: SourceConfig): ByteArray {
        val output = ByteArrayOutputStream(); val type = when (source.type) { "demo" -> 0; "komga" -> 1; "local" -> 2; "kavita" -> 3; "suwayomi" -> 4; else -> error("该源没有内置配置") }
        output.write(type)
        if (type in setOf(1, 3, 4)) listOf(source.id, source.name, source.baseUrl).forEach { value -> val bytes = value.toByteArray(); var length = bytes.size.toLong(); while (length >= 128) { output.write(((length and 127) or 128).toInt()); length = length ushr 7 }; output.write(length.toInt()); output.write(bytes) }
        return output.toByteArray()
    }
}
