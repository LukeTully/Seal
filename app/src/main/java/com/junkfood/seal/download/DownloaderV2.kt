package com.junkfood.seal.download

import android.app.PendingIntent
import android.content.Context
import androidx.annotation.MainThread
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.junkfood.seal.App
import com.junkfood.seal.R
import com.junkfood.seal.download.Task.DownloadState
import com.junkfood.seal.download.Task.DownloadState.Canceled
import com.junkfood.seal.download.Task.DownloadState.Completed
import com.junkfood.seal.download.Task.DownloadState.Error
import com.junkfood.seal.download.Task.DownloadState.FetchingInfo
import com.junkfood.seal.download.Task.DownloadState.Idle
import com.junkfood.seal.download.Task.DownloadState.ReadyWithInfo
import com.junkfood.seal.download.Task.DownloadState.Running
import com.junkfood.seal.download.Task.RestartableAction.Download
import com.junkfood.seal.download.Task.RestartableAction.FetchInfo
import com.junkfood.seal.download.Task.TypeInfo
import com.junkfood.seal.util.DownloadUtil
import com.junkfood.seal.util.FileUtil
import com.junkfood.seal.util.NotificationUtil
import com.junkfood.seal.util.PreferenceUtil
import com.junkfood.seal.util.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import kotlin.collections.component1
import kotlin.collections.component2
import kotlin.collections.set
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent

private const val MAX_CONCURRENCY = 3

@MainThread
interface DownloaderV2 {
    fun getTaskStateMap(): SnapshotStateMap<Task, Task.State>

    fun cancel(task: Task): Boolean

    fun cancel(taskId: String): Boolean {
        return getTaskStateMap().keys.find { it.id == taskId }?.let { cancel(it) } ?: false
    }

    fun restart(task: Task)

    /** Enqueue a [Task] with an empty [Task.State] */
    fun enqueue(task: Task)

    fun enqueue(task: Task, state: Task.State)

    fun enqueue(taskWithState: TaskFactory.TaskWithState) {
        val (task, state) = taskWithState
        enqueue(task, state)
    }

    fun remove(task: Task): Boolean
}

internal object FakeDownloaderV2 : DownloaderV2 {
    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return mutableStateMapOf()
    }

    override fun cancel(task: Task): Boolean {
        return false
    }

    override fun restart(task: Task) {}

    override fun enqueue(task: Task) {}

    override fun enqueue(task: Task, state: Task.State) {}

    override fun remove(task: Task): Boolean {
        return true
    }
}

/**
 * TODO:
 *     - Notification
 *     - Custom commands
 *     - States for ViewModels
 */
@OptIn(FlowPreview::class)
class DownloaderV2Impl(private val appContext: Context) : DownloaderV2, KoinComponent {
    // Queue transitions and progress updates share one dispatcher. Native work stays on IO.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val taskStateMap = mutableStateMapOf<Task, Task.State>()
    private val snapshotFlow = snapshotFlow { taskStateMap.toMap() }

    init {
        scope.launch {
            snapshotFlow
                .onEach { doYourWork() }
                .map { taskStateMap.countRunning() }
                .distinctUntilChanged()
                .collect { if (it > 0) App.startService() else App.stopService() }
        }

        scope.launch(Dispatchers.IO) {
            // don't write before we read
            enqueueFromBackup()

            snapshotFlow
                .map { it.filter { it.value.downloadState !is Completed } }
                .distinctUntilChanged()
                .collect { PreferenceUtil.encodeTaskListBackup(it) }
        }
    }

    private suspend fun enqueueFromBackup() {
        val taskList =
            PreferenceUtil.decodeTaskListBackup()
                .filter { it.value.downloadState !is Completed }
                .mapValues { (_, state) ->
                    val preState = state.downloadState
                    val downloadState =
                        when (preState) {
                            is FetchingInfo,
                            Idle -> {
                                Canceled(action = FetchInfo)
                            }
                            is Running -> {
                                Canceled(action = Download, progress = preState.progress)
                            }

                            ReadyWithInfo -> {
                                Canceled(action = Download, progress = null)
                            }
                            else -> {
                                preState
                            }
                        }
                    state.copy(downloadState = downloadState)
                }
        withContext(Dispatchers.Main.immediate) {
            taskList.forEach { (task, state) ->
                // A share can enqueue a task while its backup is being read.
                if (!taskStateMap.containsKey(task)) enqueue(task, state)
            }
        }
    }

