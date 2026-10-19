package app.monogatari.android.sources

/** Some providers retain loaded profiles until process exit; retry briefly, then recover next launch. */
internal class DeferredProfileCleanup(
    private val schedule: (Long, () -> Unit) -> Unit,
    private val delete: (String) -> Unit,
    private val reportFailure: (String, Exception) -> Unit = { _, _ -> }
) {
    private class Pending(var failures: Int = 0)
    private val pending = linkedMapOf<String, Pending>()

    fun enqueue(name: String, immediately: Boolean = false) {
        if (pending.containsKey(name)) return
        val entry = Pending()
        pending[name] = entry
        if (immediately) attempt(name, entry) else schedule(100) { attempt(name, entry) }
    }

    fun cancel(name: String) { pending.remove(name) }

    private fun attempt(name: String, entry: Pending) {
        if (pending[name] !== entry) return
        try {
            delete(name)
            pending.remove(name)
        } catch (error: Exception) {
            entry.failures++
            if (entry.failures == 1 || entry.failures and (entry.failures - 1) == 0) reportFailure(name, error)
            // The prefix remains on disk for cold-start reclamation. Do not spin for
            // the lifetime of a provider which cannot unload profiles in this process.
            if (entry.failures < 4) {
                val delay = 250L * (1L shl (entry.failures - 1))
                schedule(delay) { attempt(name, entry) }
            }
        }
    }
}
