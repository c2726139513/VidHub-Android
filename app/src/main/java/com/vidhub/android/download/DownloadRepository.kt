package com.vidhub.android.download

import android.content.Context
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

// 注册表固定文件名：filesDir/downloads.json（进程重启的任务真相源）
private const val REGISTRY_FILE_NAME = "downloads.json"

// T10 任务注册表单例（模型/存取在 DownloadTasks.kt，本类只做编排）：
//  - 统一数据出口：一切状态变更经本类 synchronized(lock)，唯一持久化入口 persistLocked
//  - 串行编排 parse(M3u8Fetcher.resolve) → download(SegmentDownloader 全局 Semaphore5)
//    → assemble(FileAssembler)；单 worker 逐任务 join，pause/cancel 只杀子任务不死队列
//  - 队列无 lost-wakeup：取任务与判空同锁，退出前同锁置空 queueJob
//  - 状态集 pending/running/paused/failed/completed/cancelled（DownloadTaskStatus）
@Singleton
class DownloadRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val moshi: Moshi,
    private val fetcher: M3u8Fetcher,
    private val downloader: SegmentDownloader,
    private val assembler: FileAssembler,
) {
    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registryFile = File(context.filesDir, REGISTRY_FILE_NAME)

    private val _tasks = MutableStateFlow(loadTaskRegistry(registryFile, moshi))
    val tasks: StateFlow<List<DownloadTask>> = _tasks

    // 队列派生态：StateFlow 值相等去重（同进度不触发通知重建）
    val queueState: StateFlow<QueueState> = _tasks.map { list ->
        val active = list.count { it.isPendingOrRunning() }
        val running = list.firstOrNull { it.status == DownloadTaskStatus.RUNNING }
        val percent = if (running != null && running.progressTotal > 0) {
            running.progressCompleted * 100 / running.progressTotal
        } else 0
        QueueState(active, percent)
    }.stateIn(scope, SharingStarted.Eagerly, QueueState(0, 0))

    // 串行 worker 句柄（kick 幂等判据）+ 当前任务子任务句柄（pause/cancel 定点取消）
    private var queueJob: Job? = null
    private var currentTaskId: String? = null
    private var currentJob: Job? = null

    private val activeStatuses =
        setOf(DownloadTaskStatus.PENDING, DownloadTaskStatus.RUNNING, DownloadTaskStatus.PAUSED)

    // ---- T11 入口 ----

    // 入队并唤醒串行队列；同 url 且 pending/running → 重复入队返回 false
    fun enqueue(serverId: String?, title: String, m3u8Url: String): Boolean {
        synchronized(lock) {
            val duplicated = _tasks.value.any { it.m3u8Url == m3u8Url && it.isPendingOrRunning() }
            if (duplicated) return false
            _tasks.value = _tasks.value + DownloadTask(
                taskId = UUID.randomUUID().toString(),
                serverId = serverId,
                title = title,
                m3u8Url = m3u8Url,
                createdAt = System.currentTimeMillis(),
            )
            persistLocked()
        }
        kick()
        return true
    }

    // ---- T12 任务管理 + 暂停/恢复 ----

    // 重试：failed/cancelled → pending（清 error）并重新排队
    fun retry(taskId: String) {
        val moved = casTransform(
            taskId,
            true,
            { it.status == DownloadTaskStatus.FAILED || it.status == DownloadTaskStatus.CANCELLED },
            { it.copy(status = DownloadTaskStatus.PENDING, error = null) },
        )
        if (moved) kick()
    }

    // 暂停（应用内）：pending/running → paused，定点取消子任务。
    // 不清 workDir 不 abort 会话——resume 后 T5 断点续传、T7 会话续拼
    fun pause(taskId: String) {
        val moved = casTransform(
            taskId,
            true,
            { it.isPendingOrRunning() },
            { it.copy(status = DownloadTaskStatus.PAUSED) },
        )
        if (moved) synchronized(lock) {
            if (currentTaskId == taskId) currentJob?.cancel()
        }
    }

    // 恢复：paused → pending 并重新排队
    fun resume(taskId: String) {
        val moved = casTransform(
            taskId,
            true,
            { it.status == DownloadTaskStatus.PAUSED },
            { it.copy(status = DownloadTaskStatus.PENDING) },
        )
        if (moved) kick()
    }

    // 取消：活动态 → cancelled；子任务停稳后释放 T7 会话 + 删任务目录（成品未产生）
    fun cancel(taskId: String) {
        var outputUri: String? = null
        var job: Job? = null
        synchronized(lock) {
            val index = indexOfLocked(taskId)
            if (index < 0) return
            val task = _tasks.value[index]
            if (task.status !in activeStatuses) return
            outputUri = task.outputUri
            replaceLocked(index, task.copy(status = DownloadTaskStatus.CANCELLED))
            persistLocked()
            if (currentTaskId == taskId) job = currentJob?.also { it.cancel() }
        }
        scope.launch { cleanupTask(job, taskId, outputUri) }
    }

    // 删除（T3 成品删除语义）：撤注册表条目 +（在跑则停）+ 删任务目录 + 删成品
    fun delete(taskId: String) {
        var outputUri: String? = null
        var job: Job? = null
        synchronized(lock) {
            val index = indexOfLocked(taskId)
            if (index < 0) return
            outputUri = _tasks.value[index].outputUri
            if (currentTaskId == taskId) job = currentJob?.also { it.cancel() }
            _tasks.value = _tasks.value.filterNot { it.taskId == taskId }
            persistLocked()
        }
        scope.launch { cleanupTask(job, taskId, outputUri) }
    }

    // 唤醒串行队列（幂等）：已有活 worker 即返回；锁内启动与队列判空同锁，无 lost-wakeup
    fun kick() {
        synchronized(lock) {
            if (queueJob?.isActive == true) return
            queueJob = scope.launch { runQueue() }
        }
    }

    // ---- 串行编排 ----

    // 单 worker 循环：锁内取首个 PENDING + 建子任务 + start（取任务与判空原子）；
    // join 完清句柄再取下一个。pause/cancel 只 cancel 子任务，本循环继续服务后续任务
    private suspend fun runQueue() {
        while (true) {
            val job = synchronized(lock) {
                val next = _tasks.value.firstOrNull { it.status == DownloadTaskStatus.PENDING }
                if (next == null) {
                    queueJob = null // 与判空同锁原子：此后任何 kick() 必开新 worker
                    return
                }
                val created = scope.launch(start = CoroutineStart.LAZY) { process(next.taskId) }
                currentTaskId = next.taskId
                currentJob = created
                created.start()
                created
            }
            job.join()
            synchronized(lock) { currentTaskId = null; currentJob = null }
        }
    }

    // 单任务执行体：PENDING→RUNNING CAS 后 parse → download → assemble；
    // 业务失败 → failed（可重试）；取消异常原样上抛（状态已由 pause/cancel 写入，不覆盖）
    private suspend fun process(taskId: String) {
        if (!markRunning(taskId)) return // 已被 pause/cancel 抢先改写
        val task = _tasks.value.firstOrNull { it.taskId == taskId } ?: return
        try {
            val playlist = when (val parsed = fetcher.resolve(task.m3u8Url)) {
                is ParseResult.Failure -> {
                    markFailed(taskId, parsed.error.message)
                    return
                }
                is ParseResult.Success -> parsed.playlist as? MediaPlaylist
            }
            if (playlist == null) {
                markFailed(taskId, "播放列表不含可下载分段")
                return
            }
            val spec = TaskSpec(
                taskId = taskId,
                m3u8Url = task.m3u8Url,
                baseUrl = task.m3u8Url, // 分段 URI 在 T2 已按播放列表 URL 绝对化
                outputFileName = outputFileName(task, playlist),
            )
            val downloaded = downloader.download(spec, playlist) { done, total ->
                updateProgress(taskId, done, total)
            }
            if (!downloaded.isSuccess) {
                markFailed(
                    taskId,
                    "分段下载失败 ${downloaded.failedIndexes.size}/${downloaded.totalSegments}",
                )
                return
            }
            // 下载全成后单次拼接：T7 追加即删使磁盘峰值≈1×成品，无需逐段拼接循环
            when (val assembled = assembler.assemble(spec, playlist)) {
                is FileAssembler.AssembleResult.Completed -> {
                    val uri = assembled.uri.toString()
                    // 竞态：任务已被取消/删除却跑完 → 成品成孤儿 → 删（幂等）
                    if (!markCompleted(taskId, uri)) deleteOutputUri(context, uri)
                }
                is FileAssembler.AssembleResult.Partial ->
                    markFailed(taskId, "拼接缺口 ${assembled.nextIndex}/${assembled.totalSegments}")
                is FileAssembler.AssembleResult.Failure ->
                    markFailed(taskId, assembled.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            markFailed(taskId, e.message ?: e.javaClass.simpleName)
        }
    }

    // 子任务停稳 → 释放 T7 会话（幂等）→ 删任务目录 → 删成品（顺序保证不与在跑任务互踩）
    private suspend fun cleanupTask(job: Job?, taskId: String, outputUri: String?) {
        job?.join()
        assembler.abort(taskId)
        deleteTaskWorkDir(context, taskId)
        if (outputUri != null) deleteOutputUri(context, outputUri)
    }

    // ---- 状态跃迁（锁内 CAS：from 不命中即不覆盖 cancel/pause 的改写） ----

    // 指定态守卫跃迁；persist=状态变更才落盘（进度走 false 只进内存）
    private fun casTransform(
        taskId: String,
        persist: Boolean,
        predicate: (DownloadTask) -> Boolean,
        mutate: (DownloadTask) -> DownloadTask,
    ): Boolean = synchronized(lock) {
        val index = indexOfLocked(taskId)
        if (index < 0) return false
        val task = _tasks.value[index]
        if (!predicate(task)) return false
        replaceLocked(index, mutate(task))
        if (persist) persistLocked()
        true
    }

    private fun markRunning(taskId: String) = casTransform(
        taskId,
        true,
        { it.status == DownloadTaskStatus.PENDING },
        { it.copy(status = DownloadTaskStatus.RUNNING) },
    )

    private fun markFailed(taskId: String, reason: String) = casTransform(
        taskId,
        true,
        { it.status == DownloadTaskStatus.RUNNING },
        { it.copy(status = DownloadTaskStatus.FAILED, error = reason) },
    )

    // →completed：running/paused/pending 均收下（成品已落定，暂停晚于完成无意义）；
    // 已取消/条目已删 → false，调用方删孤儿成品
    private fun markCompleted(taskId: String, uri: String) = casTransform(
        taskId,
        true,
        { it.status != DownloadTaskStatus.CANCELLED },
        { it.copy(status = DownloadTaskStatus.COMPLETED, outputUri = uri, error = null) },
    )

    // 进度推进：仅内存不落盘（重启由 T5 state.json 重入恢复）
    private fun updateProgress(taskId: String, completed: Int, total: Int) = casTransform(
        taskId,
        false,
        { true },
        { it.copy(progressCompleted = completed, progressTotal = total) },
    )

    // ---- 小工具（调用方须持 lock） ----

    private fun indexOfLocked(taskId: String): Int =
        _tasks.value.indexOfFirst { it.taskId == taskId }

    // 整条替换（是否落盘由各调用方决定）
    private fun replaceLocked(index: Int, task: DownloadTask) {
        val list = _tasks.value.toMutableList()
        list[index] = task
        _tasks.value = list
    }

    // 注册表落盘（状态变更专用；进度 persist=false 不会走到这里）
    private fun persistLocked() = persistTaskRegistry(registryFile, moshi, _tasks.value)
}
