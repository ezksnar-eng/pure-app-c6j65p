package com.pure.bubble

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.mlkit.nl.translate.TranslateLanguage
import kotlinx.coroutines.tasks.await

data class Item(val rect: Rect, val text: String, val bg: Int, val fg: Int)

class Engine {
    private val recognizers = HashMap<String, TextRecognizer>()
    private val translators = HashMap<String, Translator>()
    private val ready = HashSet<String>()

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

    private fun translator(src: String, tgt: String): Translator =
        translators.getOrPut("$src>$tgt") {
            Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(src)
                    .setTargetLanguage(tgt)
                    .build()
            )
        }

    /** يحمّل نموذج الترجمة أول مرة فقط (يحتاج انترنت). */
    suspend fun ensureModel(src: String, tgt: String) {
        val key = "$src>$tgt"
        if (ready.contains(key)) return
        translator(src, tgt).downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        ready.add(key)
    }

    fun isModelReady(src: String, tgt: String) = ready.contains("$src>$tgt")

    suspend fun run(frame: Bitmap, src: String, tgt: String, exclude: Rect?): List<Item> {
        if (src == tgt) return emptyList()

        val result = recognizer(src).process(InputImage.fromBitmap(frame, 0)).await()
        val blocks = result.textBlocks.filter { b ->
            val r = b.boundingBox
            r != null &&
                    b.text.trim().length >= 2 &&
                    b.text.any { it.isLetter() } &&
                    (exclude == null || !Rect.intersects(r, exclude))
        }
        if (blocks.isEmpty()) return emptyList()

        ensureModel(src, tgt)
        val tr = translator(src, tgt)
        val joiner = if (Langs.cjk.contains(src)) "" else " "

        val out = ArrayList<Item>()
        for (b in blocks) {
            val rect = b.boundingBox ?: continue
            val clean = b.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
                .joinToString(joiner).trim()
            if (clean.isEmpty()) continue
            val translated = try {
                tr.translate(clean).await().trim()
            } catch (e: Exception) {
                continue
            }
            if (translated.isEmpty() || translated.equals(clean, ignoreCase = true)) continue

            val bg = sampleBg(frame, rect)
            out.add(Item(Rect(rect), translated, bg, if (luminance(bg) > 0.55f) Color.BLACK else Color.WHITE))
        }
        return out
    }

    /** لون الخلفية المحيطة بالنص (الوسيط من نقاط حول الصندوق). */
    private fun sampleBg(bmp: Bitmap, r: Rect): Int {
        val pad = 4
        val l = (r.left - pad).coerceIn(0, bmp.width - 1)
        val t = (r.top - pad).coerceIn(0, bmp.height - 1)
        val rr = (r.right + pad).coerceIn(0, bmp.width - 1)
        val bb = (r.bottom + pad).coerceIn(0, bmp.height - 1)
        val xs = intArrayOf(l, (l + rr) / 2, rr)
        val ys = intArrayOf(t, (t + bb) / 2, bb)
        val reds = ArrayList<Int>()
        val greens = ArrayList<Int>()
        val blues = ArrayList<Int>()
        for (xi in xs.indices) {
            for (yi in ys.indices) {
                if (xi == 1 && yi == 1) continue
                val p = bmp.getPixel(xs[xi], ys[yi])
                reds.add(Color.red(p)); greens.add(Color.green(p)); blues.add(Color.blue(p))
            }
        }
        reds.sort(); greens.sort(); blues.sort()
        val m = reds.size / 2
        return Color.rgb(reds[m], greens[m], blues[m])
    }

    private fun luminance(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    fun close() {
        recognizers.values.forEach { runCatching { it.close() } }
        translators.values.forEach { runCatching { it.close() } }
        recognizers.clear(); translators.clear(); ready.clear()
    }
}
