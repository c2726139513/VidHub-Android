package com.vidhub.android.download

import android.content.Context
import android.util.Log
import com.squareup.moshi.Moshi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SegmentDownloader"
private const val MAX_ATTEMPTS = 3
private const val CHECKPOINT_BYTES = 1024L * 1024L
private const val BUFFER_BYTES = 64 * 1024
private const val AES_KEY_BYTES = 16
private val BACKOFF_MS = longArrayOf(1000L, 2000L, 4000L)

// 全局分段并发闸门 Semaphore(5)：每段是同步 call.execute()，不走 OkHttp Dispatcher 的
// maxRequestsPerHost 异步队列（预缓存/播放请求会挤占 host 连接池，调研明确的坑），
// 故用跨任务共享信号量把同时进行的分段请求限制为 5
private const val MAX_CONCURRENT_SEGMENTS = 5
private val globalSegmentPermits = Semaphore(MAX_CONCURRENT_SEGMENTS)

// T5 分段下载器：Range 断点续传 + AES-128-CBC 流式解密 + 每段 3 次退避（1s/2s/4s）。
// T7/T10 消费契约（详见 .omo/evidence/task-5-media3-buffer-download.md §3）：
//  - 目录 cacheDir/downloads/<taskId>/；明文分段 seg-<i4>.ts（segFileName），密文 seg-<i4>.ts.dl，
//    解密中间产物 .part（均以 seg- 开头，loadOrFresh 失配时整组清理）
//  - 明文存在 ⟺ 该段完成（解密/改名原子落定）；T7 可据此只读 .ts 拼接
//  - state.json 四态 pending/downloading/completed/failed；T10 重入同 spec 只补缺失/failed 段
//  - onProgress(completed, total) 在 IO 线程回调：首调 (0, total)，每完成一段回调一次
//  - 返回值：failedIndexes 空=全成功；非空=失败段下标（升序），completedCount 保留可拼接
//  - key 拉取失败：不抛异常，未完成段全标 failed 后聚合返回
//  - 本类不删除产物：分段清理与 state.json 清理由 T7 完成后负责
@Singleton
class SegmentDownloader @Inject constructor(
    private val client: OkHttpClient,
    private val moshi: Moshi,
    @ApplicationContext private val context: Context,
) {

    // 全量下载一个任务；幂等：明文已在的段直接跳过，失败段留给 T10 重入重试
    suspend fun download(
        spec: TaskSpec,
        playlist: MediaPlaylist,
        onProgress: (Int, Int) -> Unit,
    ): SegmentDownloadResult = withContext(Dispatchers.IO) {
        val workDir = File(context.cacheDir, "downloads/${spec.taskId}")
        if (!workDir.exists() && !workDir.mkdirs()) throw IOException("建目录失败：${workDir.path}")
        val stateFile = File(workDir, STATE_FILE_NAME)
        val store = TaskStateStore(stateFile, moshi, loadOrFresh(workDir, spec, playlist))
        val total = playlist.segments.size
        val completed = AtomicInteger(0)
        onProgress(0, total)
        val keyBytes = try {
            fetchKeyBytes(playlist.key)
        } catch (e: IOException) {
            // key 取不到 = 任务级失败：全部未完成段标 failed（T10 可重试），聚合返回不抛
            Log.w(TAG, "key 拉取失败：${e.message}")
            val failed = mutableListOf<Int>()
            for (i in 0 until total) {
                if (store.segment(i).status != SegmentStatus.COMPLETED) {
                    store.update(i) { it.copy(status = SegmentStatus.FAILED) }
                    failed.add(i)
                }
            }
            onProgress(total - failed.size, total)
            return@withContext SegmentDownloadResult(total, total - failed.size, failed)
        }
        val ctx = TaskContext(workDir, store, playlist, keyBytes, completed, total, onProgress)
        val failed = coroutineScope {
            playlist.segments.mapIndexed { i, seg ->
                async { globalSegmentPermits.withPermit { ctx.processSegment(i, seg) } }
            }.awaitAll().filterNotNull().sorted()
        }
        SegmentDownloadResult(total, completed.get(), failed)
    }

    // 拉取 AES-128 密钥（整任务一次）；无 KEY / METHOD=NONE → null（明文下载路径）
    private fun fetchKeyBytes(key: EncryptionKey?): ByteArray? {
        if (key == null || key.method == EncryptionKey.METHOD_NONE) return null
        val url = key.uri ?: throw IOException("EXT-X-KEY 缺 URI")
        val call = client.newCall(Request.Builder().url(url).build())
        return call.execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("key HTTP ${resp.code}：$url")
            val bytes = resp.body?.bytes() ?: throw IOException("key 空响应体：$url")
            if (bytes.size != AES_KEY_BYTES) throw IOException("key 长度 ${bytes.size}≠16：$url")
            bytes
        }
    }

    // 状态复用判定：同 taskId 且分段数一致 → 复用（断点续传基础）；否则删 seg-* 残段
    //（.ts/.dl/.part，防下标错位），state.json 由 fresh 覆盖
    private fun loadOrFresh(workDir: File, spec: TaskSpec, playlist: MediaPlaylist): TaskStateJson {
        val existing = TaskStateStore.read(File(workDir, STATE_FILE_NAME), moshi)
        if (existing != null && existing.taskId == spec.taskId &&
            existing.segments.size == playlist.segments.size) return existing
        workDir.listFiles()?.forEach { f -> if (f.name.startsWith("seg-")) f.delete() }
        return TaskStateStore.fresh(spec, playlist)
    }

    // 单任务共享上下文：并发分段经此读写 store / 用外层 client（inner 持外部引用）
    private inner class TaskContext(
        private val workDir: File, private val store: TaskStateStore,
        private val playlist: MediaPlaylist, private val keyBytes: ByteArray?,
        private val completed: AtomicInteger, private val total: Int,
        private val onProgress: (Int, Int) -> Unit,
    ) {

        // 段处理：明文已在 → 补状态计数；否则 3 次退避尝试；全败标 failed 返回下标
        suspend fun processSegment(index: Int, segment: Segment): Int? {
            val plain = File(workDir, segFileName(index))
            val dl = File(workDir, segDlFileName(index))
            if (plain.exists()) {
                dl.delete() // 明文已落定，残留密文没用了
                if (store.segment(index).status != SegmentStatus.COMPLETED) {
                    // 崩溃窗口修复：rename 后状态没写上 → 补 completed（字节数已由 writeBody 落盘）
                    store.update(index) { it.copy(status = SegmentStatus.COMPLETED) }
                }
                bump()
                return null
            }
            store.update(index) { it.copy(status = SegmentStatus.DOWNLOADING) }
            for (attempt in 1..MAX_ATTEMPTS) {
                try {
                    attemptSegment(index, segment, plain, dl)
                    store.update(index) { it.copy(status = SegmentStatus.COMPLETED) }
                    bump()
                    return null
                } catch (e: IOException) {
                    Log.w(TAG, "seg-$index 第 $attempt 次失败：${e.message}")
                    delay(BACKOFF_MS[attempt - 1]) // 1s/2s/4s，第 3 次败后也退避再标 failed
                }
            }
            store.update(index) { it.copy(status = SegmentStatus.FAILED) }
            return index
        }

        // 进度：完成数自增并回调（每段每轮至多一次）
        private fun bump() = onProgress(completed.incrementAndGet(), total)

        // 单次成功路径：密文齐 → 直接解密；否则 Range 拉取 → 解密/改名出明文
        private fun attemptSegment(index: Int, segment: Segment, plain: File, dl: File) {
            if (plain.exists()) return // 上一轮尝试的 rename 已成功
            val size = store.segment(index).sizeBytes
            val resumed = size != null && size > 0 && dl.exists() && dl.length() == size
            if (!resumed) fetchSegment(index, segment.uri, dl)
            finalizeSegment(index, plain, dl)
        }

        // Range 断点拉取密文到 .dl：206 追加（校验起点），200 覆盖写，416 清残段待重试从头下
        private fun fetchSegment(index: Int, url: String, dl: File) {
            val offset = if (dl.exists()) dl.length() else 0L
            val builder = Request.Builder().url(url)
            if (offset > 0) builder.header("Range", "bytes=$offset-")
            val call = client.newCall(builder.build())
            call.execute().use { resp ->
                val code = resp.code
                if (code == 206) {
                    val cr = resp.header("Content-Range")
                        ?: throw IOException("seg-$index 206 缺 Content-Range")
                    val start = cr.substringAfter("bytes ", "").substringBefore("-").toLongOrNull()
                        ?: throw IOException("seg-$index Content-Range 非法：$cr")
                    if (start != offset) throw IOException("seg-$index 起点 $start≠本地 $offset")
                    writeBody(resp, index, dl, append = true)
                } else if (code == 200) {
                    writeBody(resp, index, dl, append = false)
                } else if (code == 416 && offset > 0) {
                    dl.delete() // 本地残段比服务器长：清掉，退避后重试从头下
                    throw IOException("seg-$index 416（本地 $offset 字节过长），已清理")
                } else {
                    throw IOException("seg-$index HTTP $code：$url")
                }
            }
        }

        // 响应体落盘 + 每 1MB 检查点记 bytesWritten；写完按总长校验（防截断静默成功）
        private fun writeBody(resp: Response, index: Int, dl: File, append: Boolean) {
            val body = resp.body ?: throw IOException("seg-$index 空响应体")
            val crTotal = resp.header("Content-Range")?.substringAfter("/")?.toLongOrNull()
            val expected = if (resp.code == 206) crTotal?.takeIf { it > 0 }
                else body.contentLength().takeIf { it > 0 }
            if (expected != null) store.update(index) { it.copy(sizeBytes = expected) }
            var written = if (append) dl.length() else 0L
            var checkpoint = written
            FileOutputStream(dl, append).use { out ->
                val buffer = ByteArray(BUFFER_BYTES)
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        written += n
                        if (written - checkpoint >= CHECKPOINT_BYTES) {
                            store.update(index) { it.copy(bytesWritten = written) }
                            checkpoint = written
                        }
                    }
                }
            }
            if (expected != null && written != expected)
                throw IOException("seg-$index 长度 $written≠期望 $expected")
            val size = expected ?: written
            store.update(index) { it.copy(bytesWritten = written, sizeBytes = size) }
        }

        // 出明文：无 key → .dl 直接 rename；有 key → 解密到 .part 再 rename（原子，T7 只见完整段）
        private fun finalizeSegment(index: Int, plain: File, dl: File) {
            if (!dl.exists()) throw IOException("seg-$index 密文缺失")
            val key = keyBytes
            if (key == null) {
                if (!dl.renameTo(plain)) throw IOException("seg-$index rename 失败")
                return
            }
            val part = File(workDir, plain.name + ".part")
            try { decryptTo(index, dl, part, key) } catch (e: GeneralSecurityException) {
                part.delete()
                dl.delete() // key/IV 不对时密文留着也解不出，删掉让重试整段重下
                throw IOException("seg-$index 解密失败：${e.message}", e)
            }
            if (!part.renameTo(plain)) throw IOException("seg-$index 解密产物 rename 失败")
            dl.delete()
        }

        // AES/CBC/PKCS5Padding 流式解密（文件→文件，不整段进内存）
        private fun decryptTo(index: Int, dl: File, part: File, key: ByteArray) {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = IvParameterSpec(segmentIv(index))
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), iv)
            FileInputStream(dl).use { input ->
                FileOutputStream(part).use { out ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        val chunk = cipher.update(buffer, 0, n)
                        if (chunk != null && chunk.isNotEmpty()) out.write(chunk)
                    }
                    val tail = cipher.doFinal()
                    if (tail.isNotEmpty()) out.write(tail)
                }
            }
        }

        // IV：显式 ivHex 优先；否则 mediaSequence+index 的 16 字节大端（RFC 8216 §5.2）
        private fun segmentIv(index: Int): ByteArray {
            playlist.key?.ivHex?.let { return hexToBytes(it) }
            var value = playlist.mediaSequence + index
            val iv = ByteArray(16)
            for (i in 15 downTo 0) {
                iv[i] = value.toByte()
                value = value ushr 8
            }
            return iv
        }

        // 32 位 hex → 16 字节；长度非偶/含非 hex 字符 → IOException（T2 已规范化，此处兜底）
        private fun hexToBytes(hex: String): ByteArray {
            if (hex.isEmpty() || hex.length % 2 != 0) throw IOException("IV hex 长度非法：$hex")
            val out = ByteArray(hex.length / 2)
            for (i in out.indices) {
                val hi = Character.digit(hex[i * 2], 16)
                val lo = Character.digit(hex[i * 2 + 1], 16)
                if (hi < 0 || lo < 0) throw IOException("IV hex 含非法字符：$hex")
                out[i] = ((hi shl 4) or lo).toByte()
            }
            return out
        }
    }
}
