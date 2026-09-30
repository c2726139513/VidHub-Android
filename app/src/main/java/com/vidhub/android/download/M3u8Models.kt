package com.vidhub.android.download

/**
 * m3u8 解析完整结果（sealed）：成功 → [ParseResult.Success]，失败 → [ParseResult.Failure]。
 * 解析器不抛业务异常，所有不支持/非法情况都以类型化错误返回。
 */
sealed interface ParseResult {
    data class Success(val playlist: ParsedPlaylist) : ParseResult
    data class Failure(val error: M3u8Error) : ParseResult
}

/** 解析出的播放列表：master（码率变体表）或 media（分段表）。T5 下载器的输入。 */
sealed interface ParsedPlaylist

/** 类型化解析/获取错误——UI 可按子类型直接展示文案。 */
sealed interface M3u8Error {
    val message: String

    /** 播放列表语法错误：缺 #EXTM3U、缺 BANDWIDTH、时长非法、无分段、URI 无法解析等 */
    data class InvalidPlaylist(override val message: String) : M3u8Error

    /** v1 不支持的 HLS 特性（如 BYTERANGE）——明确报错，不静默跳过 */
    data class UnsupportedFeature(
        val feature: String,
        override val message: String = "暂不支持的 HLS 特性：$feature",
    ) : M3u8Error

    /** SAMPLE-AES / SAMPLE-AES-CTR 等 DRM 加密——明确超出 v1 范围 */
    data class DrmDetected(
        val method: String,
        override val message: String = "DRM 加密方式不支持：$method",
    ) : M3u8Error

    /** master 变体仍是 master——嵌套超过一层，v1 只允许 1 级解析 */
    data class NestedMaster(
        val url: String,
        override val message: String = "master 嵌套超过 1 层：$url",
    ) : M3u8Error

    /** 网络拉取失败（IO 异常 / HTTP 非 2xx / 非法 URL） */
    data class FetchFailed(
        val url: String,
        val reason: String,
        override val message: String = "拉取播放列表失败：$url（$reason）",
    ) : M3u8Error
}

/** 单文件输出格式：EXT-X-MAP 存在 → fMP4（init+moof/mdat 拼接），否则 TS。 */
enum class OutputFormat(val extension: String) {
    TS(".ts"),
    FMP4(".fmp4"),
}

/** master 变体：下载时按 BANDWIDTH 选最高码率。 */
data class Variant(val bandwidth: Long, val uri: String)

/** master 播放列表（含 #EXT-X-STREAM-INF）。 */
data class MasterPlaylist(val variants: List<Variant>) : ParsedPlaylist

/** 单个媒体分段。byteRange：v1 见 #EXT-X-BYTERANGE 即报 UnsupportedFeature，解析产物恒为 null（字段留给 v2）。 */
data class Segment(
    val durationSec: Double,
    val uri: String,
    val byteRange: String?,
)

/**
 * #EXT-X-KEY 提取结果。
 * @property method [METHOD_NONE] 或 [METHOD_AES_128]
 * @property uri key 地址，已按播放列表 base URL 绝对化（METHOD=NONE 时为 null）
 * @property ivHex 32 位小写十六进制（0x 前缀已剥离、左补零到 128 位）；缺失时为 null
 * @property ivFromSequence true → 下游按 EXT-X-MEDIA-SEQUENCE + 分段下标推导 16 字节大端 IV
 */
data class EncryptionKey(
    val method: String,
    val uri: String?,
    val ivHex: String?,
    val ivFromSequence: Boolean,
) {
    companion object {
        const val METHOD_NONE = "NONE"
        const val METHOD_AES_128 = "AES-128"
    }
}

/**
 * media 播放列表（可直接下载的分段序列）。
 * @property mediaSequence EXT-X-MEDIA-SEQUENCE（缺省 0），用于推导缺省 IV
 * @property key 当前 #EXT-X-KEY 状态：无标签 → null；METHOD=NONE → method=NONE 的明文 key
 * @property initSegmentUri EXT-X-MAP 的 fMP4 init 段地址（存在即判定输出 .fmp4）
 */
data class MediaPlaylist(
    val segments: List<Segment>,
    val mediaSequence: Long,
    val hasEndlist: Boolean,
    val key: EncryptionKey? = null,
    val initSegmentUri: String? = null,
) : ParsedPlaylist {

    /** 输出文件格式与后缀：EXT-X-MAP 存在 → .fmp4，否则 .ts（派生属性，与 initSegmentUri 永不矛盾）。 */
    val outputFormat: OutputFormat
        get() = if (initSegmentUri != null) OutputFormat.FMP4 else OutputFormat.TS
}
