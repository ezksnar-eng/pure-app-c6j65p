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
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.hypot

class OverlayService : Service() {

    companion object {
        const val ACTION_STOP = "com.pure.bubble.STOP"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL_ID = "pure_bubble_channel"
        private const val NOTIF_ID = 77

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

    private var bubble: TextView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var layer: TranslationLayer? = null
    private var layerLp: WindowManager.LayoutParams? = null

    private var autoJob: Job? = null
    private var paused = false
    private var busy = false
    private var lastHash = 0L
    private var lastFrame: Bitmap? = null
    private var modelToastShown = false

    private val engine = Engine()
    private lateinit var settings: AppSettings

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

        try {
            setupCapture(code, data)
            addLayer()
            addBubble()
        } catch (e: Exception) {
            toast("تعذر بدء الترجمة: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        running = true
        startAutoLoop()
        toast(if (settings.auto) "الترجمة التلقائية شغالة" else "اضغط الفقاعة لترجمة الشاشة")
        return START_NOT_STICKY
    }

    // ---------------------------------------------------------------- capture

    private fun realMetrics(): DisplayMetrics {
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        return dm
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
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        vd = p.createVirtualDisplay(
            "pure-capture", screenW, screenH, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface, null, null
        )
    }

    /** لو دارت الشاشة (تغير الحجم) نعيد ضبط الالتقاط بدون إنشاء VirtualDisplay جديد. */
    private fun ensureSize() {
        val dm = realMetrics()
        if (dm.widthPixels == screenW && dm.heightPixels == screenH) return
        screenW = dm.widthPixels
        screenH = dm.heightPixels
        dpi = dm.densityDpi
        reader?.close()
        val r = ImageReader.newInstance(screenW, screenH, PixelFormat.RGBA_8888, 2)
        reader = r
        vd?.resize(screenW, screenH, dpi)
        vd?.surface = r.surface
        lastFrame = null
        lastHash = 0L
        layerLp?.let {
            it.width = screenW
            it.height = screenH
            layer?.let { v -> runCatching { wm.updateViewLayout(v, it) } }
        }
    }

    private suspend fun captureFrame(): Bitmap? {
        ensureSize()
        repeat(14) {
            val img: Image? = try {
                reader?.acquireLatestImage()
            } catch (e: Exception) {
                null
            }
            if (img != null) {
                val bmp = imageToBitmap(img)
                img.close()
                lastFrame = bmp
                return bmp
            }
            delay(60)
        }
        return lastFrame
    }

    private fun imageToBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * img.width
        val full = Bitmap.createBitmap(
            img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888
        )
        full.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) return full
        val cropped = Bitmap.createBitmap(full, 0, 0, img.width, img.height)
        full.recycle()
        return cropped
    }

    private fun quickHash(b: Bitmap): Long {
        var h = 1125899906842597L
        val n = 48
        for (i in 0 until n) {
            val x = ((i + 0.5f) / n * b.width).toInt().coerceIn(0, b.width - 1)
            for (j in 0 until n) {
                val y = ((j + 0.5f) / n * b.height).toInt().coerceIn(0, b.height - 1)
                h = 31 * h + (b.getPixel(x, y) and 0xF8F8F8)
            }
        }
        return h
    }

    // ---------------------------------------------------------------- translate

