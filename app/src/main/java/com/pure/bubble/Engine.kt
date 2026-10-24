package com.pure.bubble

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.min

/** نص مكتشف (قبل الترجمة). */
data class Block(val rect: Rect, val text: String, val bg: Int, val fg: Int, val lines: Int)

/** عنصر جاهز للرسم (بعد الترجمة). */
data class Item(val rect: Rect, val text: String, val bg: Int, val fg: Int, val lines: Int)

/** قراءة النص من الصورة (OCR) + تقسيم ذكي للأسطر + لون الخلفية. الترجمة بملف Translators. */
class Engine {
    private val recognizers = HashMap<String, TextRecognizer>()

    private fun recognizer(src: String): TextRecognizer = recognizers.getOrPut(src) {
        when (src) {
            TranslateLanguage.JAPANESE ->
                TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            TranslateLanguage.KOREAN ->
                TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            TranslateLanguage.CHINESE ->
                TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            else -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        }
    }

    /**
     * crop = منطقة الإطار العائم (إذا null نقرأ الشاشة كاملة).
     * exclude = مستطيلات ما نقرأها (الفقاعة، القائمة…) بإحداثيات الشاشة.
     */
    suspend fun detect(frame: Bitmap, src: String, crop: Rect?, exclude: List<Rect>): List<Block> {
        val ox = crop?.left ?: 0
        val oy = crop?.top ?: 0
        val bmp = if (crop != null) {
            val l = crop.left.coerceIn(0, frame.width - 2)
            val t = crop.top.coerceIn(0, frame.height - 2)
            val w = min(crop.width(), frame.width - l).coerceAtLeast(2)
            val h = min(crop.height(), frame.height - t).coerceAtLeast(2)
            Bitmap.createBitmap(frame, l, t, w, h)
        } else frame

        val result = recognizer(src).process(InputImage.fromBitmap(bmp, 0)).await()
        val joiner = if (Langs.cjk.contains(src)) "" else " "
        val out = ArrayList<Block>()
        for (b in result.textBlocks) {
            for (g in groupLines(b.lines, joiner)) {
                val text = g.second.trim()
                if (text.length < 2 || text.none { it.isLetter() }) continue
                val local = g.first
                val screenRect = Rect(local.left + ox, local.top + oy, local.right + ox, local.bottom + oy)
                if (exclude.any { Rect.intersects(it, screenRect) }) continue
                val bg = sampleBg(bmp, local)
                val fg = if (luminance(bg) > 0.55f) Color.BLACK else Color.WHITE
                out.add(Block(screenRect, text, bg, fg, g.third))
            }
        }
        return out
    }

    /** يقسم أسطر البلوك لمجموعات: فراغ عمودي كبير أو اختلاف حجم الخط = نص منفصل. */
    private fun groupLines(lines: List<Text.Line>, joiner: String): List<Triple<Rect, String, Int>> {
        val ls = lines.filter { it.boundingBox != null && it.text.isNotBlank() }
            .sortedBy { it.boundingBox!!.top }
        val res = ArrayList<Triple<Rect, String, Int>>()
        var cur: Rect? = null
        var sb = StringBuilder()
        var n = 0
        var lastH = 1
        var lastBottom = 0
        for (l in ls) {
            val r = l.boundingBox!!
            val h = max(1, r.height())
            val c = cur
            val split = c != null && (
                    r.top - lastBottom > 0.9f * max(h, lastH) ||
                            max(h, lastH) > 1.8f * min(h, lastH) ||
                            r.left > c.right + 2 * h || r.right < c.left - 2 * h
                    )
            if (split && c != null) {
                res.add(Triple(c, sb.toString(), n))
                cur = null
                sb = StringBuilder()
                n = 0
            }
            if (cur == null) cur = Rect(r) else cur.union(r)
            if (sb.isNotEmpty()) sb.append(joiner)
            sb.append(l.text.trim())
            n++
            lastH = h
            lastBottom = r.bottom
        }
        val c = cur
        if (c != null) res.add(Triple(c, sb.toString(), n))
        return res
    }

    /** لون الخلفية = اللون الأكثر تكراراً على حلقة حول النص. */
    private fun sampleBg(bmp: Bitmap, r: Rect): Int {
        val pad = 4
        val l = max(0, r.left - pad)
        val t = max(0, r.top - pad)
        val rr = min(bmp.width - 1, r.right + pad)
        val bb = min(bmp.height - 1, r.bottom + pad)
        if (rr <= l || bb <= t) return Color.WHITE
        val counts = HashMap<Int, Int>()
        fun add(x: Int, y: Int) {
            val p = bmp.getPixel(x, y)
            val k = ((Color.red(p) shr 3) shl 10) or ((Color.green(p) shr 3) shl 5) or (Color.blue(p) shr 3)
            counts[k] = (counts[k] ?: 0) + 1
        }
        val steps = 14
        for (i in 0..steps) {
            val x = l + (rr - l) * i / steps
            add(x, t)
            add(x, bb)
            val y = t + (bb - t) * i / steps
            add(l, y)
            add(rr, y)
        }
        var best = 0
        var bestN = -1
        for ((k, v) in counts) if (v > bestN) { best = k; bestN = v }
        val r5 = (best shr 10) and 31
        val g5 = (best shr 5) and 31
        val b5 = best and 31
        return Color.rgb((r5 shl 3) or 4, (g5 shl 3) or 4, (b5 shl 3) or 4)
    }

    private fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        recognizers.clear()
    }
}
