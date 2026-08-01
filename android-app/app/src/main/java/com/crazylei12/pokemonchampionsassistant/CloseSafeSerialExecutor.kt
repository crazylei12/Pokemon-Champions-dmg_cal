package com.crazylei12.pokemonchampionsassistant

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class CloseSafeSerialExecutor(
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(),
) {
    private val lock = Any()
    private var closed = false

    fun submit(task: () -> Unit): Boolean = submitCancellable(task) != null

    fun submitCancellable(task: () -> Unit): Future<*>? = synchronized(lock) {
        if (closed) return@synchronized null
        executor.submit(task)
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
