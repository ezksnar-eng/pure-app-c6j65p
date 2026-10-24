package com.pure.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import kotlin.math.max

/** طبقة كاملة الشاشة ترسم الترجمة فوق النص الأصلي. لا تستقبل اللمس. */
class TranslationLayer(ctx: Context) : View(ctx) {

    private class Op(val box: RectF, val layout: StaticLayout, val bg: Int)

    private var ops: List<Op> = emptyList()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    var coverAlpha = 235 // 0..255

    fun hasContent() = ops.isNotEmpty()

    fun clear() {
        ops = emptyList()
        invalidate()
    }

    fun setItems(list: List<Item>, screenW: Int, screenH: Int) {
        val d = resources.displayMetrics.density
        val maxSize = 20f * d
        val minSize = 9f * d
        val newOps = ArrayList<Op>()

        for (item in list) {
            val r = item.rect
            val minW = 56f * d
            val width = max(r.width() + 10f, minW).coerceAtMost(screenW - 16f)
            val innerW = (width - 12f).toInt().coerceAtLeast(40)

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                color = item.fg
            }
            var size = maxSize
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, innerW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setIncludePad(false)
                    .build()
                if (layout.height <= r.height() + 6f || size <= minSize) break
                size -= d
            }

            val boxH = max(r.height() + 6f, layout.height + 8f)
            val left = (r.centerX() - width / 2f).coerceIn(4f, max(4f, screenW - width - 4f))
            var top = r.top - 3f
            if (top + boxH > screenH) top = max(0f, screenH - boxH)
            newOps.add(Op(RectF(left, top, left + width, top + boxH), layout, item.bg))
        }
        ops = newOps
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        for (op in ops) {
            bgPaint.color = (coverAlpha shl 24) or (op.bg and 0xFFFFFF)
            canvas.drawRoundRect(op.box, 12f, 12f, bgPaint)
            canvas.save()
            canvas.translate(op.box.left + 6f, op.box.top + (op.box.height() - op.layout.height) / 2f)
            op.layout.draw(canvas)
            canvas.restore()
        }
    }
}
