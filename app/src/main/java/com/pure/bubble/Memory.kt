package com.pure.bubble

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/** ذاكرة الترجمة: كل نص انترجم ينحفظ (حتى بعد إغلاق التطبيق) وما ينترجم مرة ثانية. */
class TranslationMemory(ctx: Context?, work: String = "") {

    class Entry(val text: String, val bad: Int)

    private val file: File? = ctx?.let { File(it.filesDir, memoryFileName(work)) }
    private val map = object : LinkedHashMap<String, String>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 6000
    }
    private var dirty = false

    init {
        try {
            val f = file
            if (f != null && f.exists()) {
                val o = JSONObject(f.readText())
                val it = o.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    map[k] = o.getString(k)
                }
            }
        } catch (e: Exception) {
            map.clear()
        }
    }

    fun norm(t: String): String {
        val sb = StringBuilder(t.length)
        for (ch in t) if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        return sb.toString()
    }

    private fun decode(v: String): Entry {
        val i = v.indexOf('|')
        val bad = if (i > 0) v.substring(0, i).toIntOrNull() ?: 0 else 0
        return Entry(if (i >= 0) v.substring(i + 1) else v, bad)
    }

    /** يلقى الترجمة المحفوظة؛ يتحمل أخطاء OCR الصغيرة (حرف زايد/ناقص). */
    fun get(src: String, tgt: String, text: String): Entry? {
        val prefix = "$src>$tgt|"
        val n = norm(text)
        if (n.isEmpty()) return null
        synchronized(map) {
            val exact = map[prefix + n]
            if (exact != null) return decode(exact)
            if (n.length < 8) return null
            val tol = max(1, n.length / 12)
            for ((k, v) in map) {
                if (!k.startsWith(prefix)) continue
                val kn = k.substring(prefix.length)
                if (abs(kn.length - n.length) > tol) continue
                if (lev(kn, n, tol) <= tol) return decode(v)
            }
        }
        return null
    }

    fun put(src: String, tgt: String, text: String, tr: String, bad: Int) {
        val n = norm(text)
        if (n.isEmpty()) return
        synchronized(map) {
            map["$src>$tgt|$n"] = "$bad|$tr"
            dirty = true
        }
    }

    suspend fun flush() {
        val f = file ?: return
        val json: String
        synchronized(map) {
            if (!dirty) return
            dirty = false
            val o = JSONObject()
            for ((k, v) in map) o.put(k, v)
            json = o.toString()
        }
        withContext(Dispatchers.IO) {
            try {
                f.writeText(json)
            } catch (e: Exception) {
            }
        }
    }

    private fun lev(a: String, b: String, limit: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            var rowMin = cur[0]
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
                if (cur[j] < rowMin) rowMin = cur[j]
            }
            if (rowMin > limit) return rowMin
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}

fun memoryFileName(work: String): String {
    val w = work.trim().filter { it.isLetterOrDigit() || it == '_' || it == '-' }.take(40)
    return if (w.isEmpty()) "translation_memory.json" else "translation_memory_$w.json"
}

/** كشف الترجمة الغلط: فاضية، نفس الأصل، كلمات أصلية متروكة، أو قصيرة/طويلة بشكل غير معقول. */
object Quality {
    fun ok(src: String, tr: String, tgt: String): Boolean {
        val t = tr.trim()
        if (t.isEmpty()) return false
        val s = src.trim()
        val srcLetters = s.count { it.isLetter() }
        if (srcLetters >= 4 && t.equals(s, ignoreCase = true)) return false
        var letters = 0
        var latin = 0
        for (ch in t) {
            if (!ch.isLetter()) continue
            letters++
            if (ch.code < 0x250) latin++
        }
        val srcLatin = s.count { it.isLetter() && it.code < 0x250 } * 2 > srcLetters
        val tgtLatin = tgt != "ar" && tgt != "fa" && tgt != "ru" && tgt != "ja" && tgt != "ko" && tgt != "zh"
        if (!tgtLatin && srcLatin && letters >= 6 && latin * 100 / letters > 35) return false
        if (srcLetters >= 12 && t.length < s.length * 0.25) return false
        if (s.length >= 4 && t.length > s.length * 5) return false
        return true
    }
}
