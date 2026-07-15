package com.crazylei12.pokemonchampionsassistant

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CancelableDelayedTaskTest {
    @Test
    fun cancelBeforeDispatchPreventsLateCapture() {
        var executions = 0
        val request = CancelableDelayedTask { executions += 1 }

        assertTrue(request.cancel())
        request.run()

        assertEquals(0, executions)
        assertFalse(request.cancel())
    }

    @Test
    fun dispatchRunsAtMostOnceWhenCalledMoreThanOnce() {
        val executions = AtomicInteger()
        val request = CancelableDelayedTask { executions.incrementAndGet() }
        val ready = CountDownLatch(8)
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(8)
        try {
            repeat(8) {
                pool.execute {
                    ready.countDown()
                    start.await(5, TimeUnit.SECONDS)
                    request.run()
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
        } finally {
            pool.shutdown()
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        }

        assertEquals(1, executions.get())
        assertFalse(request.cancel())
    }
}
