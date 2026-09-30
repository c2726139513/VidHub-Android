package com.vidhub.android.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject

private const val TAG_EXTM3U = "#EXTM3U"
private const val TAG_STREAM_INF = "#EXT-X-STREAM-INF"
private const val TAG_EXTINF = "#EXTINF"
private const val TAG_KEY = "#EXT-X-KEY"
private const val TAG_MAP = "#EXT-X-MAP"
private const val TAG_BYTERANGE = "#EXT-X-BYTERANGE"
private const val TAG_MEDIA_SEQUENCE = "#EXT-X-MEDIA-SEQUENCE"
private const val TAG_ENDLIST = "#EXT-X-ENDLIST"
private const val HEX_DIGITS = "0123456789abcdefABCDEF"

private fun failure(error: M3u8Error): ParseResult = ParseResult.Failure(error)

private fun unsupported(feature: String): ParseResult =
    ParseResult.Failure(M3u8Error.UnsupportedFeature(feature))

// #EXT-X-KEY 单行结果：Ok → 加密状态；Err → 类型化错误（DRM / IV 非法 / 缺属性）
private sealed interface KeyResult {
    data class Ok(val key: EncryptionKey) : KeyResult
    data class Err(val error: M3u8Error) : KeyResult
}

/**
 * m3u8 播放列表纯函数解析器（无网络）：master/media 判定、#EXTINF 时长、URI 按 baseUrl 绝对化、
 * #EXT-X-KEY（NONE/AES-128、URI 绝对化、IV 归一化、缺 IV → 媒体序号推导标记）、#EXT-X-MAP（→ .fmp4）。
 * v1 明确报错（不静默跳过）：BYTERANGE、SAMPLE-AES（DRM）、KEY 轮换、分段后出现 MAP。
 */
object M3u8Parser {

