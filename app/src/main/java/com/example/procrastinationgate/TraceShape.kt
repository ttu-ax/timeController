package com.example.procrastinationgate

import android.graphics.Path
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
    CIRCLE("圆形");

    /** 生成轨迹 Path,并按描摹顺序填充检查点到 [out]。 */
    fun buildPath(w: Float, h: Float, out: MutableList<Pair<Float, Float>>): Path {
        val path = Path()
        out.clear()
        when (this) {
            S -> buildS(w, h, path, out)
            ZIGZAG -> buildZigzag(w, h, path, out)
            SPIRAL -> buildSpiral(w, h, path, out)
            CIRCLE -> buildCircle(w, h, path, out)
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
}
