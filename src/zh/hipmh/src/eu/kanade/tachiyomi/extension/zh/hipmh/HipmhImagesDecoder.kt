package eu.kanade.tachiyomi.extension.zh.hipmh

import keiyoushi.utils.parseAs
import java.util.Base64

/**
 * 还原嘻皮漫画阅读页 `v2/chapter` 接口返回的混淆图片数据。
 *
 * 解码流程(与站点 chapter-decoder.js 等价):
 *  1. 去掉前后缀标记(qM9 / Z7),按固定偏移切成 5 段并校验(Vx / pL0);
 *  2. 以 p5+p1+p3 顺序重组,按 7 字符一块、奇数块翻转;
 *  3. 按 源表 -> 目标表 逐字符映射回标准 base64url;
 *  4. base64url -> base64 补 padding 后解码为 UTF-8 JSON,得到图片相对路径数组。
 */
object HipmhImagesDecoder {

    private const val SRC_TABLE =
        "_-9876543210abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val TGT_TABLE =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    private const val PREFIX = "qM9"
    private const val SUFFIX = "Z7"
    private const val FRAG = "Vx"
    private const val MIDDLE = "pL0"
    private const val BLOCK_SIZE = 7

    fun decode(raw: String): List<String> {
        require(raw.startsWith(PREFIX) && raw.endsWith(SUFFIX)) { "invalid images payload" }
        val s = raw.substring(PREFIX.length, raw.length - SUFFIX.length)
        val len = s.length - FRAG.length - MIDDLE.length
        require(len > 0) { "invalid images payload" }

        val c1 = len / 3
        val c2 = (len - c1) / 2
        val b = len - c1 - c2
        val p1 = s.substring(0, c2)
        val p2 = s.substring(c2, c2 + FRAG.length)
        val p3 = s.substring(c2 + FRAG.length, c2 + FRAG.length + b)
        val p4 = s.substring(c2 + FRAG.length + b, c2 + FRAG.length + b + MIDDLE.length)
        val p5 = s.substring(c2 + FRAG.length + b + MIDDLE.length)
        require(p2 == FRAG && p4 == MIDDLE && p5.length == c1) { "images payload checksum failed" }

        val combined = p5 + p1 + p3
        val reversed = StringBuilder(combined.length)
        var block = 0
        var i = 0
        while (i < combined.length) {
            val end = minOf(i + BLOCK_SIZE, combined.length)
            val chunk = combined.substring(i, end)
            reversed.append(if (block % 2 != 0) chunk.reversed() else chunk)
            block++
            i += BLOCK_SIZE
        }

        val b64 = StringBuilder(reversed.length)
        for (i in 0 until reversed.length) {
            val ch = reversed[i]
            val idx = SRC_TABLE.indexOf(ch)
            require(idx >= 0) { "unexpected character '$ch'" }
            b64.append(TGT_TABLE[idx])
        }

        var encoded = b64.toString().replace('-', '+').replace('_', '/')
        while (encoded.length % 4 != 0) encoded += "="
        val text = String(Base64.getDecoder().decode(encoded), Charsets.UTF_8)
        return text.parseAs()
    }
}
