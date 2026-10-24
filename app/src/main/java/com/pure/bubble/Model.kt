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

data class AppSettings(
    val src: String,
    val tgt: String,
    val auto: Boolean,
    val intervalMs: Long,
    val coverAlpha: Int,   // شفافية صندوق الترجمة 30..100
    val bubbleAlpha: Int   // شفافية الفقاعة العائمة 30..100
)

object Prefs {
    private const val FILE = "pure_bubble"

    fun load(c: Context): AppSettings {
        val p = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return AppSettings(
            src = p.getString("src", TranslateLanguage.ENGLISH) ?: TranslateLanguage.ENGLISH,
            tgt = p.getString("tgt", TranslateLanguage.ARABIC) ?: TranslateLanguage.ARABIC,
            auto = p.getBoolean("auto", true),
            intervalMs = p.getLong("interval", 2500L),
            coverAlpha = p.getInt("cover", 92),
            bubbleAlpha = p.getInt("bubble", 100)
        )
    }

    fun save(c: Context, s: AppSettings) {
        c.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString("src", s.src)
            .putString("tgt", s.tgt)
            .putBoolean("auto", s.auto)
            .putLong("interval", s.intervalMs)
            .putInt("cover", s.coverAlpha)
            .putInt("bubble", s.bubbleAlpha)
            .apply()
    }
}
