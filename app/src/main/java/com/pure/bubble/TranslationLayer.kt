package com.pure.bubble

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * طبقة كاملة الشاشة ما تستقبل لمس. ترسم صناديق التبييض + الترجمة فوق النص الأصلي،
 * وبوضع الإطار ترسم حدود الإطار وتقص كل شي برا الإطار.
 * الصناديق تنزاح مع التمرير (shiftY) فتبقى ملزوقة بالنص بدون ما تختفي.
 */
class TranslationLayer(ctx: Context) : View(ctx) {

    private class Op(val box: RectF, val layout: StaticLayout, val bg: Int)

    private val ops = ArrayList<Op>()
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var frame: Rect? = null
    var accent: Int = 0xFFD94F84.toInt()

    fun isEmpty() = ops.isEmpty()

    fun boxes(): List<Rect> = ops.map {
        Rect(it.box.left.toInt(), it.box.top.toInt(), it.box.right.toInt() + 1, it.box.bottom.toInt() + 1)
    }

    fun clearItems() {
        ops.clear()
        invalidate()
    }

    fun setFrame(r: Rect?) {
        frame = if (r == null) null else Rect(r)
        invalidate()
    }

    /** ينزّل/يطلّع كل الصناديق مع التمرير، ويحذف اللي طلعت كلها برا المنطقة. */
    fun shiftY(dy: Int, clip: Rect) {
        for (op in ops) op.box.offset(0f, dy.toFloat())
        ops.removeAll { it.box.bottom < clip.top || it.box.top > clip.bottom }
        invalidate()
    }

    /** يستبدل كل الصناديق دفعة وحدة (بدون وميض). offY = كم تحرك التمرير أثناء ما كانت الترجمة تشتغل. */
    fun setItems(list: List<Item>, bounds: Rect, offY: Int) {
        val d = resources.displayMetrics.density
        val newOps = ArrayList<Op>()
        val minSize = 9f * d
        val maxSize = 26f * d

        for (item in list) {
            val r = Rect(item.rect)
            r.offset(0, offY)
            val padH = max(4f * d, r.height() * 0.12f / max(1, item.lines))
            val padV = max(3f * d, 0.1f * r.height() / max(1, item.lines))
            val minW = 44f * d
            val boxW = min(max(r.width() + 2 * padH, minW), bounds.width().toFloat())
            val innerW = (boxW - 2 * padH).toInt().coerceAtLeast(24)

            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = Typeface.DEFAULT_BOLD
                color = item.fg
            }
            var size = (r.height().toFloat() / max(1, item.lines) * 0.82f).coerceIn(minSize, maxSize)
            var layout: StaticLayout
            while (true) {
                tp.textSize = size
                layout = StaticLayout.Builder.obtain(item.text, 0, item.text.length, tp, innerW)
                    .setAlignment(Layout.Alignment.ALIGN_CENTER)
                    .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_RTL)
                    .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                    .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                    .setIncludePad(false)
                    .build()
                if (layout.height <= r.height() + 2 * padV || size <= minSize) break
                size -= d * 0.75f
            }

            val boxH = max(r.height() + 2 * padV, layout.height + 2 * padV)
            var left = r.exactCenterX() - boxW / 2f
            var top = r.exactCenterY() - boxH / 2f
            left = left.coerceIn(bounds.left.toFloat(), max(bounds.left.toFloat(), bounds.right - boxW))
            top = top.coerceIn(bounds.top.toFloat(), max(bounds.top.toFloat(), bounds.bottom - boxH))
            newOps.add(Op(RectF(left, top, left + boxW, top + boxH), layout, item.bg))
        }
        ops.clear()
        ops.addAll(newOps)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val f = frame
        if (f != null) {
            canvas.save()
            canvas.clipRect(f)
        }
        for (op in ops) {
            bgPaint.color = 0xFF000000.toInt() or (op.bg and 0xFFFFFF)   // تبييض كامل: ما يبين النص الأصلي
            val rad = min(op.box.height(), op.box.width()) * 0.18f
            canvas.drawRoundRect(op.box, rad, rad, bgPaint)
            canvas.save()
            canvas.translate(
                op.box.left + (op.box.width() - op.layout.width) / 2f,
                op.box.top + (op.box.height() - op.layout.height) / 2f
            )
            op.layout.draw(canvas)
            canvas.restore()
        }
        if (f != null) {
            canvas.restore()
            val d = resources.displayMetrics.density
            borderPaint.color = accent
            borderPaint.strokeWidth = 2f * d
            val rf = RectF(f)
            rf.inset(d, d)
            canvas.drawRoundRect(rf, 6f * d, 6f * d, borderPaint)
            cornerPaint.color = accent
            canvas.drawCircle(f.left + d, f.top + d, 4f * d, cornerPaint)
        }
    }
}
