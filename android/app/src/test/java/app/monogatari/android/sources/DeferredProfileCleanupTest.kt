package app.monogatari.android.sources

import org.junit.Assert.*
import org.junit.Test

class DeferredProfileCleanupTest {
    @Test fun retriesProviderReleaseWithoutThrowingOrLosingTheProfile() {
        val scheduled = ArrayDeque<Pair<Long, () -> Unit>>()
        var attempts = 0
        val deleted = mutableListOf<String>()
        val cleanup = DeferredProfileCleanup(
            { delay, action -> scheduled.addLast(delay to action) },
            { name -> attempts++; if (attempts < 3) throw IllegalStateException("Cannot delete in-use profile"); deleted.add(name) }
        )
        cleanup.enqueue("temporary")
        assertEquals(100L, scheduled.first().first); scheduled.removeFirst().second()
        assertEquals(250L, scheduled.first().first); scheduled.removeFirst().second()
        assertEquals(500L, scheduled.first().first); scheduled.removeFirst().second()
        assertEquals(listOf("temporary"), deleted)
        assertTrue(scheduled.isEmpty())

        var unavailableAttempts = 0
        val unavailable = DeferredProfileCleanup(
            { delay, action -> scheduled.addLast(delay to action) },
            { unavailableAttempts++; throw IllegalStateException("Loaded until next process") }
        )
        unavailable.enqueue("retained")
        repeat(4) { scheduled.removeFirst().second() }
        assertEquals(4, unavailableAttempts)
        assertTrue("A permanently loaded profile must stop retrying until the next launch", scheduled.isEmpty())
        unavailable.enqueue("retained")
        assertTrue(scheduled.isEmpty())
    }

    @Test fun cancelledCallbacksCannotDeleteAnOpenedOrRequeuedProfile() {
        val scheduled = ArrayDeque<() -> Unit>()
        val deleted = mutableListOf<String>()
        val cleanup = DeferredProfileCleanup({ _, action -> scheduled.addLast(action) }, { name -> deleted.add(name) })
        cleanup.enqueue("temporary")
        cleanup.cancel("temporary")
        cleanup.enqueue("temporary")
        scheduled.removeFirst()()
        assertTrue(deleted.isEmpty())
        scheduled.removeFirst()()
        assertEquals(listOf("temporary"), deleted)
    }

    @Test fun duplicateEnqueueAndBusyProfilesDoNotBlockOtherReclamation() {
        val scheduled = ArrayDeque<() -> Unit>()
        val deleted = mutableListOf<String>()
        val cleanup = DeferredProfileCleanup(
            { _, action -> scheduled.addLast(action) },
            { name -> if (name == "busy") throw IllegalStateException("in use") else deleted.add(name) }
        )
        cleanup.enqueue("busy"); cleanup.enqueue("busy"); cleanup.enqueue("released")
        assertEquals(2, scheduled.size)
        scheduled.removeFirst()(); scheduled.removeFirst()()
        assertEquals(listOf("released"), deleted)
        assertEquals(1, scheduled.size)
        cleanup.cancel("busy"); scheduled.removeFirst()()
        assertTrue(scheduled.isEmpty())
    }
}
