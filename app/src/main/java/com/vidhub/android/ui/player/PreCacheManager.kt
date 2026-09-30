package com.vidhub.android.ui.player

import android.content.Context
import android.os.HandlerThread
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.source.preload.PreCacheHelper
import com.vidhub.android.util.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 范围预缓存（C2 写侧）：包装 PreCacheHelper@1.8.1，往共享 SimpleCache 预拉当前剧集窗口。
 *
 * 契约：
 * - 公开方法只从主线程调用（initPlayer / onEvents / onDestroy 均在主线程）。helper 在调用线程
 *   create → Listener 回调经 applicationHandler 回到该线程（即主线程），回调内可直接动 Player。
 * - 播放侧必须改用 [onUpdatedMediaItem] 回传的 updatedMediaItem（带 cache key），否则读不到
 *   预缓存（1.8.1 javadoc 明示）；是否采纳由调用方把关（仅首播/换集路径采纳，见 PlaybackActivity）。
 * - [release]（Activity onDestroy）保留已缓存内容；@Singleton 跨会话复用 → 下次 preCache 懒重建。
 */
@OptIn(UnstableApi::class)
@Singleton
class PreCacheManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cache: SimpleCache,
) {

    /** 主线程回调：首播/换集命中时播放侧用它重建队列并切到 updatedMediaItem。 */
    var onUpdatedMediaItem: ((MediaItem) -> Unit)? = null

    private var handlerThread: HandlerThread? = null
    private var downloadExecutor: ExecutorService? = null
    private var factory: PreCacheHelper.Factory? = null
    private var helper: PreCacheHelper? = null
    private var cachedUri: String? = null

    // 四回调全接（1.8.1 Listener 无 onPreCacheCompleted → 完成判定只看 progress 百分比）
    private val listener = object : PreCacheHelper.Listener {
        override fun onPrepared(originalMediaItem: MediaItem, updatedMediaItem: MediaItem) {
            Log.d(TAG, "onPrepared: ${originalMediaItem.localConfiguration?.uri}")
            onUpdatedMediaItem?.invoke(updatedMediaItem)
        }

        override fun onPreCacheProgress(
            mediaItem: MediaItem,
            contentLength: Long,
            bytesDownloaded: Long,
            percentageDownloaded: Float,
        ) {
            if (percentageDownloaded >= 100f) {
                Log.d(
                    TAG,
                    "window cached: ${mediaItem.localConfiguration?.uri} (${bytesDownloaded}B)",
                )
            }
        }

        override fun onPrepareError(mediaItem: MediaItem, error: IOException) {
            // 静默降级：只记日志不弹 UI，该窗口放弃，播放侧照常回源
            Log.w(TAG, "prepare error, degrade to normal playback", error)
        }

        override fun onDownloadError(mediaItem: MediaItem, error: IOException) {
            Log.w(TAG, "download error, degrade to normal playback", error)
        }
    }

    /**
     * 预缓存 [mediaItem] 自 [startPositionMs] 起的窗口（首播/换集传 0 或起播点，播放中缓冲不足
     * 传当前缓冲末端），时长 = PLAYER_MAX_BUFFER_MS(120s)。同参数幂等：helper 内部 isReusable
     * 复用在飞任务；窗口移动才取消旧任务（removeCachedContent=false 保留已缓存分段）。
     */
    fun preCache(mediaItem: MediaItem, startPositionMs: Long) {
        val uri = mediaItem.localConfiguration?.uri?.toString() ?: return
        val start = startPositionMs.coerceAtLeast(0L)
        val current = helper
        if (current != null && uri == cachedUri) {
            current.preCache(start, Constants.PLAYER_MAX_BUFFER_MS)
            return
        }
        current?.release(false) // 换集：停旧窗口但保留其缓存内容
        val created = ensureFactory().create(mediaItem)
        helper = created
        cachedUri = uri
        created.preCache(start, Constants.PLAYER_MAX_BUFFER_MS)
    }

    /**
     * 缓冲余量 < PLAYER_MIN_BUFFER_MS 时由 onEvents 调用：从缓冲末端（bufferedPosition =
     * currentPosition + 已缓冲时长）续预取 120s。seek 落入空档时 bufferedPosition ≈
     * currentPosition → 同规则自动覆盖，无需 seek 专用监听。
     */
    fun notifyBuffering(player: Player) {
        val item = player.currentMediaItem ?: return
        val current = player.currentPosition
        val bufferedEnd = player.bufferedPosition
        preCache(item, if (bufferedEnd > current) bufferedEnd else current)
    }

    /**
     * Activity onDestroy 调用：取消当前窗口但保留缓存（release(false)），停 PreCache 线程与
     * 下载池，并清空 onUpdatedMediaItem 防 Activity 泄漏。
     */
    fun release() {
        helper?.release(false)
        helper = null
        cachedUri = null
        onUpdatedMediaItem = null
        factory = null
        downloadExecutor?.shutdownNow()
        downloadExecutor = null
        handlerThread?.quitSafely()
        handlerThread = null
    }

    // 1.8.1 默认 downloadExecutor=Runnable::run → 段下载退化为单任务串行，必须显式多线程池。
    // Factory 构造只收 Looper（源码 Factory(Context, Cache, Looper)）→ 直接传专用线程 looper。
    private fun ensureFactory(): PreCacheHelper.Factory {
        factory?.let { return it }
        val thread = HandlerThread("PreCache").also { it.start() }
        handlerThread = thread
        val pool = Executors.newFixedThreadPool(4)
        downloadExecutor = pool
        val created = PreCacheHelper.Factory(context, cache, thread.looper)
            .setDownloadExecutor(pool)
            .setListener(listener)
        factory = created
        return created
    }

    private companion object {
        const val TAG = "PreCacheManager"
    }
}
