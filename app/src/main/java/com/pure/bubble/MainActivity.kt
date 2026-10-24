package com.pure.bubble

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val blue = 0xFF00639B.toInt()
    private val cardBg = 0xFFEAF4FF.toInt()

    private lateinit var srcSp: Spinner
    private lateinit var tgtSp: Spinner
    private lateinit var autoSw: Switch
    private lateinit var intervalSp: Spinner
    private lateinit var coverSb: SeekBar
    private lateinit var bubbleSb: SeekBar
    private lateinit var startBtn: Button

    private val intervals = listOf(
        "ثانية ونصف" to 1500L,
        "ثانيتين ونصف" to 2500L,
        "4 ثواني" to 4000L
    )

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, r.resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                startBtn.postDelayed({ refreshButton() }, 600)
                Toast.makeText(
                    this,
                    "تم. افتح التطبيق اللي تريد تترجمه وراح تلاقي الفقاعة عائمة.",
                    Toast.LENGTH_LONG
                ).show()
                moveTaskToBack(true)
            } else {
                Toast.makeText(this, "لازم توافق على تسجيل الشاشة حتى تشتغل الترجمة", Toast.LENGTH_LONG).show()
            }
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestProjection()
        }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun roundBg(color: Int, radiusDp: Int) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun label(text: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.parseColor("#1B1B1F")) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(cardBg, 28)
        setPadding(dp(18), dp(16), dp(18), dp(16))
    }

    private fun LinearLayout.addWithTop(v: View, topDp: Int = 12) {
        val lp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        lp.topMargin = dp(topDp)
        addView(v, lp)
    }

    private fun spinnerOf(items: List<String>, selected: Int): Spinner =
        Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity, android.R.layout.simple_spinner_dropdown_item, items
            )
            setSelection(selected)
        }

    private fun seek(min: Int, value: Int): SeekBar = SeekBar(this).apply {
        max = 100 - min
        progress = value - min
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = Color.WHITE
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR

        val s = Prefs.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.WHITE)
        }

        root.addView(label("بيور ترجمة", 26f, true))

        // ---- بطاقة اللغات + بدء
        val langCard = card()
        srcSp = spinnerOf(
            Langs.sources.map { it.name },
            Langs.sources.indexOfFirst { it.code == s.src }.coerceAtLeast(0)
        )
        tgtSp = spinnerOf(
            Langs.targets.map { it.name },
            Langs.targets.indexOfFirst { it.code == s.tgt }.coerceAtLeast(0)
        )
        langCard.addView(label("من لغة", 14f, color = blue))
        langCard.addView(srcSp)
        langCard.addWithTop(label("إلى لغة", 14f, color = blue), 8)
        langCard.addView(tgtSp)

        startBtn = Button(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundBg(blue, 40)
            setOnClickListener { onStartClicked() }
        }
        val btnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        btnLp.topMargin = dp(16)
        langCard.addView(startBtn, btnLp)
        root.addWithTop(langCard, 20)

        // ---- خيارات الترجمة
        root.addWithTop(label("خيارات الترجمة", 18f, true), 24)
        val optCard = card().apply { background = roundBg(0xFFF7F8FC.toInt(), 28) }

        autoSw = Switch(this).apply {
            isChecked = s.auto
            text = "الترجمة التلقائية"
            textSize = 16f
        }
        optCard.addView(autoSw)
        optCard.addWithTop(
            label("تترجم الشاشة كل كم ثانية وتغيّر الترجمة تلقائياً مع التمرير.", 12f, color = Color.GRAY), 2
        )

        optCard.addWithTop(label("سرعة التحديث التلقائي"), 14)
        intervalSp = spinnerOf(
            intervals.map { it.first },
            intervals.indexOfFirst { it.second == s.intervalMs }.let { if (it < 0) 1 else it }
        )
        optCard.addView(intervalSp)

        optCard.addWithTop(label("شفافية صندوق الترجمة"), 14)
        coverSb = seek(30, s.coverAlpha)
        optCard.addView(coverSb)

        optCard.addWithTop(label("شفافية الفقاعة العائمة"), 14)
        bubbleSb = seek(30, s.bubbleAlpha)
        optCard.addView(bubbleSb)
        root.addWithTop(optCard, 12)

        // ---- شرح
        root.addWithTop(
            label(
                "طريقة الاستخدام:\n" +
                        "١) اختر اللغتين واضغط بدء.\n" +
                        "٢) وافق على إذن الظهور فوق التطبيقات وتسجيل الشاشة.\n" +
                        "٣) افتح أي تطبيق أو صفحة، وراح تظهر الترجمة فوق النص.\n" +
                        "• ضغطة على الفقاعة: إيقاف مؤقت / تشغيل (أو ترجمة مرة وحدة بدون تلقائي).\n" +
                        "• ضغطة طويلة على الفقاعة: إغلاق.\n" +
                        "• أول مرة لكل لغة يحتاج انترنت لتحميل نموذج الترجمة، وبعدها تشتغل بدون اتصال.",
                13f, color = Color.DKGRAY
            ), 20
        )

        setContentView(ScrollView(this).apply { addView(root) })
        refreshButton()
    }

    override fun onResume() {
        super.onResume()
        refreshButton()
    }

    private fun refreshButton() {
        val running = OverlayService.running
        startBtn.text = if (running) "إيقاف" else "بدء"
        startBtn.background = roundBg(if (running) 0xFFB3261E.toInt() else blue, 40)
    }

    private fun saveSettings() {
        val s = AppSettings(
            src = Langs.sources[srcSp.selectedItemPosition].code,
            tgt = Langs.targets[tgtSp.selectedItemPosition].code,
            auto = autoSw.isChecked,
            intervalMs = intervals[intervalSp.selectedItemPosition].second,
            coverAlpha = coverSb.progress + 30,
            bubbleAlpha = bubbleSb.progress + 30
        )
        Prefs.save(this, s)
    }

    private fun onStartClicked() {
        if (OverlayService.running) {
            startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_STOP))
            startBtn.postDelayed({ refreshButton() }, 500)
            return
        }

        saveSettings()

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "فعّل «الظهور فوق التطبيقات» ثم ارجع واضغط بدء", Toast.LENGTH_LONG).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
            return
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        requestProjection()
    }

    private fun requestProjection() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
