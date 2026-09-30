package com.vidhub.android.download

import android.content.Context
import android.net.Uri
import android.util.Log
import com.vidhub.android.download.storage.DownloadStorage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "FileAssembler"
private const val BUFFER_BYTES = 64 * 1024

// T7 有序拼接器：T5 明文分段按序追加进 T3 Sink，拼成单文件（.ts / .fmp4）。
//
// 与 T10 的契约（本类唯一对外语义）：
//  1) assemble() = 同步排干：把"当前已按序就绪"的分段一直追加到第一个缺口就返回，
//     不阻塞等待。T10 在 T5 每轮 download() 之后（或每完成一段）调用，按返回值推进：
//     Partial → 等缺口段就绪后再调（会话保留，续拼不重复）；Completed → 任务完成，
//     此后不得再调 assemble；Failure → T5 重补后可再调（将从头重启，见 3）。
//  2) 段就绪判据 = workDir/segFileName(i) 明文存在（T5 契约 SegmentDownloader.kt:47
//     "明文存在⟺段完成"）。不收 lambda、不读 state.json：state.json 可缺失/损坏
//     （TaskStateStore.read→null），且"追加即删"后其 completed 状态与磁盘脱节（状态仍
//     completed 但文件已删）；文件存在性是 T5 rename 原子落定的即时真相，且 T10 零耦合。
//  3) AssembleResult 三态：
//       Completed(uri) —— 全部段+init 已写入，Sink.finish() 成功（.dl 改名 / IS_PENDING=0），
//         state.json 与任务目录已清；uri 即 finish() 返回值（API<29 file://，≥29 content://）。
//       Partial(nextIndex, total) —— 段 0..nextIndex-1 已入成品，nextIndex 处缺口；Sink
//         保持打开（不 finish 不 abort），重入续拼不重复已拼部分（nextIndex=已拼段数）。
//       Failure(reason) —— 硬失败（open/init 拉取/读写 IOException）：Sink.abort() 已调用
//         （半成品已弃、会话已清），state.json 与剩余分段保留；重入=新 Sink 从 0 重启，
//         已删的消费段需 T5 重下补齐（判据 2 的直接推论）。非法 URL 等编程错误直接上抛
//         （与 T5 同策略）。
//  4) 取消：T10 用户取消时调 abort(taskId) 释放会话（删 .dl / pending 行），幂等。
//  5) fMP4 = init（EXT-X-MAP）由本类网络拉取且最先写入（T5 不下载 init，evidence
//     task-5 §6.1 移交本类）；TS = 裸追加。单线程顺序追加，无并发 append。
//  6) 磁盘峰值 = 单分段级：每段追加落定即删其明文；state.json 只在 finish 后随目录清理。
//  不做：seekable 索引、TS 时间戳修复、转封装。
@Singleton
class FileAssembler @Inject constructor(
    private val storage: DownloadStorage,
    private val client: OkHttpClient,
    @ApplicationContext private val context: Context,
) {
    // 拼接结果三态（sealed 穷尽，T10 按此分支：等待 / 完成 / 重下）
    sealed interface AssembleResult {
        // finish() 成功：uri=成品地址，任务目录已清理
        data class Completed(val uri: Uri) : AssembleResult
        // 停在 nextIndex 缺口（0..nextIndex-1 已写入）；会话保留，可重入续拼
        data class Partial(val nextIndex: Int, val totalSegments: Int) : AssembleResult
        // 硬失败：已 abort（半成品弃、会话清），state.json+剩余段保留供 T5 补齐
        data class Failure(val reason: String) : AssembleResult
    }

    // 单任务会话：Sink 跨 assemble() 调用持有 + 成品内已写进度。进度只存内存不落盘——
    // T3 open() 每次都截断 .dl / 新建 pending 行（Sink 不跨进程存活），磁盘 marker 在进程
    // 重启后会指向空成品必然错位；新会话从空成品起步（nextIndex=0）才是唯一自洽状态。
    private class Session(
        val sink: DownloadStorage.Sink,
        var nextIndex: Int = 0,
        var initAppended: Boolean = false,
    )

    // 会话表 + 全局锁：单写者顺序追加（锁内全是阻塞 IO、无挂起点，无死锁面）
    private val sessions = mutableMapOf<String, Session>()
    private val lock = Any()

    // 同步排干入口（契约 1-3）：与 T5 download(spec, playlist, ...) 同形，T10 一份 spec 两处复用；
    // workDir 按 taskId 派生（同 SegmentDownloader.kt L66 公式）而非入参——可派生输入不占参数位。
    // IO 线程执行，含 fMP4 init 的网络拉取
    suspend fun assemble(
        spec: TaskSpec,
        playlist: MediaPlaylist,
    ): AssembleResult = withContext(Dispatchers.IO) {
        synchronized(lock) { assembleLocked(spec, playlist) }
    }

    // 取消/放弃（契约 4）：释放会话并 abort 半成品（T3 保证幂等）；无会话=无操作
    fun abort(taskId: String) {
        synchronized(lock) {
            sessions.remove(taskId)?.sink?.abort()
        }
    }

    // 锁内主体：开会话（同 taskId 幂等复用）→ 排干；IOException 降级 Failure 并弃会话
    private fun assembleLocked(
        spec: TaskSpec,
        playlist: MediaPlaylist,
    ): AssembleResult {
        val taskId = spec.taskId // try 外声明：catch 分支也要用（作用域规则）
        return try {
            val session = sessions[taskId]
                ?: Session(storage.open(taskId, spec.outputFileName)).also { sessions[taskId] = it }
            val workDir = File(context.cacheDir, "downloads/$taskId") // 同 T5 目录公式
            val result = drainLocked(playlist, workDir, session)
            // finish 成功后才出表：失败路径仍需会话在表内才能 abort（catch 分支依赖）
            if (result is AssembleResult.Completed) sessions.remove(taskId)
            result
        } catch (e: IOException) {
            // 硬失败：abort 弃半成品（T3 幂等承诺；finish 成功后 abort 为 no-op，伤不到成品）
            sessions.remove(taskId)?.sink?.abort()
            Log.w(TAG, "task=$taskId 拼接失败：${e.message}")
            AssembleResult.Failure("task=$taskId ${e.message}")
        }
    }

    // 排干主体：fMP4 init 先行 → 按序追加就绪段（追加即删）→ 齐则 finish+清理，缺口则 Partial
    private fun drainLocked(
        playlist: MediaPlaylist,
        workDir: File,
        session: Session,
    ): AssembleResult {
        val total = playlist.segments.size
        // fMP4（OutputFormat.FMP4 ⟺ initSegmentUri != null，T2 派生属性保证）：init 必须
        // 先于任何 moof/mdat 写入；initAppended 存会话内 → 重入不重复写 init
        val initUri = playlist.initSegmentUri
        if (initUri != null && !session.initAppended) {
            appendInit(initUri, session.sink)
            session.initAppended = true
        }
        while (session.nextIndex < total) {
            val segFile = File(workDir, segFileName(session.nextIndex))
            if (!segFile.exists()) break // 缺口：只按序推进，绝不跳号（有序保证）
            appendFile(segFile, session.sink)
            session.nextIndex++ // 先记进度（内存态）…
            segFile.delete()    // …再删段：追加即删，磁盘峰值=单分段级（残余由收尾清理兜底）
        }
        if (session.nextIndex < total) {
            // 缺口（契约 3）：不 finish 不 abort，会话留待 T10 重入续拼
            return AssembleResult.Partial(session.nextIndex, total)
        }
        val uri = session.sink.finish() // 原子收尾：.dl 改名成品 / IS_PENDING=0
        cleanupWorkDir(workDir)         // state.json 与任务目录残余一并清理
        return AssembleResult.Completed(uri)
    }

    // InputStream → Sink 分块追加：内存峰值=64KB 级（append 只收 ByteArray，整段 readBytes
    // 会把内存峰值推到分段大小）；copyOf 不依赖"Sink 不持有引用"的隐含假设
    private fun appendStream(input: InputStream, sink: DownloadStorage.Sink) {
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            sink.append(buffer.copyOf(n))
        }
    }

    // 分段明文文件 → Sink（T5 契约：存在即完整段，rename 原子落定）
    private fun appendFile(file: File, sink: DownloadStorage.Sink) =
        FileInputStream(file).use { appendStream(it, sink) }

    // fMP4 init 段（EXT-X-MAP）网络拉取 → 最先写入成品（契约 5：T5 不下载 init）
    private fun appendInit(initUri: String, sink: DownloadStorage.Sink) {
        val call = client.newCall(Request.Builder().url(initUri).build())
        call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("init HTTP ${resp.code}：$initUri")
            val body = resp.body ?: throw IOException("init 空响应体：$initUri")
            body.byteStream().use { appendStream(it, sink) }
        }
    }

    // 收尾清理：任务目录整删（state.json、漏删明文、.dl/.part 残余一并清）；best-effort——
    // 成品已 finish，残留不影响结果（与 T3 abort 同款策略，失败仅留日志级痕迹）
    private fun cleanupWorkDir(workDir: File) {
        workDir.listFiles()?.forEach { f -> f.delete() }
        workDir.delete()
    }
}
