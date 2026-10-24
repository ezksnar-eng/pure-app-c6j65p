package com.pure.bubble

import android.graphics.Rect
import android.media.Image
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** بصمة أعمدة من الشاشة (سطوع فقط). نستعملها نعرف إذا الصفحة انزاحت (تمرير) وبكم بكسل. */
class Sig(val area: Rect, val xs: IntArray, val cols: Array<ByteArray>) {
    val h: Int get() = area.height()
}

class Cmp(val kind: Int, val dy: Int) {
    companion object {
        const val STATIC = 0      // ما تغيّر شي
        const val SHIFT = 1       // الصفحة انزاحت dy بكسل (موجب = نزلت لتحت)
        const val CHANGED = 2     // تغيّر المحتوى كلياً
        const val UNRELIABLE = 3  // ما نقدر نحكم (الشاشة مغطاة أو فاضية بلا ملامح)
    }
}

object Scroll {
    private const val COLS = 14
    private const val PAD = 10
    private const val STATIC_ERR = 1.2f
    private const val GOOD_ERR = 1.5f
    private const val MATCH_ERR = 6f
    private const val FLAT_TEX = 2.0f

    fun makeSig(img: Image, area: Rect): Sig {
        val plane = img.planes[0]
        val buf = plane.buffer
        val ps = plane.pixelStride
        val rs = plane.rowStride
        val h = area.height()
        val w = area.width()
        val n = min(COLS, max(1, w / 3))
        val xs = IntArray(n) { k -> area.left + ((k + 0.5f) / n * w).toInt() }
        val cols = Array(n) { ByteArray(h) }
        val maxX = img.width - 1
        val maxY = img.height - 1
        for (k in 0 until n) {
            val x = xs[k].coerceIn(0, maxX)
            val col = cols[k]
            for (i in 0 until h) {
                val y = (area.top + i).coerceIn(0, maxY)
                val o = y * rs + x * ps
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                col[i] = ((r * 77 + g * 150 + b * 29) shr 8).toByte()
            }
        }
        return Sig(Rect(area), xs, cols)
    }

    /** يعلّم البكسلات المغطاة بصناديق الترجمة (ما نقارن عليها لأنها من رسمنا مو من الصفحة). */
    fun makeMask(sig: Sig, rects: List<Rect>): Array<BooleanArray> {
        val h = sig.h
        val m = Array(sig.xs.size) { BooleanArray(h) }
        for (k in sig.xs.indices) {
            val x = sig.xs[k]
            for (r in rects) {
                if (x < r.left - PAD || x > r.right + PAD) continue
                val a = max(0, r.top - PAD - sig.area.top)
                val b = min(h, r.bottom + PAD - sig.area.top)
                for (i in a until b) m[k][i] = true
            }
        }
        return m
    }

    fun emptyMask(sig: Sig): Array<BooleanArray> = Array(sig.xs.size) { BooleanArray(sig.h) }

    fun compare(prev: Sig, cur: Sig, mask: Array<BooleanArray>, hasOverlay: Boolean): Cmp {
        if (prev.area.width() != cur.area.width() || prev.area.height() != cur.area.height() ||
            prev.xs.size != cur.xs.size
        ) return Cmp(Cmp.CHANGED, 0)

        val h = cur.h
        val n = cur.xs.size
        var cnt = 0
        var sum = 0L
        var tex = 0L
        var texCnt = 0
        for (k in 0 until n) {
            val a = prev.cols[k]
            val b = cur.cols[k]
            val m = mask[k]
            for (i in 0 until h) {
                if (m[i]) continue
                sum += abs((a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
                cnt++
                if (i + 1 < h && !m[i + 1]) {
                    tex += abs((b[i + 1].toInt() and 0xFF) - (b[i].toInt() and 0xFF))
                    texCnt++
                }
            }
        }
        val total = n * h
        if (cnt < max(300, (total * 0.06f).toInt())) return Cmp(Cmp.UNRELIABLE, 0)

        val e0 = sum.toFloat() / cnt
        val texture = if (texCnt > 0) tex.toFloat() / texCnt else 0f
        if (e0 <= STATIC_ERR) {
            return if (hasOverlay && texture < FLAT_TEX) Cmp(Cmp.UNRELIABLE, 0) else Cmp(Cmp.STATIC, 0)
        }
        if (hasOverlay && texture < FLAT_TEX) return Cmp(Cmp.UNRELIABLE, 0)

        // بحث خشن (خطوة 3) من الأصغر للأكبر، بعدين تدقيق بكسل ببكسل
        val maxShift = (h * 0.75f).toInt()
        var bestDy = 0
        var bestErr = Float.MAX_VALUE
        var d = 1
        while (d <= maxShift) {
            for (s in 0..1) {
                val dy = if (s == 0) d else -d
                val e = errAt(prev, cur, mask, dy, 4)
                if (e < bestErr) {
                    bestErr = e
                    bestDy = dy
                }
            }
            if (bestErr < GOOD_ERR) break
            d += 3
        }
        if (bestErr == Float.MAX_VALUE) return Cmp(Cmp.CHANGED, 0)
        var fineDy = bestDy
        var fineErr = Float.MAX_VALUE
        for (dy in (bestDy - 3)..(bestDy + 3)) {
            val e = errAt(prev, cur, mask, dy, 1)
            if (e < fineErr) {
                fineErr = e
                fineDy = dy
            }
        }
        return if (fineErr < MATCH_ERR && fineErr < e0 * 0.6f && fineDy != 0) {
            Cmp(Cmp.SHIFT, fineDy)
        } else {
            Cmp(Cmp.CHANGED, 0)
        }
    }

    /** متوسط الفرق بين صف i من السابق وصف i+dy من الحالي. */
    private fun errAt(prev: Sig, cur: Sig, mask: Array<BooleanArray>, dy: Int, step: Int): Float {
        val h = cur.h
        val i0 = max(0, -dy)
        val i1 = min(h, h - dy)
        if (i1 - i0 < 40) return Float.MAX_VALUE
        var cnt = 0
        var sum = 0L
        for (k in cur.xs.indices) {
            val a = prev.cols[k]
            val b = cur.cols[k]
            val m = mask[k]
            var i = i0
            while (i < i1) {
                if (!m[i] && !m[i + dy]) {
                    sum += abs((a[i].toInt() and 0xFF) - (b[i + dy].toInt() and 0xFF))
                    cnt++
                }
                i += step
            }
        }
        if (cnt < 150) return Float.MAX_VALUE
        return sum.toFloat() / cnt
    }
}
