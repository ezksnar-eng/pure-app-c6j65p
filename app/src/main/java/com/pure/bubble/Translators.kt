package com.pure.bubble

import android.util.LruCache
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * مركز الترجمة: كاش + سلسلة محركات.
 * 0 = جوجل (مجاني بدون مفتاح)، 1 = ذكاء اصطناعي LLM (مفتاحك)، 2 = أوفلاين ML Kit.
 * إذا المحرك الأول فشل أو رجّع ناقص، ينتقل تلقائياً للي بعده — فما تبقى كلمة بدون ترجمة.
 */
class Hub(private val s: AppSettings) {

    var onError: ((String) -> Unit)? = null

    private val cache = LruCache<String, String>(900)
    private val recent = ArrayList<String>()   // سياق للـ LLM (آخر أسطر ترجمها)
    private val mlTranslators = HashMap<String, Translator>()
    private var errorShown = false

    private fun key(t: String) = "${s.engine}|${s.src}|${s.tgt}|" + t.trim().lowercase()

    private fun chain(): List<Int> = when (s.engine) {
        1 -> listOf(1, 0, 2)
        2 -> listOf(2, 0)
        else -> listOf(0, 2)
    }

    suspend fun translate(texts: List<String>): List<String?> {
        val out = arrayOfNulls<String>(texts.size)
        val need = LinkedHashMap<String, MutableList<Int>>()
        for ((i, t) in texts.withIndex()) {
            val c = cache.get(key(t))
            if (c != null) out[i] = c else need.getOrPut(t) { ArrayList() }.add(i)
        }
        if (need.isNotEmpty()) {
            val uniq = need.keys.toList()
            val res = arrayOfNulls<String>(uniq.size)
            for (eng in chain()) {
                val missing = uniq.indices.filter { res[it] == null }
                if (missing.isEmpty()) break
                val got: List<String?> = try {
                    exec(eng, missing.map { uniq[it] })
                } catch (e: Exception) {
                    fail(eng, e.message ?: e.javaClass.simpleName)
                    List(missing.size) { null }
                }
                for ((j, idx) in missing.withIndex()) {
                    val g = got.getOrNull(j)
                    if (!g.isNullOrBlank()) res[idx] = g
                }
            }
            for ((j, t) in uniq.withIndex()) {
                val r = res[j] ?: continue
                cache.put(key(t), r)
                for (i in need[t]!!) out[i] = r
                recent.add("$t => $r")
                if (recent.size > 8) recent.removeAt(0)
            }
        }
        return out.toList()
    }

    private fun fail(eng: Int, msg: String) {
        if (errorShown) return
        errorShown = true
        val n = when (eng) { 1 -> "الذكاء الاصطناعي"; 0 -> "جوجل"; else -> "الأوفلاين" }
        onError?.invoke("تعذّر محرك $n ($msg) — راح أستعمل البديل")
    }

    private suspend fun exec(eng: Int, texts: List<String>): List<String?> = when (eng) {
        1 -> llm(texts)
        0 -> google(texts)
        else -> mlkit(texts)
    }

    // ------------------------------------------------------------ Google (free)

    private fun gl(code: String) = if (code == "zh") "zh-CN" else code

    private suspend fun google(texts: List<String>): List<String?> = coroutineScope {
        val sem = Semaphore(6)
        texts.map { t -> async { sem.withPermit { googleOne(t) } } }.awaitAll()
    }

    private suspend fun googleOne(t: String): String? = withContext(Dispatchers.IO) {
        try {
            val u = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=${gl(s.src)}" +
                    "&tl=${gl(s.tgt)}&dt=t&q=" + URLEncoder.encode(t.take(1500), "UTF-8")
            val c = URL(u).openConnection() as HttpURLConnection
            c.connectTimeout = 6000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) {
                c.disconnect()
                null
            } else {
                val body = c.inputStream.bufferedReader().use { it.readText() }
                c.disconnect()
                val segs = JSONArray(body).getJSONArray(0)
                val sb = StringBuilder()
                for (i in 0 until segs.length()) sb.append(segs.getJSONArray(i).optString(0, ""))
                val r = sb.toString().trim()
                if (r.isEmpty()) null else r
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------ LLM (OpenAI-compatible)

    private fun langName(code: String): String =
        Locale(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code }

    private suspend fun llm(texts: List<String>): List<String?> = withContext(Dispatchers.IO) {
        if (s.llmBase.isBlank() || s.llmModel.isBlank()) {
            throw IllegalStateException("إعدادات الـ LLM ناقصة")
        }
        val srcN = langName(s.src)
        val tgtN = langName(s.tgt)
        val sys = "You are a professional $srcN-to-$tgtN translator for manga, manhwa, games and apps. " +
                "Translate EVERY input string completely into natural, fluent $tgtN. " +
                "Never leave a $srcN word untranslated except proper names. Keep the tone, be concise " +
                "so it fits a speech bubble. Reply with ONLY a JSON array of strings, same length and " +
                "same order as the input, no explanations."
        val ctx = if (recent.isEmpty()) "" else
            "Earlier lines for context (do not output them):\n" + recent.joinToString("\n") + "\n\n"
        val user = ctx + "Input:\n" + JSONArray(texts).toString()
        val body = JSONObject()
            .put("model", s.llmModel)
            .put("temperature", 0.2)
            .put(
                "messages", JSONArray()
                    .put(JSONObject().put("role", "system").put("content", sys))
                    .put(JSONObject().put("role", "user").put("content", user))
            ).toString().toByteArray(Charsets.UTF_8)

        val base = s.llmBase.trim().trimEnd('/')
        val c = URL("$base/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 8000
        c.readTimeout = 30000
        c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json")
        if (s.llmKey.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${s.llmKey.trim()}")
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        c.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code")

        val content = JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
        val a = content.indexOf('[')
        val b = content.lastIndexOf(']')
        if (a < 0 || b <= a) throw IllegalStateException("رد غير مفهوم")
        val arr = JSONArray(content.substring(a, b + 1))
        if (arr.length() != texts.size) throw IllegalStateException("عدد الأسطر ما يطابق")
        List(texts.size) { i -> arr.optString(i, "").trim().ifEmpty { null } }
    }

    // ------------------------------------------------------------ ML Kit (offline)

    private fun mlTranslator(): Translator = mlTranslators.getOrPut("${s.src}>${s.tgt}") {
        Translation.getClient(
            TranslatorOptions.Builder().setSourceLanguage(s.src).setTargetLanguage(s.tgt).build()
        )
    }

    private suspend fun mlkit(texts: List<String>): List<String?> {
        val tr = mlTranslator()
        tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        return texts.map { t ->
            try {
                tr.translate(t).await().trim().ifEmpty { null }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun close() {
        mlTranslators.values.forEach { runCatching { it.close() } }
        mlTranslators.clear()
    }
}