    /** 解析播放列表文本；text 为 m3u8 原文（CRLF/LF/BOM 均可），baseUrl 为相对 URI 基准。 */
    fun parse(text: String, baseUrl: String): ParseResult {
        val lines = text.removePrefix("\uFEFF").lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.none { it.startsWith(TAG_EXTM3U) }) {
            return failure(M3u8Error.InvalidPlaylist("缺少 #EXTM3U 头"))
        }
        return if (lines.any { it.startsWith(TAG_STREAM_INF) }) parseMaster(lines, baseUrl)
        else parseMedia(lines, baseUrl)
    }

    // master：收集全部 #EXT-X-STREAM-INF 的 BANDWIDTH + 绝对化变体 URI（进入本分支必有 ≥1 条）
    private fun parseMaster(lines: List<String>, baseUrl: String): ParseResult {
        val variants = mutableListOf<Variant>()
        for ((index, line) in lines.withIndex()) {
            if (!line.startsWith(TAG_STREAM_INF)) continue
            val attrs = parseAttributes(line.substringAfter(':'))
            val bandwidth = attrs["BANDWIDTH"]?.toLongOrNull()
                ?: return failure(M3u8Error.InvalidPlaylist("缺 BANDWIDTH：$line"))
            var uriIndex = index + 1
            while (uriIndex < lines.size && lines[uriIndex].startsWith("#")) uriIndex++
            if (uriIndex >= lines.size) return failure(M3u8Error.InvalidPlaylist("缺变体 URI：$line"))
            val uriLine = lines[uriIndex]
            val uri = absolutize(baseUrl, uriLine)
                ?: return failure(M3u8Error.InvalidPlaylist("变体 URI 无法解析：$uriLine"))
            variants += Variant(bandwidth, uri)
        }
        return ParseResult.Success(MasterPlaylist(variants))
    }

    // media：逐行状态机 → 分段序列 + KEY/MAP/MEDIA-SEQUENCE 状态
    private fun parseMedia(lines: List<String>, baseUrl: String): ParseResult {
        var mediaSequence = 0L
        var hasEndlist = false
        var key: EncryptionKey? = null
        var initSegmentUri: String? = null
        var pendingDurationSec: Double? = null
        val segments = mutableListOf<Segment>()
        for (line in lines) {
            when {
                line.startsWith(TAG_MEDIA_SEQUENCE) -> {
                    mediaSequence = line.substringAfter(':').trim().toLongOrNull()
                        ?: return failure(M3u8Error.InvalidPlaylist("MEDIA-SEQUENCE 非法：$line"))
                }

                line == TAG_ENDLIST -> hasEndlist = true

                line.startsWith(TAG_BYTERANGE) -> return unsupported("BYTERANGE")

                line.startsWith(TAG_KEY) -> when (val result = parseKey(line, baseUrl)) {
                    is KeyResult.Err -> return failure(result.error)
                    is KeyResult.Ok -> {
                        // 同键重复声明不算轮换；明文↔AES 切换、换 URI/IV、中途引入 key 均算
                        val changed = key?.method != result.key.method ||
                            (result.key.method == EncryptionKey.METHOD_AES_128 && key != result.key)
                        if (segments.isNotEmpty() && changed) return unsupported("KEY_ROTATION")
                        key = result.key
                    }
                }

                line.startsWith(TAG_MAP) -> {
                    if (segments.isNotEmpty()) return unsupported("MAP_MID_PLAYLIST")
                    val attrs = parseAttributes(line.substringAfter(':'))
                    if ("BYTERANGE" in attrs) return unsupported("BYTERANGE")
                    val raw = attrs["URI"]
                        ?: return failure(M3u8Error.InvalidPlaylist("缺 MAP URI：$line"))
                    initSegmentUri = absolutize(baseUrl, raw)
                        ?: return failure(M3u8Error.InvalidPlaylist("MAP URI 无法解析：$raw"))
                }

                line.startsWith(TAG_EXTINF) -> {
                    if (pendingDurationSec != null) {
                        return failure(M3u8Error.InvalidPlaylist("EXTINF 缺分段 URI：$line"))
                    }
                    val raw = line.substringAfter(':').substringBefore(',').trim()
                    pendingDurationSec = raw.toDoubleOrNull()
                        ?: return failure(M3u8Error.InvalidPlaylist("EXTINF 时长非法：$line"))
                }

                line.startsWith("#") -> Unit // 其他标签/注释：v1 忽略

                else -> {
                    val duration = pendingDurationSec
                        ?: return failure(M3u8Error.InvalidPlaylist("分段缺 #EXTINF：$line"))
                    val uri = absolutize(baseUrl, line)
                        ?: return failure(M3u8Error.InvalidPlaylist("分段 URI 无法解析：$line"))
                    segments += Segment(duration, uri, null)
                    pendingDurationSec = null
                }
            }
        }
        if (pendingDurationSec != null) return failure(M3u8Error.InvalidPlaylist("末尾 EXTINF 缺分段"))
        if (segments.isEmpty()) return failure(M3u8Error.InvalidPlaylist("没有分段"))
        val playlist = MediaPlaylist(segments, mediaSequence, hasEndlist, key, initSegmentUri)
        return ParseResult.Success(playlist)
    }

    // #EXT-X-KEY：NONE/AES-128 → Ok；SAMPLE-AES* → DrmDetected；缺属性/IV 非法 → InvalidPlaylist
    private fun parseKey(line: String, baseUrl: String): KeyResult {
        val attrs = parseAttributes(line.substringAfter(':'))
        val method = attrs["METHOD"]?.uppercase()
            ?: return KeyResult.Err(M3u8Error.InvalidPlaylist("KEY 缺 METHOD：$line"))
        return when (method) {
            EncryptionKey.METHOD_NONE ->
                KeyResult.Ok(EncryptionKey(EncryptionKey.METHOD_NONE, null, null, false))

            EncryptionKey.METHOD_AES_128 -> {
                val uriRaw = attrs["URI"]
                    ?: return KeyResult.Err(M3u8Error.InvalidPlaylist("KEY 缺 URI：$line"))
                val keyUri = absolutize(baseUrl, uriRaw)
                    ?: return KeyResult.Err(M3u8Error.InvalidPlaylist("KEY URI 无法解析：$uriRaw"))
                val ivRaw = attrs["IV"]
                val ivHex = ivRaw?.let { normalizeIvHex(it) }
                if (ivRaw != null && ivHex == null) {
                    KeyResult.Err(M3u8Error.InvalidPlaylist("IV 非法（非十六进制）：$ivRaw"))
                } else {
                    val fromSeq = ivRaw == null
                    val key = EncryptionKey(EncryptionKey.METHOD_AES_128, keyUri, ivHex, fromSeq)
                    KeyResult.Ok(key)
                }
            }

            else ->
                if (method.startsWith("SAMPLE-AES")) KeyResult.Err(M3u8Error.DrmDetected(method))
                else KeyResult.Err(M3u8Error.UnsupportedFeature("KEY_METHOD=$method"))
        }
    }

    // IV 归一化：剥 0x、校验十六进制、左补零到 32 位（128 bit）；非法返回 null
    private fun normalizeIvHex(raw: String): String? {
        val digits = raw.trim().removePrefix("0x").removePrefix("0X")
        if (digits.isEmpty() || digits.length > 32 || digits.any { it !in HEX_DIGITS }) return null
        return digits.lowercase().padStart(32, '0')
    }

    // 逗号分隔属性表（引号内逗号/= 不拆分），如 METHOD=AES-128,URI="https://k/a,b",IV=0x…
    private fun parseAttributes(list: String): Map<String, String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in list) {
            when {
                ch == '"' -> { inQuotes = !inQuotes; current.append(ch) }
                ch == ',' && !inQuotes -> { tokens += current.toString(); current.setLength(0) }
                else -> current.append(ch)
            }
        }
        tokens += current.toString()
        return tokens.mapNotNull { token ->
            val i = token.indexOf('=')
            if (i <= 0) return@mapNotNull null
            token.substring(0, i).trim() to token.substring(i + 1).trim().removeSurrounding("\"")
        }.toMap()
    }

    // URI 绝对化：绝对 http(s) 原样返回，相对路径按 baseUrl 做 RFC 3986 解析（HttpUrl 容忍非 ASCII 路径）
    private fun absolutize(baseUrl: String, reference: String): String? {
        val ref = reference.trim()
        if (ref.isEmpty()) return null
        if (ref.startsWith("http://", true) || ref.startsWith("https://", true)) return ref
        return baseUrl.toHttpUrlOrNull()?.resolve(ref)?.toString()
    }
}

