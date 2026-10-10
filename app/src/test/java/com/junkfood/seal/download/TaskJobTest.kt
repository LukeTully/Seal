package com.junkfood.seal.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskJobTest {
    @Test
    fun immediateCompletionDoesNotOverwriteTerminalStateWithRunning() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val transitions = mutableListOf<String>()
            val job =
                scope.launchTask(publish = { transitions += "running" }) {
                    transitions += "completed"
                }

            assertEquals(listOf("running", "completed"), transitions)
            assertTrue(job.isCompleted)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun firstCallbackCanSeeItsPublishedJob() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var publishedJob: Job? = null
            var observedPublishedJob: Job? = null
            var observedRunningJob: Job? = null
            val job =
                scope.launchTask(publish = { publishedJob = it }) {
                    observedPublishedJob = publishedJob
                    observedRunningJob = coroutineContext[Job]
                }
            assertSame(job, observedPublishedJob)
            assertSame(job, observedRunningJob)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cancellationDuringPublicationPreventsNativeWorkFromStarting() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var executed = false
            val job = scope.launchTask(publish = { it.cancel() }) { executed = true }

            assertTrue(job.isCancelled)
            assertFalse(executed)
        } finally {
            scope.cancel()
        }
    }
}
