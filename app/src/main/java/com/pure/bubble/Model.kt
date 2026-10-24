package com.pure.bubble

import android.content.Context
import com.google.mlkit.nl.translate.TranslateLanguage

data class Lang(val name: String, val code: String)

object Langs {
    // لغات المصدر محدودة باللغات اللي يقدر ML Kit يقرأها (OCR)
    val sources = listOf(
        Lang("الإنجليزية", TranslateLanguage.ENGLISH),
        Lang("اليابانية", TranslateLanguage.JAPANESE),
        Lang("الكورية", TranslateLanguage.KOREAN),
        Lang("الصينية", TranslateLanguage.CHINESE),
        Lang("الفرنسية", TranslateLanguage.FRENCH),
        Lang("الإسبانية", TranslateLanguage.SPANISH),
        Lang("الألمانية", TranslateLanguage.GERMAN),
        Lang("الإيطالية", TranslateLanguage.ITALIAN),
        Lang("التركية", TranslateLanguage.TURKISH)
    )

    val targets = listOf(
        Lang("العربية", TranslateLanguage.ARABIC),
        Lang("الإنجليزية", TranslateLanguage.ENGLISH),
        Lang("الفارسية", TranslateLanguage.PERSIAN),
        Lang("التركية", TranslateLanguage.TURKISH),
        Lang("الفرنسية", TranslateLanguage.FRENCH),
        Lang("الإسبانية", TranslateLanguage.SPANISH),
        Lang("الألمانية", TranslateLanguage.GERMAN),
        Lang("الروسية", TranslateLanguage.RUSSIAN),
        Lang("الإيطالية", TranslateLanguage.ITALIAN),
        Lang("الإندونيسية", TranslateLanguage.INDONESIAN)
    )

    val cjk = setOf(TranslateLanguage.JAPANESE, TranslateLanguage.KOREAN, TranslateLanguage.CHINESE)
}

/** إعدادات جاهزة لمزودي الذكاء الاصطناعي (كلهم يدعمون نفس واجهة OpenAI). الموديل قابل للتعديل. */
data class LlmPreset(val name: String, val base: String, val model: String)

object Presets {
    val list = listOf(
        LlmPreset("Groq (فيه خطة مجانية)", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
        LlmPreset("Gemini (فيه خطة مجانية)", "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash"),
        LlmPreset("OpenRouter", "https://openrouter.ai/api/v1", "google/gemini-2.5-flash"),
        LlmPreset("OpenAI / ChatGPT", "https://api.openai.com/v1", "gpt-4o-mini"),
        LlmPreset("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
        LlmPreset("مخصص (Ollama / LM Studio)", "http://192.168.1.2:11434/v1", "aya")
    )
}

data class AppSettings(
    val src: String,
    val tgt: String,
    val settleMs: Long,     // كم ننتظر بعد ما الشاشة تثبت قبل الترجمة
    val mode: Int,          // 0 = الشاشة كاملة، 1 = إطار عائم
    val bubbleDp: Int,      // حجم الفقاعة 28..72
    val bubbleAlpha: Int,   // شفافية الفقاعة 30..100
    val engine: Int,        // 0 = جوجل مجاني، 1 = LLM، 2 = أوفلاين
    val llmBase: String,
    val llmKey: String,
    val llmModel: String,
    val fl: Int, val ft: Int, val fr: Int, val fb: Int   // مكان الإطار (-1 = غير محدد)
)

object Prefs {
    private const val FILE = "pure_bubble2"

    fun load(c: Context): AppSettings {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val preset = Presets.list[0]
        return AppSettings(
            src = p.getString("src", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH,
            tgt = p.getString("tgt", TranslateLanguage.ARABIC) ?: TranslateLanguage.ARABIC,
            settleMs = p.getLong("settle", 300L),
            mode = p.getInt("mode", 0),
            bubbleDp = p.getInt("bubbleDp", 36),
            bubbleAlpha = p.getInt("bubbleAlpha", 100),
            engine = p.getInt("engine", 0),
            llmBase = p.getString("llmBase", preset.base) ?: preset.base,
            llmKey = p.getString("llmKey", "") ?: "",
            llmModel = p.getString("llmModel", preset.model) ?: preset.model,
            fl = p.getInt("fl", -1), ft = p.getInt("ft", -1),
            fr = p.getInt("fr", -1), fb = p.getInt("fb", -1)
        )
    }

    fun save(c: Context, s: AppSettings) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("src", s.src)
            .putString("tgt", s.tgt)
            .putLong("settle", s.settleMs)
            .putInt("mode", s.mode)
            .putInt("bubbleDp", s.bubbleDp)
            .putInt("bubbleAlpha", s.bubbleAlpha)
            .putInt("engine", s.engine)
            .putString("llmBase", s.llmBase)
            .putString("llmKey", s.llmKey)
            .putString("llmModel", s.llmModel)
            .putInt("fl", s.fl).putInt("ft", s.ft).putInt("fr", s.fr).putInt("fb", s.fb)
            .apply()
    }
}