    private fun Map<Task, Task.State>.countRunning(): Int = count { (_, state) ->
        state.downloadState is Running || state.downloadState is FetchingInfo
    }

    override fun getTaskStateMap(): SnapshotStateMap<Task, Task.State> {
        return taskStateMap
    }

    override fun enqueue(task: Task) {
        taskStateMap +=
            task to Task.State(Idle, null, Task.ViewState(url = task.url, title = task.url))
    }

    override fun enqueue(task: Task, state: Task.State) {
        taskStateMap += task to state
    }

    /**
     * Noted the caller is responsible for stopping the [task] before removing it
     *
     * @return true if the task was removed
     */
    override fun remove(task: Task): Boolean {
        if (taskStateMap.contains(task)) {
            taskStateMap.remove(task)
            return true
        }
        return false
    }

    override fun cancel(task: Task): Boolean = task.cancelImpl()

    override fun restart(task: Task) {
        task.restartImpl()
    }

    private var Task.state: Task.State
        get() = taskStateMap[this]!!
        set(value) {
            taskStateMap[this] = value
        }

    private var Task.downloadState: DownloadState
        get() = state.downloadState
        set(value) {
            val prevState = state
            taskStateMap[this] = prevState.copy(downloadState = value)
        }

    private val Task.info: VideoInfo?
        get() = state.videoInfo

    private val Task.viewState: Task.ViewState
        get() = state.viewState

    private val Task.notificationId: Int
        get() = id.hashCode()

    /** Processes pending tasks, prioritizing downloads. */
    private fun doYourWork() {
        if (taskStateMap.countRunning() >= MAX_CONCURRENCY) return

        taskStateMap.entries
            .sortedBy { (_, state) -> state.downloadState }
            .firstOrNull { (_, state) ->
                state.downloadState == ReadyWithInfo || state.downloadState == Idle
            }
            ?.let { (task, state) ->
                when (state.downloadState) {
                    Idle -> task.prepare()
                    ReadyWithInfo -> task.download()
                    else -> {
                        throw IllegalStateException()
                    }
                }
            }
    }

    private fun Task.prepare() {
        check(downloadState == Idle)
        if (type is TypeInfo.CustomCommand) {
            execute()
        } else {
            fetchInfo()
        }
    }

