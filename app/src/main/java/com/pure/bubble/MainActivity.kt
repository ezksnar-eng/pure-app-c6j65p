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
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val accent = 0xFF00639B.toInt()
    private val cardBg = 0xFFEAF4FF.toInt()

    private lateinit var srcSp: Spinner
    private lateinit var tgtSp: Spinner
    private lateinit var modeSp: Spinner
    private lateinit var engineSp: Spinner
    private lateinit var speedSp: Spinner
    private lateinit var presetSp: Spinner
    private lateinit var baseEt: EditText
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var llmBox: LinearLayout
    private lateinit var bubbleSizeSb: SeekBar
    private lateinit var bubbleAlphaSb: SeekBar
    private lateinit var startBtn: Button
    private var presetTouched = false

    private val modes = listOf("الشاشة كاملة", "إطار عائم صغير (تتحكم بمكانه وحجمه)")
    private val engines = listOf(
        "جوجل — مجاني بدون مفتاح (الافتراضي)",
        "ذكاء اصطناعي LLM — أعلى جودة (بمفتاحك)",
        "أوفلاين — ML Kit (بدون انترنت)"
    )
    private val speeds = listOf("سريع جداً" to 150L, "متوازن" to 300L, "هادئ" to 600L)

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
            val data = r.data
            if (r.resultCode == RESULT_OK && data != null) {
                val i = Intent(this, OverlayService::class.java)
                    .putExtra(OverlayService.EXTRA_CODE, r.resultCode)
                    .putExtra(OverlayService.EXTRA_DATA, data)
                ContextCompat.startForegroundService(this, i)
                startBtn.postDelayed({ refreshButton() }, 600)
                Toast.makeText(this, "تم. افتح التطبيق اللي تريد تترجمه.", Toast.LENGTH_LONG).show()
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

    private fun card(bg: Int = cardBg): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = roundBg(bg, 28)
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

    private fun edit(hint: String, value: String, password: Boolean = false): EditText =
        EditText(this).apply {
            this.hint = hint
            setText(value)
            textSize = 13f
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            if (password) inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
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
        root.addView(label("بيور ترجمة", 26f, true, accent))

        // ---- اللغات + بدء
        val langCard = card()
        srcSp = spinnerOf(
            Langs.sources.map { it.name },
            Langs.sources.indexOfFirst { it.code == s.src }.coerceAtLeast(0)
        )
        tgtSp = spinnerOf(
            Langs.targets.map { it.name },
            Langs.targets.indexOfFirst { it.code == s.tgt }.coerceAtLeast(0)
        )
        langCard.addView(label("من لغة", 14f, color = accent))
        langCard.addView(srcSp)
        langCard.addWithTop(label("إلى لغة", 14f, color = accent), 8)
        langCard.addView(tgtSp)
        startBtn = Button(this).apply {
            textSize = 20f
            setTextColor(Color.WHITE)
            isAllCaps = false
            background = roundBg(accent, 40)
            setOnClickListener { onStartClicked() }
        }
        val btnLp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64))
        btnLp.topMargin = dp(16)
        langCard.addView(startBtn, btnLp)
        root.addWithTop(langCard, 20)

        // ---- وضع العرض
        root.addWithTop(label("وضع العرض", 18f, true), 24)
        val modeCard = card(0xFFF7F8FC.toInt())
        modeSp = spinnerOf(modes, s.mode.coerceIn(0, 1))
        modeCard.addView(modeSp)
        modeCard.addWithTop(
            label("الإطار العائم: يترجم بس اللي تحته. اسحب الفقاعة تحرك الإطار، واسحب الدائرة بالزاوية تغيّر حجمه (يصغر لحد ما تريد).", 12f, color = Color.GRAY), 4
        )
        modeCard.addWithTop(label("حجم الفقاعة"), 14)
        bubbleSizeSb = SeekBar(this).apply { max = 44; progress = s.bubbleDp.coerceIn(28, 72) - 28 }
        modeCard.addView(bubbleSizeSb)
        modeCard.addWithTop(label("شفافية الفقاعة"), 10)
        bubbleAlphaSb = SeekBar(this).apply { max = 70; progress = s.bubbleAlpha.coerceIn(30, 100) - 30 }
        modeCard.addView(bubbleAlphaSb)
        root.addWithTop(modeCard, 12)

        // ---- محرك الترجمة
        root.addWithTop(label("محرك الترجمة", 18f, true), 24)
        val engCard = card(0xFFF7F8FC.toInt())
        engineSp = spinnerOf(engines, s.engine.coerceIn(0, 2))
        engCard.addView(engineSp)

        llmBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val presetIdx = Presets.list.indexOfFirst { it.base == s.llmBase }.let { if (it < 0) Presets.list.size - 1 else it }
        presetSp = spinnerOf(Presets.list.map { it.name }, presetIdx)
        baseEt = edit("رابط API", s.llmBase)
        keyEt = edit("مفتاح API (اتركه فاضي لـ Ollama)", s.llmKey, true)
        modelEt = edit("اسم الموديل", s.llmModel)
        llmBox.addWithTop(label("المزود", 13f, color = accent), 10)
        llmBox.addView(presetSp)
        llmBox.addView(baseEt)
        llmBox.addView(keyEt)
        llmBox.addView(modelEt)
        llmBox.addWithTop(
            label("الـ LLM يفهم السياق ويترجم كل شي بشكل طبيعي. إذا فشل، يرجع تلقائياً لجوجل.", 12f, color = Color.GRAY), 4
        )
        engCard.addView(llmBox)
        llmBox.visibility = if (s.engine == 1) View.VISIBLE else View.GONE

        engineSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                llmBox.visibility = if (pos == 1) View.VISIBLE else View.GONE
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        presetSp.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (!presetTouched) {
                    presetTouched = true
                    return
                }
                baseEt.setText(Presets.list[pos].base)
                modelEt.setText(Presets.list[pos].model)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }

        engCard.addWithTop(label("سرعة الاستجابة بعد ما تثبت الشاشة"), 14)
        speedSp = spinnerOf(
            speeds.map { it.first },
            speeds.indexOfFirst { it.second == s.settleMs }.let { if (it < 0) 1 else it }
        )
        engCard.addView(speedSp)
        root.addWithTop(engCard, 12)

        root.addWithTop(
            label(
                "طريقة الاستخدام:\n" +
                        "١) اختر اللغتين واضغط بدء ووافق على الأذونات.\n" +
                        "٢) افتح أي تطبيق: الترجمة تظهر فوق النص وتتحرك معه أثناء التمرير.\n" +
                        "• ضغطة على الفقاعة: قائمة (إيقاف مؤقت / تبديل الوضع / إعدادات / إغلاق).\n" +
                        "• أول تشغيل بوضع «أوفلاين» يحتاج انترنت لتنزيل النموذج مرة وحدة.",
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
        startBtn.background = roundBg(if (running) 0xFFB3261E.toInt() else accent, 40)
    }

    private fun saveSettings() {
        val old = Prefs.load(this)
        val s = old.copy(
            src = Langs.sources[srcSp.selectedItemPosition].code,
            tgt = Langs.targets[tgtSp.selectedItemPosition].code,
            settleMs = speeds[speedSp.selectedItemPosition].second,
            mode = modeSp.selectedItemPosition,
            bubbleDp = bubbleSizeSb.progress + 28,
            bubbleAlpha = bubbleAlphaSb.progress + 30,
            engine = engineSp.selectedItemPosition,
            llmBase = baseEt.text.toString().trim(),
            llmKey = keyEt.text.toString().trim(),
            llmModel = modelEt.text.toString().trim()
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
