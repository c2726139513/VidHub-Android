package com.vidhub.android.download.storage

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import javax.inject.Inject

private const val TAG = "DownloadStorage"
private const val SUB_DIR = "VidHub"
private const val TEMP_SUFFIX = ".dl" // 临时文件 <fileName>.dl → finish 改名去掉后缀

/**
 * 下载成品存储层：单文件写入公共 `Download/VidHub` 目录。
 *
 * 双分支：API 21-28 直写 `<fileName>.dl` → finish 时 renameTo 成品名（截断覆盖）；
 * API 29+ `MediaStore.Downloads` pending 行（RELATIVE_PATH=Download/VidHub、IS_PENDING=1）
 * → finish 清 IS_PENDING，失败 abort = delete uri。
 *
 * 明确不做：存储卷选择、SAF（TV 系统级缺失）、下载目录自定义设置。
 * `open/append/finish/abort` 是 T7 有序拼接的消费契约，签名保持稳定。
 */
class DownloadStorage @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 一次成品的写入句柄：open → append* → finish() 成功，或任意阶段 abort()。 */
    interface Sink {
        /** 追加一段字节；失败时清理本句柄的中间产物并抛 [IOException]。 */
        fun append(bytes: ByteArray)

        /** 原子收尾，返回成品 Uri（API<29 为 file://，API≥29 为 content://）。 */
        fun finish(): Uri

        /** 放弃任务并清理（删 .dl / delete pending 行）；幂等。 */
        fun abort()
    }

    /**
     * API 21-28 调用方的权限门禁；API 29+ 走 MediaStore 免权限，恒 true。
     * 运行时申请 UI 属后续 todo，这里只提供判断。
     */
    fun hasStoragePermission(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED

    /**
     * 打开写入句柄。
     * @param taskId 任务 id，仅用于错误信息定位
     * @param fileName 成品文件名（不含路径、不含 `.dl` 后缀）
     */
    fun open(taskId: String, fileName: String): Sink {
        // fileName 来自远端视频元数据，是不可信输入：禁止路径分隔符逃出 VidHub 目录
        if (fileName.isBlank() ||
            fileName.contains(File.separatorChar) ||
            fileName == "." ||
            fileName == ".."
        ) {
            throw IOException("task=$taskId 非法 fileName: $fileName")
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStoreSink(taskId, fileName)
        } else {
            LegacySink(taskId, fileName)
        }
    }

    /**
     * API 21-28 直写分支。
     *
     * 全程持有单个 [FileOutputStream]（finish 的 flush+close 源于此）：单写者顺序追加，
     * 无缓冲流 write 即落盘，省去逐块 open/close。open 时 append=false 开新会话，
     * 上次崩溃残留的半截 `.dl` 在此截断。
     */
    @Suppress("DEPRECATION") // getExternalStoragePublicDirectory 29 起废弃，但本分支只跑 <29
    private inner class LegacySink(
        private val taskId: String,
        fileName: String
    ) : Sink {
        private val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            SUB_DIR
        )
        private val tempFile = File(dir, "$fileName$TEMP_SUFFIX") // <fileName>.dl
        private val finalFile = File(dir, fileName)
        private var stream: FileOutputStream? = openTemp()

        private fun openTemp(): FileOutputStream {
            if (!dir.isDirectory && !dir.mkdirs()) {
                throw IOException("task=$taskId 无法创建下载目录 ${dir.absolutePath}")
            }
            return try {
                FileOutputStream(tempFile, false)
            } catch (e: SecurityException) {
                throw IOException(
                    "task=$taskId 缺少 WRITE_EXTERNAL_STORAGE，无法写 ${tempFile.name}",
                    e
                )
            }
        }

        override fun append(bytes: ByteArray) {
            val out = stream ?: throw IOException("task=$taskId Sink 已关闭")
            try {
                out.write(bytes)
            } catch (e: IOException) {
                fail(e)
            } catch (e: SecurityException) {
                fail(e)
            }
        }

        override fun finish(): Uri {
            val out = stream ?: throw IOException("task=$taskId Sink 已关闭")
            try {
                out.flush()
                out.close()
                stream = null
            } catch (e: IOException) {
                fail(e)
            } catch (e: SecurityException) {
                fail(e)
            }
            // 成品已存在 → 先删（截断覆盖）；同目录 renameTo 是原子改名
            if (finalFile.exists() && !finalFile.delete()) {
                throw IOException("task=$taskId 无法覆盖已存在的 ${finalFile.name}")
            }
            if (!tempFile.renameTo(finalFile)) {
                throw IOException("task=$taskId ${tempFile.name} 改名为 ${finalFile.name} 失败")
            }
            return Uri.fromFile(finalFile)
        }

        override fun abort() {
            closeQuietly()
            tempFile.delete() // best-effort：不存在/失败均忽略，幂等
        }

        /** 关流 + 删 `.dl` + 重抛为 [IOException]：失败不留半截临时文件。 */
        private fun fail(cause: Throwable): Nothing {
            closeQuietly()
            tempFile.delete()
            throw IOException("task=$taskId 写入 ${tempFile.name} 失败: ${cause.message}", cause)
        }

        private fun closeQuietly() {
            try {
                stream?.close()
            } catch (e: IOException) {
                Log.w(TAG, "task=$taskId 关闭 ${tempFile.name} 失败: ${e.message}")
            }
            stream = null
        }
    }

    /**
     * API 29+ 分支：`MediaStore.Downloads` pending 行，免存储权限。
     *
     * 每次 append 重新 `openFileDescriptor(uri, "rw")`（新描述符文件偏移从 0 开始），
     * 写前必须 `FileChannel.position(channel.size())` seek 到当前文件尾，否则每个分块
     * 都从字节 0 覆盖 —— 这是本分支的关键点。流与 pfd 共享同一 FileDescriptor：
     * 先 stream.use 关（连带关 fd），finally 再关 pfd；对已释放 fd 的二次 close 可能
     * 抛 IOException(EBADF)，表示 fd 已关闭，属幂等收尾，吞掉。
     */
    @RequiresApi(Build.VERSION_CODES.Q)
    private inner class MediaStoreSink(
        private val taskId: String,
        private val displayName: String
    ) : Sink {
        private val uri: Uri = insertPending()
        private var closed = false
        private var committed = false

        private fun insertPending(): Uri {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                // Environment.DIRECTORY_DOWNLOADS == "Download" → RELATIVE_PATH=Download/VidHub
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$SUB_DIR")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val inserted = try {
                context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            } catch (e: SecurityException) {
                throw IOException("task=$taskId MediaStore insert 失败: ${e.message}", e)
            }
            return inserted
                ?: throw IOException("task=$taskId MediaStore insert 返回 null: $displayName")
        }

        override fun append(bytes: ByteArray) {
            if (closed) throw IOException("task=$taskId Sink 已关闭")
            val pfd = try {
                context.contentResolver.openFileDescriptor(uri, "rw")
            } catch (e: SecurityException) {
                throw IOException("task=$taskId 打开 $uri 失败: ${e.message}", e)
            } ?: throw IOException("task=$taskId openFileDescriptor 返回 null: $uri")
            try {
                FileOutputStream(pfd.fileDescriptor).use { out ->
                    val channel = out.channel
                    channel.position(channel.size()) // seek 到文件尾（重开时偏移为 0）
                    val buffer = ByteBuffer.wrap(bytes)
                    while (buffer.hasRemaining()) {
                        channel.write(buffer)
                    }
                }
            } catch (e: SecurityException) {
                throw IOException("task=$taskId 写入 $displayName 失败: ${e.message}", e)
            } finally {
                try {
                    pfd.close()
                } catch (ignored: IOException) {
                    // 流已连带关闭共享 fd 时的二次 close(EBADF)：fd 已释放，忽略
                    Log.d(TAG, "task=$taskId pfd 二次关闭忽略: ${ignored.message}")
                }
            }
        }

        override fun finish(): Uri {
            if (closed) throw IOException("task=$taskId Sink 已关闭")
            closed = true
            val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
            val updated = try {
                context.contentResolver.update(uri, values, null, null)
            } catch (e: SecurityException) {
                throw IOException("task=$taskId 清除 IS_PENDING 失败: ${e.message}", e)
            }
            if (updated == 0) {
                // 行已被系统/用户清掉 → 成品对外不可见，必须报错
                throw IOException("task=$taskId MediaStore 行已不存在: $uri")
            }
            committed = true
            return uri
        }

        override fun abort() {
            closed = true
            if (committed) return // finish 已成功：成品已发布，abort 不再删行（幂等承诺）
            try {
                context.contentResolver.delete(uri, null, null) // best-effort，删过返回 0，幂等
            } catch (e: SecurityException) {
                Log.w(TAG, "task=$taskId 删除 pending 行失败: ${e.message}")
            }
        }
    }
}
