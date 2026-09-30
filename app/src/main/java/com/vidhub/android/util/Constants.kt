package com.vidhub.android.util

object Constants {

    // ---- 网络 ----
    const val CONNECT_TIMEOUT_SECONDS = 15L
    const val READ_TIMEOUT_SECONDS = 30L
    const val WRITE_TIMEOUT_SECONDS = 30L

    // ---- 搜索 ----
    /** 搜索防抖间隔（毫秒） */
    const val SEARCH_DEBOUNCE_MS = 500L

    /** 聚合搜索时的最大并发源数 */
    const val SEARCH_MAX_CONCURRENT_SOURCES = 6

    /** 单次聚合搜索最多请求的源数量 */
    const val SEARCH_MAX_SOURCES = 12

    // ---- 历史/收藏 ----
    const val HISTORY_MAX_ITEMS = 60
    const val FAVORITES_MAX_ITEMS = 200

    /** 播放到接近结尾（剩余小于该值）时视为已看完，下次从头播放 */
    const val HISTORY_NEAR_END_MS = 15_000L

    /** 播放器自动保存进度间隔（毫秒） */
    const val PLAYER_PROGRESS_SAVE_INTERVAL_MS = 10_000L

    // ---- 播放器缓冲（抗网络抖动，供 DefaultLoadControl 使用） ----
    /** 缓冲下限：低于该值播放器继续下载 */
    const val PLAYER_MIN_BUFFER_MS = 60_000

    /** 缓冲上限：达到该值播放器停止下载 */
    const val PLAYER_MAX_BUFFER_MS = 120_000

    /** 首次播放前需要缓冲的媒体时长（对齐 jellyfin EXTRA_LARGE，避免首播/seek 等待过久） */
    const val PLAYER_BUFFER_FOR_PLAYBACK_MS = 5_000

    /** 卡顿（re-buffer）后恢复播放前需要缓冲的媒体时长 */
    const val PLAYER_BUFFER_FOR_REBUFFER_MS = 15_000

    /** 内存缓冲字节上限（硬顶，约 100MB）。不开启 prioritizeTimeOverSizeThresholds，
     *  播放器达到字节上限即停止加载，保证内存占用可预测。 */
    const val PLAYER_TARGET_BUFFER_BYTES = 100 * 1024 * 1024

    // 播放共享缓存上限 512MB（可调）
    const val PLAYER_CACHE_MAX_BYTES = 512L * 1024 * 1024

    // ---- Intent Extra ----
    const val EXTRA_VIDEO_ITEM = "extra_video_item"
    const val EXTRA_EPISODE_INDEX = "extra_episode_index"
    const val EXTRA_EPISODE_URLS = "extra_episode_urls"
    const val EXTRA_START_POSITION_MS = "extra_start_position_ms"
    const val EXTRA_SERVER_ID = "extra_server_id"

    // ---- SharedPreferences / DataStore 名称 ----
    const val PREFS_SECURE_SERVERS = "vidhub_secure_servers"
    const val DATASTORE_HISTORY = "vidhub_history"
    const val DATASTORE_FAVORITES = "vidhub_favorites"
    const val DATASTORE_SOURCES_CACHE = "vidhub_sources_cache"
}
