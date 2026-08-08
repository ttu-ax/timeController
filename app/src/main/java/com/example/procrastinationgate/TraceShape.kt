package com.example.procrastinationgate

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 门上的描摹轨迹形状。每种形状负责生成引导 Path 与有序检查点，
 * 用户必须从起点沿轨迹按顺序经过所有检查点才能推开。
 */
enum class TraceShape(val label: String) {
    S("S 形"),
    ZIGZAG("之字形"),
    SPIRAL("螺旋形"),
    CIRCLE("圆形"),
    HEART("心形"),
    STAR("五角星"),
    LIGHTNING("闪电"),
    EIGHT("数字 8");

    /** 生成轨迹 Path,并按描摹顺序填充检查点到 [out]。 */
    fun buildPath(w: Float, h: Float, out: MutableList<Pair<Float, Float>>): Path {
        val path = Path()
        out.clear()
        when (this) {
            S -> buildS(w, h, path, out)
            ZIGZAG -> buildZigzag(w, h, path, out)
            SPIRAL -> buildSpiral(w, h, path, out)
            CIRCLE -> buildCircle(w, h, path, out)
            HEART -> buildHeart(w, h, path, out)
            STAR -> buildStar(w, h, path, out)
            LIGHTNING -> buildLightning(w, h, path, out)
            EIGHT -> buildEight(w, h, path, out)
        }
        return path
    }

    private fun buildS(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val left = w * 0.22f
        val right = w * 0.78f
        val top = h * 0.18f
        val bottom = h * 0.82f
        path.moveTo(right, top)
        path.cubicTo(left, top, left, h * 0.5f, w * 0.5f, h * 0.5f)
        path.cubicTo(right, h * 0.5f, right, bottom, left, bottom)
        out += right to top
        out += w * 0.50f to h * 0.22f
        out += left to h * 0.38f
        out += w * 0.50f to h * 0.50f
        out += right to h * 0.62f
        out += w * 0.50f to h * 0.78f
        out += left to bottom
    }

    private fun buildZigzag(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val left = w * 0.22f
        val right = w * 0.78f
        val top = h * 0.18f
        val bottom = h * 0.82f
        path.moveTo(left, top)
        path.lineTo(right, top)
        path.lineTo(left, bottom)
        path.lineTo(right, bottom)
        out += left to top
        out += right to top
        out += w * 0.56f to h * 0.46f
        out += left to bottom
        out += right to bottom
    }

    private fun buildSpiral(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxR = min(w, h) * 0.38f
        val totalAngle = 2.5f * 2f * PI.toFloat()
        val steps = 72
        path.moveTo(cx, cy - maxR)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val angle = t * totalAngle - PI.toFloat() / 2f
            val radius = maxR * (1f - t)
            path.lineTo(cx + radius * cos(angle), cy + radius * sin(angle))
        }
        out += cx to (cy - maxR)
        out += spiralPoint(cx, cy, maxR, totalAngle, 0.18f)
        out += spiralPoint(cx, cy, maxR, totalAngle, 0.36f)
        out += spiralPoint(cx, cy, maxR, totalAngle, 0.54f)
        out += spiralPoint(cx, cy, maxR, totalAngle, 0.72f)
        out += spiralPoint(cx, cy, maxR, totalAngle, 0.88f)
        out += cx to cy
    }

    private fun spiralPoint(
        cx: Float, cy: Float, maxR: Float, totalAngle: Float, t: Float
    ): Pair<Float, Float> {
        val angle = t * totalAngle - PI.toFloat() / 2f
        val radius = maxR * (1f - t)
        return cx + radius * cos(angle) to cy + radius * sin(angle)
    }

    private fun buildCircle(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = min(w, h) * 0.36f
        path.addCircle(cx, cy, r, Path.Direction.CW)
        for (i in 0 until 8) {
            val angle = -PI.toFloat() / 2f + i * (2f * PI.toFloat() / 8f)
            out += cx + r * cos(angle) to cy + r * sin(angle)
        }
    }

    private fun buildHeart(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val cx = w * 0.5f
        val cy = h * 0.58f
        val s = min(w, h) * 0.032f
        val steps = 72
        for (i in 0..steps) {
            val a = i.toFloat() / steps * 2f * PI.toFloat()
            val x = cx + 16f * sin(a) * sin(a) * sin(a) * s
            val y = cy - (13f * cos(a) - 5f * cos(2f * a) - 2f * cos(3f * a) - cos(4f * a)) * s
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        for (i in 0 until 7) {
            val a = i / 6f * 2f * PI.toFloat()
            out += cx + 16f * sin(a) * sin(a) * sin(a) * s to
                cy - (13f * cos(a) - 5f * cos(2f * a) - 2f * cos(3f * a) - cos(4f * a)) * s
        }
    }

    private fun buildStar(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val r = min(w, h) * 0.38f
        val ri = r * 0.45f
        val step = 2f * PI.toFloat() / 5f
        for (i in 0 until 5) {
            val outerAngle = -PI.toFloat() / 2f + i * step
            val innerAngle = outerAngle + step / 2f
            val ox = cx + r * cos(outerAngle)
            val oy = cy + r * sin(outerAngle)
            val ix = cx + ri * cos(innerAngle)
            val iy = cy + ri * sin(innerAngle)
            if (i == 0) path.moveTo(ox, oy) else path.lineTo(ox, oy)
            path.lineTo(ix, iy)
            out += ox to oy
            out += ix to iy
        }
        path.close()
    }

    private fun buildLightning(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val pts = listOf(
            w * 0.52f to h * 0.16f,
            w * 0.26f to h * 0.50f,
            w * 0.46f to h * 0.47f,
            w * 0.34f to h * 0.84f,
            w * 0.68f to h * 0.40f,
            w * 0.48f to h * 0.38f,
            w * 0.60f to h * 0.16f
        )
        path.moveTo(pts[0].first, pts[0].second)
        for (i in 1 until pts.size) path.lineTo(pts[i].first, pts[i].second)
        out.addAll(pts)
    }

    private fun buildEight(w: Float, h: Float, path: Path, out: MutableList<Pair<Float, Float>>) {
        val cx = w * 0.5f
        val cy = h * 0.5f
        val rx = w * 0.24f
        val ry = h * 0.26f
        path.addOval(RectF(cx - rx, cy - ry, cx + rx, cy), Path.Direction.CW)
        path.addOval(RectF(cx - rx, cy, cx + rx, cy + ry), Path.Direction.CW)
        out += (cx + rx) to cy
        out += cx to cy
        out += (cx - rx) to cy
        out += cx to (cy - ry)
        out += (cx + rx) to cy
        out += cx to (cy + ry)
        out += (cx - rx) to cy
        out += cx to cy
    }
}
