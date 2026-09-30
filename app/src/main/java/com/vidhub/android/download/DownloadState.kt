package com.vidhub.android.download

import android.util.Log
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import java.io.File
import java.io.IOException

// 分段状态四态（String 常量而非 enum：无编译器环境降险，Moshi codegen 对 String 零风险；T10 按此判定重试）
object SegmentStatus {
    const val PENDING = "pending"
    const val DOWNLOADING = "downloading"
    const val COMPLETED = "completed"
    const val FAILED = "failed"
}

// state.json 固定文件名：cacheDir/downloads/<taskId>/state.json（T7 拼接完成后随任务目录清理）
const val STATE_FILE_NAME = "state.json"

// 分段明文文件名 seg-<i4>.ts（4 位补零，如 seg-0003.ts；fMP4 也用 .ts 后缀——计划定名，T7 按此读取）
fun segFileName(index: Int): String = "seg-" + index.toString().padStart(4, '0') + ".ts"

// 分段密文临时文件 = 明文名 + ".dl"（半截态；解密/改名完成后删除）
fun segDlFileName(index: Int): String = segFileName(index) + ".dl"

// download() 输入元数据：state.json 中除分段表外的固定字段（T10 从任务记录填充）
data class TaskSpec(
    val taskId: String,
    val m3u8Url: String,
    val baseUrl: String,
    val outputFileName: String,
)

// download() 聚合结果：failedIndexes 空=全部成功；非空=部分失败（完成段保留，T10 重入同 spec 只补缺/失败段）
data class SegmentDownloadResult(
    val totalSegments: Int,
    val completedCount: Int,
    val failedIndexes: List<Int>,
) {
    val isSuccess: Boolean
        get() = failedIndexes.isEmpty()
}

// state.json 单段记录：status=SegmentStatus 四态；sizeBytes=密文总长（响应头可定时写入，未知为 null）
@JsonClass(generateAdapter = true)
data class SegmentStateJson(
    val index: Int,
    val status: String = SegmentStatus.PENDING,
    val bytesWritten: Long = 0L,
    val sizeBytes: Long? = null,
)

// state.json 根对象（Moshi codegen = app 既有模式，见 model/ 与 dto/）：任务元数据 + 分段表；
// IV/key 三字段抄自 T2 的 EXT-X-KEY 结果（keyUri 已绝对化），供断点续传与问题排查
@JsonClass(generateAdapter = true)
data class TaskStateJson(
    val taskId: String,
    val m3u8Url: String,
    val baseUrl: String,
    val outputFileName: String,
    val mediaSequence: Long,
    val ivHex: String? = null,
    val ivFromSequence: Boolean = false,
    val keyUri: String? = null,
    val totalSegments: Int,
    val segments: List<SegmentStateJson>,
    val updatedAt: Long,
)

// state.json 存取：内存镜像 + synchronized 原子落盘（.tmp → renameTo，防进程被杀留半截 JSON）；
// 一次 download() 一个实例，并发分段的一切读写都经本类（唯一写入口 update）
class TaskStateStore(
    private val file: File,
    private val moshi: Moshi,
    initial: TaskStateJson,
) {
    private val adapter = moshi.adapter(TaskStateJson::class.java)
    private val lock = Any()
    private var state: TaskStateJson = initial

    companion object {
        private const val TAG = "TaskStateStore"

        // 读 state.json；缺失/损坏返回 null（调用方按新任务重建；磁盘上已下的分段文件仍可被重入判定识别）
        fun read(file: File, moshi: Moshi): TaskStateJson? {
            if (!file.exists()) return null
            val adapter = moshi.adapter(TaskStateJson::class.java)
            return try {
                adapter.fromJson(file.readText())
            } catch (e: Exception) {
                // 边界恢复：半截/损坏 JSON → 当新任务重建；不静默——Log.w 留痕
                Log.w(TAG, "state.json 损坏按新任务重建：${e.message}")
                null
            }
        }

        // 首次运行的全 pending 状态（IV/key 三字段抄 playlist.key——T2 已绝对化 keyUri）
        fun fresh(spec: TaskSpec, playlist: MediaPlaylist): TaskStateJson = TaskStateJson(
            taskId = spec.taskId,
            m3u8Url = spec.m3u8Url,
            baseUrl = spec.baseUrl,
            outputFileName = spec.outputFileName,
            mediaSequence = playlist.mediaSequence,
            ivHex = playlist.key?.ivHex,
            ivFromSequence = playlist.key?.ivFromSequence == true,
            keyUri = playlist.key?.uri,
            totalSegments = playlist.segments.size,
            segments = List(playlist.segments.size) { i -> SegmentStateJson(index = i) },
            updatedAt = System.currentTimeMillis(),
        )
    }

    // 第 index 段快照（锁内读；下标==index 由 fresh/read 保证）
    fun segment(index: Int): SegmentStateJson = synchronized(lock) { state.segments[index] }

    // 任务快照（起始进度统计等整表读取）
    fun snapshot(): TaskStateJson = synchronized(lock) { state }

    // 部分更新第 index 段并立即落盘（transform 在锁内执行）——并发分段的唯一写入口
    fun update(index: Int, transform: (SegmentStateJson) -> SegmentStateJson) {
        synchronized(lock) {
            val segments = state.segments.toMutableList()
            segments[index] = transform(segments[index])
            state = state.copy(segments = segments, updatedAt = System.currentTimeMillis())
            persistLocked()
        }
    }

    // tmp + rename 原子替换（须持 lock 调用）；失败上抛 IOException——磁盘错误不静默
    private fun persistLocked() {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(adapter.toJson(state))
        if (!tmp.renameTo(file)) throw IOException("state.json 落盘失败：${file.path}")
    }
}
