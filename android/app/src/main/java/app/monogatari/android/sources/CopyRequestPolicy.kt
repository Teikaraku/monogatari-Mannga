package app.monogatari.android.sources

import okhttp3.HttpUrl
import java.io.IOException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal class CopyRateLimitFailure(val retryAt: Long, now: Long) : IOException(
    "拷贝漫画请求过于频繁（HTTP 429），请在约 ${(retryAt - now).coerceAtLeast(1000).let { (it + 999) / 1000 }} 秒后重试。代理的共享出口也可能被限流。"
)

/** Retry-After applies to the service, not just the chapter that received it. */
internal class CopyRequestPolicy(private val now: () -> Long, private val pause: (Long) -> Unit) {
    private val deadlines = mutableMapOf<String, Long>()
    private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"

    fun before(url: HttpUrl) {
        while (true) {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
            val deadline = synchronized(deadlines) { deadlines[origin(url)] ?: 0L }
            val wait = deadline - now()
            if (wait > MAX_AUTOMATIC_WAIT) throw CopyRateLimitFailure(deadline, now())
            if (wait <= 0) return
            pause(wait)
        }
    }

    fun limited(url: HttpUrl, retryAfter: String?, serverDate: String?, attempt: Int): CopyRateLimitFailure {
        val current = now()
        val delay = retryDelay(retryAfter, serverDate, current) ?: (2000L shl attempt.coerceAtMost(2))
        val deadline = current + delay.coerceAtMost(Long.MAX_VALUE - current)
        synchronized(deadlines) { deadlines[origin(url)] = maxOf(deadlines[origin(url)] ?: 0L, deadline) }
        return CopyRateLimitFailure(deadline, current)
    }

    companion object {
        const val MAX_AUTOMATIC_WAIT = 5000L
        fun retryDelay(header: String?, serverDate: String?, now: Long): Long? {
            if (header == null) return null
            header.trim().toLongOrNull()?.takeIf { it >= 0 }?.let {
                return (it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000).coerceAtLeast(1000)
            }
            fun date(value: String?) = runCatching { ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
            val deadline = date(header) ?: return null
            return (deadline - (date(serverDate) ?: now)).coerceAtLeast(1000)
        }
    }
}