    private fun Task.fetchInfo() {
        check(downloadState == Idle)
        val task = this
        val taskInfo = task.type
        val playlistIndex = if (taskInfo is TypeInfo.Playlist) taskInfo.index else null
        val title = viewState.title
        scope.launchTask(publish = { downloadState = FetchingInfo(job = it, taskId = id) }) {
            withContext(Dispatchers.IO) {
                    DownloadUtil.fetchVideoInfoFromUrl(
                        url = url,
                        playlistIndex = playlistIndex,
                        preferences = preferences,
                        taskKey = id,
                    )
                }
                .onSuccess {
                    state =
                        state.copy(
                            videoInfo = it,
                            viewState = Task.ViewState.fromVideoInfo(it),
                            downloadState = ReadyWithInfo,
                        )
                }
                .onFailure { throwable ->
                    if (throwable is YoutubeDL.CanceledException) {
                        return@onFailure
                    }
                    task.downloadState = Error(throwable = throwable, action = FetchInfo)
                    withContext(Dispatchers.IO) {
                        NotificationUtil.notifyError(
                            title = title,
                            textId = R.string.download_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                }
        }
    }

    private fun Task.download() {
        check(downloadState == ReadyWithInfo && info != null)
        if (type is TypeInfo.CustomCommand) {
            execute()
            return
        }
        val playlist = type as? TypeInfo.Playlist
        scope.launchTask(publish = { downloadState = Running(job = it, taskId = id) }) {
            val running = downloadState as Running
            val videoInfo = info
            val title = viewState.title
            withContext(Dispatchers.IO) {
                    DownloadUtil.downloadVideo(
                        videoInfo = videoInfo,
                        playlistUrl = if (playlist != null) url else "",
                        playlistItem = playlist?.index ?: 0,
                        taskId = id,
                        downloadPreferences = preferences,
                        progressCallback = callback@{ progressPercentage, _, text ->
                                if (!running.job.isActive) return@callback
                                val progress = progressPercentage / 100f
                                scope.launch {
                                    when (
                                        val preState = taskStateMap[this@download]?.downloadState
                                    ) {
                                        is Running -> {
                                            if (preState.job !== running.job) return@launch
                                            downloadState =
                                                preState.copy(
                                                    progress = progress,
                                                    progressText = text,
                                                )
                                        }
                                        else -> {}
                                    }
                                }
                                NotificationUtil.notifyProgress(
                                    notificationId = notificationId,
                                    progress = progressPercentage.toInt(),
                                    text = text,
                                    title = title,
                                    taskId = id,
                                )
                            },
                    )
                }
                .onSuccess { pathList ->
                    downloadState = Completed(pathList.firstOrNull())

                    withContext(Dispatchers.IO) {
                        val text =
                            appContext.getString(
                                if (pathList.isEmpty()) R.string.status_completed
                                else R.string.download_finish_notification
                            )
                        FileUtil.createIntentForOpeningFile(pathList.firstOrNull()).run {
                            NotificationUtil.finishNotification(
                                notificationId,
                                title = title,
                                text = text,
                                intent =
                                    if (this != null)
                                        PendingIntent.getActivity(
                                            appContext,
                                            0,
                                            this,
                                            PendingIntent.FLAG_IMMUTABLE,
                                        )
                                    else null,
                            )
                        }
                    }
                }
                .onFailure { throwable ->
                    if (throwable is YoutubeDL.CanceledException) {
                        return@onFailure
                    }
                    downloadState = Error(throwable = throwable, action = Download)
                    withContext(Dispatchers.IO) {
                        NotificationUtil.notifyError(
                            title = title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                }
        }
    }

    private fun Task.cancelImpl(): Boolean {
        when (val preState = downloadState) {
            is DownloadState.Cancelable -> {
                val res = YoutubeDL.destroyProcessById(preState.taskId)
                if (res) {
                    preState.job.cancel()
                    val progress = if (preState is Running) preState.progress else null
                    NotificationUtil.cancelNotification(notificationId)
                    downloadState =
                        DownloadState.Canceled(action = preState.action, progress = progress)
                }
                return res
            }
            Idle -> {
                downloadState = DownloadState.Canceled(action = FetchInfo)
            }
            ReadyWithInfo -> {
                downloadState = DownloadState.Canceled(action = Download)
            }

            else -> {
                return false
            }
        }
        return true
    }

    private fun Task.restartImpl() {
        when (val preState = downloadState) {
            is DownloadState.Restartable -> {
                downloadState =
                    when (preState.action) {
                        Download -> ReadyWithInfo
                        FetchInfo -> Idle
                    }
            }
            else -> {
                throw IllegalStateException()
            }
        }
    }

    /**
     * Execute a custom command task
     *
     * @see Task.TypeInfo.CustomCommand
     */
    private fun Task.execute() {
        check(downloadState == Idle)
        check(type is TypeInfo.CustomCommand)
        val template = type.template
        val title = viewState.title
        scope.launchTask(publish = { downloadState = Running(job = it, taskId = id) }) {
            val running = downloadState as Running
            withContext(Dispatchers.IO) {
                    DownloadUtil.executeCustomCommandTask(
                        url,
                        id,
                        template,
                        preferences,
                    ) callback@{ progressPercentage, _, text ->
                        if (!running.job.isActive) return@callback
                        val progress = progressPercentage / 100f
                        scope.launch {
                            when (val preState = taskStateMap[this@execute]?.downloadState) {
                                is Running -> {
                                    if (preState.job !== running.job) return@launch
                                    downloadState =
                                        preState.copy(progress = progress, progressText = text)
                                }
                                else -> {}
                            }
                        }
                        NotificationUtil.makeNotificationForCustomCommand(
                            notificationId = notificationId,
                            taskId = id,
                            progress = progressPercentage.toInt(),
                            templateName = template.name,
                            taskUrl = url,
                            text = text,
                        )
                    }
                }
                .onFailure { throwable ->
                    if (throwable is YoutubeDL.CanceledException) {
                        return@onFailure
                    }
                    downloadState = Error(throwable = throwable, action = Download)
                    withContext(Dispatchers.IO) {
                        NotificationUtil.notifyError(
                            title = title,
                            textId = R.string.fetch_info_error_msg,
                            notificationId = notificationId,
                            report = throwable.stackTraceToString(),
                        )
                    }
                }
                .onSuccess {
                    downloadState = Completed(null)

                    val text = appContext.getString(R.string.status_completed)

                    withContext(Dispatchers.IO) {
                        NotificationUtil.finishNotification(
                            notificationId = notificationId,
                            title = title,
                            text = text,
                            intent = null,
                        )
                    }
                }
        }
    }
}
