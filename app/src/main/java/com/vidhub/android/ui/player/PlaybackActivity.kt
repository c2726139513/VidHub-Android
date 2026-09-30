package com.vidhub.android.ui.player

import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.PriorityTaskManager
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.vidhub.android.R
import com.vidhub.android.model.VideoItem
import com.vidhub.android.util.Constants
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 全屏播放页。
 *
 * 关键约定：视频 URL 来自 /api/detail，ExoPlayer 直连播放 M3U8，不走任何代理。
 * 播放进度定时保存，退出时保存；一集播完自动连播下一集。
 */
@AndroidEntryPoint
class PlaybackActivity : FragmentActivity() {

    private val viewModel: PlayerViewModel by viewModels()

    @Inject
    @OptIn(UnstableApi::class)
    lateinit var cacheFactory: CacheDataSource.Factory

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var titleView: TextView

    private lateinit var videoItem: VideoItem
    private var episodeUrls: List<String> = emptyList()
    private var currentIndex: Int = 0
    private var startPositionMs: Long = 0L

    private var progressSaveJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.player_view)
        titleView = findViewById(R.id.player_title)

        val item = IntentCompat.getParcelableExtra(
            intent, Constants.EXTRA_VIDEO_ITEM, VideoItem::class.java,
        )
        val urls = intent.getStringArrayListExtra(Constants.EXTRA_EPISODE_URLS)
        if (item == null || urls.isNullOrEmpty()) {
            finish()
            return
        }
        videoItem = item
        episodeUrls = urls
        currentIndex = intent.getIntExtra(Constants.EXTRA_EPISODE_INDEX, 0)
            .coerceIn(0, episodeUrls.size - 1)
        startPositionMs = intent.getLongExtra(Constants.EXTRA_START_POSITION_MS, 0L)

        viewModel.bind(videoItem, episodeUrls.size, currentIndex)
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
        startProgressSaver()
    }

    override fun onStop() {
        saveCurrentProgress()
        progressSaveJob?.cancel()
        progressSaveJob = null
        releasePlayer()
        super.onStop()
    }

    @OptIn(UnstableApi::class)
    private fun initPlayer() {
        // 抗网络抖动：加大缓冲窗口与卡顿恢复阈值，字节上限硬顶内存。
        // 刻意不开 setPrioritizeTimeOverSizeThresholds —— 1.3.1 无 OOM 保护，开了内存会失控。
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                Constants.PLAYER_MIN_BUFFER_MS,
                Constants.PLAYER_MAX_BUFFER_MS,
                Constants.PLAYER_BUFFER_FOR_PLAYBACK_MS,
                Constants.PLAYER_BUFFER_FOR_REBUFFER_MS,
            )
            .setTargetBufferBytes(Constants.PLAYER_TARGET_BUFFER_BYTES)
            .build()
        // 播放读接共享缓存（命中读 Cache，未命中经 T6 upstream 回源）；player 侧 Factory 挂 PTM，读任务按
        // PRIORITY_PLAYBACK 注册（Builder.setPriorityTaskManager 加载期间 add/remove）。已知良性限制：
        // PreCacheHelper 的 Factory 无 PTM 注入点，其下载任务不注册——并发写锁期间播放读回退上游直拉（不劣于现状）。
        val priorityTaskManager = PriorityTaskManager()
        cacheFactory.setUpstreamPriorityTaskManager(priorityTaskManager)
        val exo = ExoPlayer.Builder(this)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(cacheFactory))
            .setPriorityTaskManager(priorityTaskManager)
            .build()
        player = exo
        playerView.player = exo
        playerView.keepScreenOn = true

        exo.setMediaItems(
            episodeUrls.map { buildMediaItem(it) },
            currentIndex,
            startPositionMs,
        )
        exo.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val newIndex = exo.currentMediaItemIndex
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    // 上一集自然播完：记录到历史（归零），再切到新集
                    viewModel.saveEpisodeFinished()
                }
                if (newIndex != currentIndex) {
                    currentIndex = newIndex
                    viewModel.episodeIndex = newIndex
                    updateTitle()
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(
                    this@PlaybackActivity,
                    getString(R.string.player_error) + "：" + (error.errorCodeName),
                    Toast.LENGTH_LONG,
                ).show()
            }
        })
        exo.prepare()
        exo.playWhenReady = true
        updateTitle()
    }

    private fun releasePlayer() {
        playerView.player = null
        player?.release()
        player = null
    }

    /**
     * 构造 MediaItem。部分 CMS 源的 M3U8 地址不带标准扩展名（如 ?type=m3u8 的 CDN 链接），
     * 会导致 ExoPlayer 无法按路径推断 HLS 类型，这里显式标注。
     */
    private fun buildMediaItem(url: String): MediaItem {
        return if (url.contains("m3u8", ignoreCase = true)) {
            MediaItem.Builder()
                .setUri(url)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
        } else {
            MediaItem.fromUri(url)
        }
    }

    private fun updateTitle() {
        titleView.text = "${videoItem.title}  " +
            getString(R.string.player_episode_format, currentIndex + 1, episodeUrls.size)
    }

    private fun startProgressSaver() {
        progressSaveJob?.cancel()
        progressSaveJob = lifecycleScope.launch {
            while (isActive) {
                delay(Constants.PLAYER_PROGRESS_SAVE_INTERVAL_MS)
                saveCurrentProgress()
            }
        }
    }

    private fun saveCurrentProgress() {
        val exo = player ?: return
        val duration = exo.duration
        viewModel.saveProgress(
            positionMs = exo.currentPosition.coerceAtLeast(0L),
            durationMs = if (duration > 0) duration else 0L,
        )
    }
}
