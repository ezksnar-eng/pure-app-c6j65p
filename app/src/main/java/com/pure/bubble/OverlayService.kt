package com.pure.bubble

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Icon
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * فكرة الشغل (بدون وميض وبدون ما الترجمة "تلاحقك"):
 *  1) كل ~110ms ناخذ بصمة خفيفة من الشاشة ونقارنها بالسابقة (نتجاهل مناطق صناديقنا).
 *  2) إذا الصفحة انزاحت (تمرير) ننزّل الصناديق نفس المسافة فوراً — تبقى ملزوقة بالنص.
 *  3) إذا تغير المحتوى كلياً نمسح الصناديق فوراً.
 *  4) لما الشاشة تثبت: نرجّع لون/شكل الأصل تحت صناديقنا من آخر صورة نظيفة (بدون إخفاء الطبقة)،
 *     نقرأ النص، نترجم، ونبدّل كل الصناديق دفعة وحدة.
 *  5) فقط إذا الشاشة بلا ملامح نقدر نتتبع عليها (صفحة نص أبيض مثلاً) نخفي الطبقة لحظة للتأكد.
 */
class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.pure.bubble.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "pure_bubble_channel"
        private const val NOTIF_ID = 77
        private const val PINK = 0xFFD94F84.toInt()

        @Volatile
        var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var projection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var reader: ImageReader? = null
    private var vd: VirtualDisplay? = null
    private var screenW = 0
    private var screenH = 0
    private var dpi = 0
    private var frameCounter = 0
    private var held: Image? = null

    private var layer: TranslationLayer? = null
    private var layerLp: WindowManager.LayoutParams? = null
    private var bubble: ImageView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var handle: View? = null
    private var handleLp: WindowManager.LayoutParams? = null
    private var menu: LinearLayout? = null
    private var menuLp: WindowManager.LayoutParams? = null

    private val frame = Rect()          // الإطار العائم (إحداثيات الشاشة)

    private var loopJob: Job? = null
    private var paused = false
    private var busy = false
    private var epoch = 0
    private var dirty = true
    private var dirtySince = 0L
    private var stableSince = 0L
    private var lastPeek = 0L
    private var prevSig: Sig? = null
    private var cleanSig: Sig? = null
    private val boxHistory = ArrayDeque<List<Rect>>()
    private var totalShift = 0
    private var clean: Bitmap? = null
    private var cleanShift = 0

    private lateinit var engine: Engine
    private lateinit var hub: Hub
    private lateinit var settings: AppSettings

    private val hideMenuRunnable = Runnable { hideMenu() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_NOT_STICKY

        startForegroundNotification()

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent?.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(EXTRA_DATA)
        }
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        settings = Prefs.load(this)
        engine = Engine()
        hub = Hub(settings)
        hub.onError = { m -> toast(m) }

        try {
            setupCapture(code, data)
            addLayer()
            initFrame()
            addBubble()
            applyModeViews()
        } catch (e: Exception) {
            toast("تعذر بدء الترجمة: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        startLoop()
        toast(if (settings.mode == 1) "حرّك الإطار فوق النص اللي تريد تترجمه" else "الترجمة التلقائية شغالة")
        return START_NOT_STICKY
    }

    // ================================================================= capture

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
    }

    private fun newReader(): ImageReader {
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ frameCounter++ }, main)
        return r
    }

    private fun setupCapture(code: Int, data: Intent) {
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val p = mpm.getMediaProjection(code, data)
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                main.post { stopSelf() }
            }
        }
        p.registerCallback(cb, main)
        projection = p
        projectionCallback = cb

        val dm = realMetrics()
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        dpi = dm.densityDpi
        val r = newReader()
        reader = r
        vd = p.createVirtualDisplay(
            "pure-capture", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    /** لو دارت الشاشة نعيد ضبط الالتقاط. */
    private fun ensureSize() {
        val dm = realMetrics()
        if (dm.widthPixels == screenW && dm.heightPixels == screenH) return
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        dpi = dm.densityDpi
        runCatching { held?.close() }
        held = null
        reader?.close()
        val r = newReader()
        reader = r
        vd?.resize(screenW, screenH, dpi)
        vd?.surface = r.surface
        layerLp?.let {
            it.width = screenW
            it.height = screenH
            layer?.let { v -> runCatching { wm.updateViewLayout(v, it) } }
        }
        clampFrame()
        applyModeViews()
    }

    /** يسحب آخر صورة جديدة (إذا فيه). نحتفظ بآخر صورة مفتوحة حتى نقدر نترجم حتى لو الشاشة ثابتة. */
    private fun pullFrame(): Boolean {
        val r = reader ?: return false
        val n: Image? = try {
            r.acquireLatestImage()
        } catch (e: Exception) {
            null
        }
        if (n == null) return false
        runCatching { held?.close() }
        held = n
        return true
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val bmp = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        plane.buffer.rewind()
        bmp.copyPixelsFromBuffer(plane.buffer)
        return bmp   // قابل للتعديل (نستعمله لإرجاع الأصل تحت الصناديق)
    }

    // ================================================================= area / frame

    private fun areaRect(): Rect {
        if (settings.mode != 1) return Rect(0, 0, screenW, screenH)
        val i = dp(3)
        return Rect(
            frame.left + i, frame.top + i,
            max(frame.left + i + 8, frame.right - i), max(frame.top + i + 8, frame.bottom - i)
        )
    }

    private fun initFrame() {
        if (settings.fr > settings.fl && settings.fb > settings.ft && settings.fl >= 0) {
            frame.set(settings.fl, settings.ft, settings.fr, settings.fb)
        } else {
            val w = min((screenW * 0.62f).toInt(), dp(240))
            val h = dp(72)
            val l = (screenW - w) / 2
            val t = (screenH * 0.68f).toInt()
            frame.set(l, t, l + w, t + h)
        }
        clampFrame()
    }

    private fun clampFrame() {
        val minW = dp(44)
        val minH = dp(32)
        val w = max(minW, min(frame.width(), screenW))
        val h = max(minH, min(frame.height(), screenH))
        val l = frame.left.coerceIn(0, max(0, screenW - w))
        val t = frame.top.coerceIn(0, max(0, screenH - h))
        frame.set(l, t, l + w, t + h)
    }

    private fun saveFrame() {
        settings = settings.copy(fl = frame.left, ft = frame.top, fr = frame.right, fb = frame.bottom)
        Prefs.save(this, settings)
    }

    private fun bubbleSize() = dp(settings.bubbleDp)

    private fun placeFrameViews() {
        val l = layer ?: return
        l.setFrame(Rect(frame))
        val b = bubble
        val blp = bubbleLp
        if (b != null && blp != null) {
            val s = bubbleSize()
            blp.x = (frame.left - s / 2).coerceIn(0, max(0, screenW - s))
            blp.y = (frame.top - s / 2).coerceIn(0, max(0, screenH - s))
            runCatching { wm.updateViewLayout(b, blp) }
        }
        val h = handle
        val hlp = handleLp
        if (h != null && hlp != null) {
            val s = dp(26)
            hlp.x = (frame.right - s / 2).coerceIn(0, max(0, screenW - s))
            hlp.y = (frame.bottom - s / 2).coerceIn(0, max(0, screenH - s))
            runCatching { wm.updateViewLayout(h, hlp) }
        }
    }

    /** يطبّق وضع الشاشة الكاملة أو الإطار العائم على النوافذ. */
    private fun applyModeViews() {
        val l = layer ?: return
        if (settings.mode == 1) {
            clampFrame()
            addHandle()
            placeFrameViews()
        } else {
            l.setFrame(null)
            removeHandle()
        }
        dropAll()
        prevSig = null
        markDirty(SystemClock.uptimeMillis())
    }

    // ================================================================= views

    private fun addLayer() {
        val v = TranslationLayer(this)
        v.accent = PINK
        val lp = WindowManager.LayoutParams(
            screenW, screenH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
            if (Build.VERSION.SDK_INT >= 28) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        wm.addView(v, lp)
        layer = v
        layerLp = lp
    }

    private fun touchableParams(size: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        size, size,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        this.x = x
        this.y = y
    }

    private fun addBubble() {
        val size = bubbleSize()
        val b = ImageView(this).apply {
            setImageResource(R.drawable.bubble_icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = settings.bubbleAlpha / 100f
            elevation = dp(3).toFloat()
        }
        bubble = b
        val lp = touchableParams(size, screenW - size - dp(10), screenH / 3)
        bubbleLp = lp

        b.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            val startFrame = Rect()

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        startFrame.set(frame)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (!moved && hypot(dx, dy) > dp(6)) {
                            moved = true
                            hideMenu()
                            if (settings.mode == 1) dropAll()
                        }
                        if (moved) {
                            if (settings.mode == 1) {
                                val w = startFrame.width()
                                val h = startFrame.height()
                                val nl = (startFrame.left + dx).toInt().coerceIn(0, max(0, screenW - w))
                                val nt = (startFrame.top + dy).toInt().coerceIn(0, max(0, screenH - h))
                                frame.set(nl, nt, nl + w, nt + h)
                                placeFrameViews()
                            } else {
                                lp.x = (startX + dx).toInt().coerceIn(0, max(0, screenW - lp.width))
                                lp.y = (startY + dy).toInt().coerceIn(0, max(0, screenH - lp.height))
                                runCatching { wm.updateViewLayout(b, lp) }
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            toggleMenu()
                        } else if (settings.mode == 1) {
                            saveFrame()
                            frameChanged()
                        }
                    }
                }
                return true
            }
        })
        wm.addView(b, lp)
    }

    private fun addHandle() {
        if (handle != null) return
        val s = dp(26)
        val v = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFFFFFF.toInt())
                setStroke(dp(2), PINK)
            }
        }
        val lp = touchableParams(s, frame.right - s / 2, frame.bottom - s / 2)
        handle = v
        handleLp = lp
        v.setOnTouchListener(object : View.OnTouchListener {
            var startR = 0
            var startB = 0
            var tx = 0f
            var ty = 0f
            var moved = false
            override fun onTouch(view: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startR = frame.right
                        startB = frame.bottom
                        tx = e.rawX
                        ty = e.rawY
                        moved = false
                        hideMenu()
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!moved) {
                            moved = true
                            dropAll()
                        }
                        frame.right = (startR + (e.rawX - tx)).toInt().coerceIn(frame.left + dp(44), screenW)
                        frame.bottom = (startB + (e.rawY - ty)).toInt().coerceIn(frame.top + dp(32), screenH)
                        placeFrameViews()
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) {
                            saveFrame()
                            frameChanged()
                        }
                    }
                }
                return true
            }
        })
        wm.addView(v, lp)
    }

    private fun removeHandle() {
        handle?.let { runCatching { wm.removeView(it) } }
        handle = null
        handleLp = null
    }

    private fun frameChanged() {
        prevSig = null
        markDirty(SystemClock.uptimeMillis())
    }

    // ---- القائمة الصغيرة (تظهر عند الضغط على الفقاعة)

    private fun toggleMenu() {
        if (menu != null) hideMenu() else showMenu()
    }

    private fun menuButton(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label
        textSize = 17f
        gravity = Gravity.CENTER
        setTextColor(PINK)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener {
            onClick()
            main.removeCallbacks(hideMenuRunnable)
            main.postDelayed(hideMenuRunnable, 4000)
        }
    }

    private fun showMenu() {
        val b = bubble ?: return
        val blp = bubbleLp ?: return
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(Color.WHITE)
                setStroke(dp(2), PINK)
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            elevation = dp(6).toFloat()
        }
        row.addView(menuButton(if (paused) "▶" else "⏸") {
            paused = !paused
            if (paused) {
                dropAll()
            } else {
                prevSig = null
                markDirty(SystemClock.uptimeMillis())
            }
            hideMenu()
        })
        row.addView(menuButton(if (settings.mode == 1) "⛶" else "▭") {
            settings = settings.copy(mode = 1 - settings.mode)
            Prefs.save(this, settings)
            hideMenu()
            applyModeViews()
            toast(if (settings.mode == 1) "وضع الإطار العائم" else "وضع الشاشة كاملة")
        })
        row.addView(menuButton("⚙") {
            hideMenu()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })
        row.addView(menuButton("✕") { stopSelf() })

        val menuW = dp(40) * 4 + dp(8)
        val s = bubbleSize()
        val onRight = blp.x + s + dp(6) + menuW <= screenW
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (onRight) blp.x + s + dp(6) else max(0, blp.x - menuW - dp(6))
            y = (blp.y + s / 2 - dp(22)).coerceIn(0, max(0, screenH - dp(48)))
        }
        menu = row
        menuLp = lp
        wm.addView(row, lp)
        main.removeCallbacks(hideMenuRunnable)
        main.postDelayed(hideMenuRunnable, 4000)
    }

    private fun hideMenu() {
        main.removeCallbacks(hideMenuRunnable)
        menu?.let { runCatching { wm.removeView(it) } }
        menu = null
        menuLp = null
    }

    private fun excludeRects(): List<Rect> {
        val out = ArrayList<Rect>()
        val pad = dp(4)
        bubbleLp?.let { out.add(Rect(it.x - pad, it.y - pad, it.x + it.width + pad, it.y + it.height + pad)) }
        handleLp?.let { out.add(Rect(it.x - pad, it.y - pad, it.x + it.width + pad, it.y + it.height + pad)) }
        val m = menu
        val mlp = menuLp
        if (m != null && mlp != null) {
            val w = if (m.width > 0) m.width else dp(168)
            val h = if (m.height > 0) m.height else dp(44)
            out.add(Rect(mlp.x - pad, mlp.y - pad, mlp.x + w + pad, mlp.y + h + pad))
        }
        return out
    }

    // ================================================================= main loop

    private fun startLoop() {
        loopJob?.cancel()
        loopJob = scope.launch {
            while (isActive) {
                delay(110)
                if (paused) continue
                try {
                    tick()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // تجاهل أخطاء لحظية؛ الحلقة تكمل
                }
            }
        }
    }

    private fun markDirty(now: Long) {
        if (!dirty) dirtySince = now
        dirty = true
        stableSince = now
    }

    /** يمسح كل الصناديق فوراً ويلغي أي ترجمة شغالة (نتيجتها صارت قديمة). */
    private fun dropAll() {
        epoch++
        layer?.clearItems()
        clean = null
        boxHistory.clear()
    }

    private fun pushHistory(boxes: List<Rect>) {
        boxHistory.addLast(boxes)
        while (boxHistory.size > 2) boxHistory.removeFirst()
    }

    private suspend fun tick() {
        ensureSize()
        val lyr = layer ?: return
        val now = SystemClock.uptimeMillis()
        val isNew = pullFrame()
        val img = held ?: return

        if (!isNew) {            // الشاشة ثابتة
            maybeRefresh(now)
            return
        }

        val area = areaRect()
        val cur = Scroll.makeSig(img, area)
        val prev = prevSig
        prevSig = cur
        if (prev == null) {
            markDirty(now)
            return
        }

        val boxes = lyr.boxes()
        val rects = ArrayList<Rect>(boxes)
        for (h in boxHistory) rects.addAll(h)
        rects.addAll(excludeRects())
        val mask = Scroll.makeMask(cur, rects)
        val c = Scroll.compare(prev, cur, mask, boxes.isNotEmpty())

        when (c.kind) {
            Cmp.SHIFT -> {
                lyr.shiftY(c.dy, areaRect())
                totalShift += c.dy
                markDirty(now)
            }
            Cmp.CHANGED -> {
                dropAll()
                markDirty(now)
            }
            Cmp.UNRELIABLE -> {
                if (!busy && boxes.isNotEmpty() && now - lastPeek > 1200) {
                    lastPeek = now
                    busy = true
                    scope.launch { peek() }
                }
            }
            else -> {}
        }
        pushHistory(boxes)
        maybeRefresh(now)
    }

    private fun maybeRefresh(now: Long) {
        if (busy || !dirty) return
        if (now - stableSince < settings.settleMs && now - dirtySince < 1500) return
        val img = held ?: return
        val lyr = layer ?: return

        val boxes = lyr.boxes()
        val cl = clean
        if (boxes.isNotEmpty() && cl == null) {
            // عندنا صناديق بس بدون صورة نظيفة نرجع منها الأصل → نتأكد بلقطة نظيفة
            busy = true
            scope.launch { peek() }
            return
        }
        busy = true
        dirty = false
        val myEpoch = epoch
        val snap = totalShift
        val bmp = imageToBitmap(img)
        if (boxes.isEmpty()) {
            cleanSig = Scroll.makeSig(img, areaRect())
        } else if (cl != null) {
            compose(bmp, boxes, cl, totalShift - cleanShift)
        }
        scope.launch { doRefresh(bmp, myEpoch, snap) }
    }

    /** يرجّع الأصل تحت صناديقنا من آخر صورة نظيفة (مزاحة بمقدار التمرير) فتصير اللقطة "نظيفة". */
    private fun compose(frameBmp: Bitmap, boxes: List<Rect>, cl: Bitmap, delta: Int) {
        val canvas = Canvas(frameBmp)
        val bounds = Rect(0, 0, cl.width, cl.height)
        for (r in boxes) {
            val dst = Rect(r)
            if (!dst.intersect(0, 0, frameBmp.width, frameBmp.height)) continue
            val src = Rect(dst.left, dst.top - delta, dst.right, dst.bottom - delta)
            if (!src.intersect(bounds)) continue
            val ndst = Rect(src.left, src.top + delta, src.right, src.bottom + delta)
            canvas.drawBitmap(cl, src, ndst, null)
        }
    }

    private suspend fun doRefresh(bmp: Bitmap, myEpoch: Int, snap: Int) {
        try {
            val crop = if (settings.mode == 1) areaRect() else null
            val blocks = engine.detect(bmp, settings.src, crop, excludeRects())
            if (myEpoch != epoch) return
            val lyr = layer ?: return
            if (blocks.isEmpty()) {
                lyr.clearItems()
                clean = bmp
                cleanShift = snap
                return
            }
            val tr = hub.translate(blocks.map { it.text })
            if (myEpoch != epoch) return

            val items = ArrayList<Item>()
            for ((i, b) in blocks.withIndex()) {
                val t = tr[i] ?: continue
                if (t.isBlank() || sameText(t, b.text)) continue
                items.add(Item(b.rect, t, b.bg, b.fg, b.lines))
            }
            lyr.setItems(items, areaRect(), totalShift - snap)
            clean = bmp
            cleanShift = snap
            pushHistory(lyr.boxes())
            if (totalShift != snap) markDirty(SystemClock.uptimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            toast("خطأ بالترجمة: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    private fun sameText(a: String, b: String): Boolean =
        a.filter { !it.isWhitespace() }.equals(b.filter { !it.isWhitespace() }, ignoreCase = true)

    /** لقطة نظيفة سريعة (نخفي الطبقة ~فريمين) للأوضاع اللي ما نقدر نتتبع فيها بدون رؤية. */
    private suspend fun peek() {
        val lyr = layer
        if (lyr == null) {
            busy = false
            return
        }
        var handed = false
        try {
            val c0 = frameCounter
            lyr.alpha = 0f
            var waited = 0
            while (frameCounter < c0 + 2 && waited < 260) {
                delay(16)
                waited += 16
            }
            pullFrame()
            val img = held ?: return
            val sig = Scroll.makeSig(img, areaRect())
            val ref = cleanSig
            if (ref != null && lyr.isEmpty().not()) {
                val cmp = Scroll.compare(ref, sig, Scroll.emptyMask(sig), false)
                if (cmp.kind == Cmp.STATIC) return        // ما تغيّر شي → نرجّع الطبقة
            }
            val bmp = imageToBitmap(img)
            dropAll()
            cleanSig = sig
            lyr.alpha = 1f
            handed = true
            dirty = false
            doRefresh(bmp, epoch, totalShift)
        } finally {
            lyr.alpha = 1f
            if (!handed) busy = false
        }
    }

    // ================================================================= notification

    private fun startForegroundNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "بيور ترجمة", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, OverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val openIntent = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder: Notification.Builder
        if (Build.VERSION.SDK_INT >= 26) {
            builder = Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            builder = Notification.Builder(this)
        }
        val stopIcon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        val notif = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("بيور ترجمة شغال")
            .setContentText("اضغط هنا لفتح التطبيق")
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(stopIcon, "إيقاف", stopIntent).build())
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun toast(msg: String) {
        main.post { Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        running = false
        loopJob?.cancel()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        runCatching { menu?.let { wm.removeView(it) } }
        runCatching { handle?.let { wm.removeView(it) } }
        runCatching { bubble?.let { wm.removeView(it) } }
        runCatching { layer?.let { wm.removeView(it) } }
        menu = null
        handle = null
        bubble = null
        layer = null
        runCatching { held?.close() }
        held = null
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        runCatching { projection?.stop() }
        if (::engine.isInitialized) engine.close()
        if (::hub.isInitialized) hub.close()
        clean = null
        super.onDestroy()
    }
}