    private suspend fun processOnce(force: Boolean) {
        if (busy) return
        val l = layer ?: return
        busy = true
        try {
            val hadContent = l.hasContent()
            if (hadContent) {
                l.visibility = View.INVISIBLE
                delay(220)
            }

            val frame = captureFrame()
            if (frame == null) {
                l.visibility = View.VISIBLE
                return
            }

            val hash = quickHash(frame)
            if (!force && hadContent && hash == lastHash) {
                l.visibility = View.VISIBLE
                return
            }
            lastHash = hash
            l.clear()
            l.visibility = View.VISIBLE

            if (!engine.isModelReady(settings.src, settings.tgt) && !modelToastShown) {
                modelToastShown = true
                toast("جاري تحميل نموذج الترجمة لأول مرة (يحتاج انترنت)…")
            }

            val items = engine.run(frame, settings.src, settings.tgt, bubbleRect())
            l.setItems(items, screenW, screenH)
        } catch (e: Exception) {
            toast("خطأ بالترجمة: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            busy = false
        }
    }

    private fun bubbleRect(): Rect? {
        val lp = bubbleLp ?: return null
        val b = bubble ?: return null
        val w = if (b.width > 0) b.width else lp.width
        val h = if (b.height > 0) b.height else lp.height
        return Rect(lp.x - 8, lp.y - 8, lp.x + w + 8, lp.y + h + 8)
    }

    private fun startAutoLoop() {
        autoJob?.cancel()
        autoJob = scope.launch {
            while (isActive) {
                delay(settings.intervalMs)
                if (settings.auto && !paused) processOnce(false)
            }
        }
    }

    // ---------------------------------------------------------------- overlay views

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun addLayer() {
        val v = TranslationLayer(this)
        v.coverAlpha = (settings.coverAlpha * 255 / 100).coerceIn(80, 255)
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

    private fun styleBubble() {
        val b = bubble ?: return
        val active = !(settings.auto && paused)
        b.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(if (active) 0xFF00639B.toInt() else 0xFF8A8F98.toInt())
            setStroke(dp(2), Color.WHITE)
        }
        b.text = if (settings.auto) (if (paused) "⏸" else "ت") else "ت"
    }

    private fun addBubble() {
        val size = dp(56)
        val b = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 22f
            alpha = settings.bubbleAlpha / 100f
            elevation = dp(6).toFloat()
        }
        bubble = b
        styleBubble()

        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenW - size - dp(12)
            y = screenH / 3
        }
        bubbleLp = lp

        b.setOnTouchListener(object : View.OnTouchListener {
            var startX = 0
            var startY = 0
            var touchX = 0f
            var touchY = 0f
            var moved = false
            var longFired = false
            val longRunnable = Runnable {
                if (!moved) {
                    longFired = true
                    stopSelf()
                }
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        startX = lp.x
                        startY = lp.y
                        touchX = e.rawX
                        touchY = e.rawY
                        moved = false
                        longFired = false
                        main.postDelayed(longRunnable, 1000)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - touchX
                        val dy = e.rawY - touchY
                        if (!moved && hypot(dx, dy) > dp(8)) {
                            moved = true
                            main.removeCallbacks(longRunnable)
                        }
                        if (moved) {
                            lp.x = (startX + dx).toInt().coerceIn(0, screenW - lp.width)
                            lp.y = (startY + dy).toInt().coerceIn(0, screenH - lp.height)
                            runCatching { wm.updateViewLayout(b, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        main.removeCallbacks(longRunnable)
                        if (!moved && !longFired) onBubbleTap()
                    }
                    MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longRunnable)
                }
                return true
            }
        })

        wm.addView(b, lp)
    }

    private fun onBubbleTap() {
        val l = layer ?: return
        if (settings.auto) {
            paused = !paused
            styleBubble()
            if (paused) {
                l.clear()
            } else {
                lastHash = 0L
                scope.launch { processOnce(true) }
            }
        } else {
            if (l.hasContent()) l.clear() else scope.launch { processOnce(true) }
        }
    }

    // ---------------------------------------------------------------- notification

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
        autoJob?.cancel()
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        runCatching { bubble?.let { wm.removeView(it) } }
        runCatching { layer?.let { wm.removeView(it) } }
        bubble = null
        layer = null
        runCatching { vd?.release() }
        runCatching { reader?.close() }
        projectionCallback?.let { cb -> runCatching { projection?.unregisterCallback(cb) } }
        runCatching { projection?.stop() }
        engine.close()
        lastFrame = null
        super.onDestroy()
    }
}
