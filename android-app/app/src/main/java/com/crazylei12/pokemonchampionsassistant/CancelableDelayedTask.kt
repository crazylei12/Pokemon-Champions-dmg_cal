package com.crazylei12.pokemonchampionsassistant

import java.util.concurrent.atomic.AtomicBoolean

internal class CancelableDelayedTask(
    private val action: () -> Unit,
) : Runnable {
    private val pending = AtomicBoolean(true)

    override fun run() {
        if (pending.compareAndSet(true, false)) action()
    }

    fun cancel(): Boolean = pending.getAndSet(false)
}
