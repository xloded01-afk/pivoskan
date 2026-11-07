package ru.pivoskan.app.data

import java.util.concurrent.Executor

/** One running calculation and one replaceable pending request, irrespective of input rate. */
class LatestTaskRunner(private val worker: Executor, private val delivery: Executor) {
    private var generation = 0L
    private var pending: (() -> Unit)? = null
    private var running = false
    private var closed = false

    @Synchronized fun <T> submit(calculate: () -> T, show: (T) -> Unit, supersedeRunning: Boolean = true) {
        if (closed) return
        if (supersedeRunning) generation++
        val token = generation
        pending = {
            val result = calculate()
            delivery.execute {
                synchronized(this) { if (!closed && generation == token) show(result) }
            }
        }
        if (running) return
        running = true
        worker.execute {
            while (true) {
                val action = synchronized(this) {
                    val next = pending
                    pending = null
                    if (next == null) running = false
                    next
                } ?: break
                action()
            }
        }
    }
    @Synchronized fun close() { closed = true; generation++; pending = null }
}
