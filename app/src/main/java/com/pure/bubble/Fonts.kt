package com.pure.bubble

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface

/**
 * خطوط بيور (داخل التطبيق): عادية، غضب، صراخ، سعادة، تفكير، توتر، تهديد، مربع.
 * التطبيق يختار الخط تلقائياً حسب نبرة النص ولون الفقاعة.
 */
object PureFonts {
    val keys = listOf("normal", "angry", "scream", "happy", "thinking", "nervous", "threat", "square")

    private val cache = HashMap<String, Typeface?>()

    fun get(ctx: Context, key: String): Typeface? = synchronized(cache) {
        if (!cache.containsKey(key)) {
            cache[key] = try {
                Typeface.createFromAsset(ctx.assets, "fonts/$key.ttf")
            } catch (e: Exception) {
                null
            }
        }
        cache[key]
    }

    private val angryWords = Regex(
        "\\b(damn|hell|die|kill|shut up|idiot|stupid|bastard|fool|hate|bastards|trash|scum|get out|how dare)\\b",
        RegexOption.IGNORE_CASE
    )
    private val happyWords = Regex("(♪|♡|♥|~|\\b(haha+|hehe+|yay+|lol|wow|thank you|thanks)\\b)", RegexOption.IGNORE_CASE)
    private val stutter = Regex("\\b[A-Za-z]-[A-Za-z]")
    private val dots = Regex("\\.\\.\\.|…")

    private fun lum(c: Int): Float =
        (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f

    /** يختار مفتاح الخط من نص الأصل + لون الخلفية + شكل المستطيل. */
    fun pick(src: String, bg: Int, rectW: Int, rectH: Int, lines: Int): String {
        val t = src.trim()
        val l = lum(bg)
        if (l < 0.32f) return "threat"                       // فقاعة سوداء = تهديد

        val ex = t.count { it == '!' || it == '！' }
        val letters = t.filter { it.isLetter() }
        val caps = letters.length >= 3 && letters.count { it.isUpperCase() } * 100 / letters.length > 80

        if (ex >= 2 || (caps && ex >= 1)) return "scream"
        if (caps || (ex >= 1 && angryWords.containsMatchIn(t))) return "angry"
        if (t.startsWith("(") || t.startsWith("（") ||
            ((t.endsWith("...") || t.endsWith("…")) && t.length < 40)
        ) return "thinking"
        if (stutter.containsMatchIn(t) || dots.findAll(t).count() >= 2) return "nervous"
        if (happyWords.containsMatchIn(t)) return "happy"

        val mx = maxOf(Color.red(bg), Color.green(bg), Color.blue(bg))
        val mn = minOf(Color.red(bg), Color.green(bg), Color.blue(bg))
        if (lines == 1 && rectW >= 4 * rectH && l > 0.6f && mx - mn > 24) return "square"   // صندوق سرد ملوّن
        return "normal"
    }
}
