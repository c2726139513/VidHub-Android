package com.vidhub.android.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File

// T10 任务模型与注册表存取（从 DownloadRepository 拆出，保持编排层 <250 pure-LOC）
private const val TAG = "DownloadTasks"
// 成品标题净化上限（防超长文件名；storage.open 另拒 /、blank、.、..）
private val ILLEGAL_NAME_CHARS = charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|')
private const val MAX_TITLE_CHARS = 60

// 任务六态（String 常量仿 SegmentStatus：Moshi codegen 对 String 零风险）
object DownloadTaskStatus {
    const val PENDING = "pending"
    const val RUNNING = "running"
    const val PAUSED = "paused"
    const val FAILED = "failed"
    const val COMPLETED = "completed"
    const val CANCELLED = "cancelled"
}

// 注册表条目：持久化于 filesDir/downloads.json；progress 随存但只作展示，
// 断点恢复真相在 T5 的 state.json（明文存在 ⟺ 段完成）
@JsonClass(generateAdapter = true)
data class DownloadTask(
    val taskId: String,
    val serverId: String?,
    val title: String,
    val m3u8Url: String,
    val status: String = DownloadTaskStatus.PENDING,
    val progressCompleted: Int = 0,
    val progressTotal: Int = 0,
    val error: String? = null,
    val outputUri: String? = null,
    val createdAt: Long = 0L,
)

// 注册表 JSON 根对象（Moshi codegen 包装列表，app 既有模式）
@JsonClass(generateAdapter = true)
data class TaskRegistry(val tasks: List<DownloadTask> = emptyList())

// 队列派生快照：前台通知与设置页共用（activeCount=pending+running，percent=当前 running 任务）
data class QueueState(val activeCount: Int, val progressPercent: Int)

internal fun DownloadTask.isPendingOrRunning(): Boolean =
    status == DownloadTaskStatus.PENDING || status == DownloadTaskStatus.RUNNING

// 装载注册表：损坏/缺失 → 空表（Log.w 留痕）；进程重启后 running/pending → paused
//（不做开机/自动恢复——T12 由用户显式 resume）
internal fun loadTaskRegistry(file: File, moshi: Moshi): List<DownloadTask> {
    if (!file.exists()) return emptyList()
    return try {
        moshi.adapter(TaskRegistry::class.java).fromJson(file.readText())?.tasks.orEmpty().map {
            if (it.isPendingOrRunning()) {
                it.copy(status = DownloadTaskStatus.PAUSED)
            } else it
        }
    } catch (e: Exception) {
        Log.w(TAG, "downloads.json 损坏按空表重建：${e.message}")
        emptyList()
    }
}

// 落盘注册表：tmp + rename 原子替换；仅状态变更调用（进度只在内存）；
// 失败仅留痕不抛（调用方多在主线程，磁盘异常不许崩 UI）
internal fun persistTaskRegistry(file: File, moshi: Moshi, tasks: List<DownloadTask>) {
    try {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(moshi.adapter(TaskRegistry::class.java).toJson(TaskRegistry(tasks)))
        if (!tmp.renameTo(file)) Log.w(TAG, "downloads.json 落盘失败")
    } catch (e: Exception) {
        Log.w(TAG, "downloads.json 写入失败：${e.message}")
    }
}

// 删任务工作目录 cacheDir/downloads/<taskId>/（seg-*、state.json 残余）；best-effort 幂等
internal fun deleteTaskWorkDir(context: Context, taskId: String) {
    try {
        val dir = File(context.cacheDir, "downloads/$taskId")
        dir.listFiles()?.forEach { f -> f.delete() }
        dir.delete()
    } catch (e: Exception) {
        Log.w(TAG, "删任务目录失败 $taskId：${e.message}")
    }
}

// 删成品（T3 删除语义）：content:// 走 ContentResolver，file:// 走 File；best-effort 幂等
internal fun deleteOutputUri(context: Context, uriString: String) {
    try {
        val uri = Uri.parse(uriString)
        when (uri.scheme) {
            "content" -> context.contentResolver.delete(uri, null, null)
            "file" -> uri.path?.let { File(it).delete() }
            else -> Log.w(TAG, "未知成品 scheme：$uriString")
        }
    } catch (e: Exception) {
        Log.w(TAG, "删成品失败 $uriString：${e.message}")
    }
}

// 成品名 = 净化标题 + url 短哈希（防同名互覆）+ 扩展名（T2 派生 .ts/.fmp4）
internal fun outputFileName(task: DownloadTask, playlist: MediaPlaylist): String {
    val cleaned = task.title
        .map { c -> if (c in ILLEGAL_NAME_CHARS) '_' else c }
        .joinToString("")
        .trim()
        .take(MAX_TITLE_CHARS)
        .ifBlank { "download" }
    val hash = task.m3u8Url.hashCode().toUInt().toString(16).padStart(8, '0').take(6)
    return "$cleaned-$hash${playlist.outputFormat.extension}"
}
