package com.junkfood.seal.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Publish the job's state before it can finish or emit progress. */
internal fun CoroutineScope.launchTask(
    publish: (Job) -> Unit,
    block: suspend CoroutineScope.() -> Unit,
): Job {
    val job = launch(start = CoroutineStart.LAZY, block = block)
    publish(job)
    job.start()
    return job
}
