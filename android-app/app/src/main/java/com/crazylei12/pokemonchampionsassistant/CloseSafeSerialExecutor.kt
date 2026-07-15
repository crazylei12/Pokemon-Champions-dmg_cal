package com.crazylei12.pokemonchampionsassistant

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

internal class CloseSafeSerialExecutor(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val lock = Any()
    private var closed = false

    fun submit(task: () -> Unit): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        executor.execute(task)
        true
    }

    fun closeAfterPending(cleanup: () -> Unit) {
        synchronized(lock) {
            if (closed) return
            closed = true
            executor.execute(cleanup)
            executor.shutdown()
        }
    }
}
