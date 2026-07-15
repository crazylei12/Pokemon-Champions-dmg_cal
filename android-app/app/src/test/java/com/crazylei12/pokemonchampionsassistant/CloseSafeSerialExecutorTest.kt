package com.crazylei12.pokemonchampionsassistant

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloseSafeSerialExecutorTest {
    @Test
    fun cleanupRunsOnlyAfterAcceptedWorkFinishes() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cleaned = CountDownLatch(1)
        val events = Collections.synchronizedList(mutableListOf<String>())
        val queue = CloseSafeSerialExecutor()

        assertTrue(queue.submit {
            events += "work-start"
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            events += "work-end"
        })
        assertTrue(started.await(5, TimeUnit.SECONDS))
        queue.closeAfterPending {
            events += "cleanup"
            cleaned.countDown()
        }
        assertFalse(queue.submit { events += "late-work" })
        release.countDown()

        assertTrue(cleaned.await(5, TimeUnit.SECONDS))
        assertEquals(listOf("work-start", "work-end", "cleanup"), events)
    }
}
