package ru.pivoskan.app.data

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import java.util.concurrent.Executor

class LatestTaskRunnerTest {
    private class Queue : Executor {
        val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    @Test fun burstKeepsOnlyNewestPendingCalculation() {
        val worker = Queue(); val main = Queue()
        val runner = LatestTaskRunner(worker, main)
        val computed = mutableListOf<Int>(); val shown = mutableListOf<Int>()
        repeat(100) { n -> runner.submit({ computed.add(n); n }, { shown.add(it) }) }
        assertEquals(1, worker.tasks.size)
        worker.drain(); main.drain()
        assertEquals(listOf(99), computed); assertEquals(listOf(99), shown)
    }
    @Test fun resultQueuedBeforeNewRequestCannotOverwriteNewerScreen() {
        val worker = Queue(); val main = Queue(); val shown = mutableListOf<Int>()
        val runner = LatestTaskRunner(worker, main)
        runner.submit({ 1 }, { shown.add(it) }); worker.drain()
        runner.submit({ 2 }, { shown.add(it) }); worker.drain(); main.drain()
        assertEquals(listOf(2), shown)
    }
    @Test fun closingScreenDropsPendingWorkAndDelivery() {
        val worker = Queue(); val main = Queue(); val shown = mutableListOf<Int>()
        val runner = LatestTaskRunner(worker, main)
        runner.submit({ 1 }, { shown.add(it) }); worker.drain()
        runner.submit({ 2 }, { shown.add(it) }); runner.close()
        worker.drain(); main.drain()
        assertTrue(shown.isEmpty())
    }
    @Test fun progressUpdatesCanDeliverCompletedSnapshotWhileNewerWorkWaits() {
        val worker = Queue(); val main = Queue(); val shown = mutableListOf<Int>()
        val runner = LatestTaskRunner(worker, main)
        runner.submit({ 1 }, { shown.add(it) }); worker.drain()
        runner.submit({ 2 }, { shown.add(it) }, supersedeRunning = false)
        main.drain()
        assertEquals(listOf(1), shown)
        worker.drain(); main.drain()
        assertEquals(listOf(1, 2), shown)
    }
}