/**
 * HLS 播放列表网络层：复用 AppModule 的单例 OkHttpClient（Hilt 注入，不新建 client）。
 * fetch 用 call.execute() 跑在 Dispatchers.IO——maxRequestsPerHost=5 只限 enqueue，同步 execute 不受限。
 */
class M3u8Fetcher @Inject constructor(
    private val client: OkHttpClient,
) {

    /** 拉取播放列表原文；失败抛 IOException（网络/非 2xx/空体）或 IllegalArgumentException（URL 非法）。 */
    suspend fun fetch(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}：$url")
            response.body?.string() ?: throw IOException("空响应体：$url")
        }
    }

    /**
     * 拉取并解析：media 直接返回；master 按 BANDWIDTH 选最高码率变体后再拉取一层
     * （变体仍是 master → [M3u8Error.NestedMaster]，不做多层递归）。业务错误走 Failure，不抛出。
     */
    suspend fun resolve(url: String): ParseResult {
        val master = when (val first = fetchAndParse(url)) {
            is ParseResult.Failure -> return first
            is ParseResult.Success -> when (val playlist = first.playlist) {
                is MediaPlaylist -> return first
                is MasterPlaylist -> playlist
            }
        }
        val variant = master.variants.maxByOrNull { it.bandwidth }
            ?: return failure(M3u8Error.InvalidPlaylist("master 无变体：$url"))
        return when (val second = fetchAndParse(variant.uri)) {
            is ParseResult.Failure -> second
            is ParseResult.Success -> when (val playlist = second.playlist) {
                is MediaPlaylist -> second
                is MasterPlaylist -> failure(M3u8Error.NestedMaster(variant.uri))
            }
        }
    }

    private suspend fun fetchAndParse(url: String): ParseResult {
        val text = try {
            fetch(url)
        } catch (e: IOException) {
            return failure(M3u8Error.FetchFailed(url, e.message ?: e.javaClass.simpleName))
        } catch (e: IllegalArgumentException) {
            return failure(M3u8Error.FetchFailed(url, "非法 URL：${e.message}"))
        }
        return M3u8Parser.parse(text, url)
    }
}
